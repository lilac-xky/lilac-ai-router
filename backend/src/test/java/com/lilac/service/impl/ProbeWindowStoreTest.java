package com.lilac.service.impl;

import com.lilac.enums.ProbeFailureKind;
import com.lilac.support.IntegrationTestSupport;
import com.lilac.task.ProbeResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针滑动窗口（Redis ZSET）的行为测试。
 *
 * <p>为什么是集成测试：这一项的全部价值就在「换成 Redis 之后语义不变、且多实例共享」，
 * 用 mock 替掉 {@code RedissonClient} 等于把被测对象整个换掉，测的是自己喂进去的东西。
 * 这里连本机真 Redis（与其它集成测试一致）。
 *
 * <h3>三个必须锁住的语义</h3>
 * <ol>
 *   <li><b>窗口为空 → null，不是 0%</b>。0% 会被判成 unhealthy，等于「没数据就拉黑」——
 *       §3.1 那个自锁就是这么来的。</li>
 *   <li><b>同一毫秒的两次探测不能互相覆盖</b>。ZSET 的 member 唯一，若只用结果当 member，
 *       连续两次失败会被折叠成一条，样本数就少了一个，成功率随之失真。</li>
 *   <li><b>Redis 抖动不能把整轮健康检查带崩</b>。读写异常时降级成「无样本」，让状态停在 unknown。</li>
 * </ol>
 */
@Slf4j
class ProbeWindowStoreTest extends IntegrationTestSupport {

    /** 用一个不可能与真实 provider 冲突的 id，避免污染真实窗口 */
    private static final long PROVIDER_ID = 999_000_001L;
    private static final String KEY = "lilac:health:probe:window:" + PROVIDER_ID;

    @Resource
    private ProbeWindowStore store;
    @Resource
    private RedissonClient redissonClient;

    @AfterEach
    void cleanUp() {
        redissonClient.getKeys().delete(KEY);
    }

    @Test
    @DisplayName("空窗口返回 null（「没有样本」不能等同于 0% 成功率）")
    void emptyWindow_returnsNull() {
        assertThat(store.successRate(PROVIDER_ID))
                .as("返回 0 会让该 provider 被判 unhealthy —— 这正是 §3.1 的自锁成因")
                .isNull();
        assertThat(store.latest(PROVIDER_ID)).isNull();
    }

    @Test
    @DisplayName("成功率按窗口内样本计算")
    void successRate_countsWithinWindow() {
        store.record(PROVIDER_ID, ProbeResult.success(120, 200));
        store.record(PROVIDER_ID, ProbeResult.success(130, 210));
        store.record(PROVIDER_ID, ProbeResult.failure(ProbeFailureKind.AUTH, 90));

        // 3 条里 2 条成功 = 66.67%
        assertThat(store.successRate(PROVIDER_ID)).isEqualByComparingTo(new BigDecimal("66.67"));
    }

    @Test
    @DisplayName("同一毫秒的连续探测各自成一条样本，不被 ZSET 折叠")
    void sameMillisecond_doesNotCollapseSamples() {
        // 三次连续同结果：member 若不唯一，ZSET 只会留 1 条，样本数从 3 变 1
        for (int i = 0; i < 3; i++) {
            store.record(PROVIDER_ID, ProbeResult.failure(ProbeFailureKind.AUTH, 90));
        }
        store.record(PROVIDER_ID, ProbeResult.success(100, 150));

        RScoredSortedSet<String> raw = redissonClient.getScoredSortedSet(KEY);
        assertThat(raw.size())
                .as("同一毫秒的重复结果被折叠会让成功率失真（4 条变 2 条）")
                .isEqualTo(4);
        assertThat(store.successRate(PROVIDER_ID)).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("latest 返回最近一次探测，失败分类可完整还原")
    void latest_returnsMostRecent() {
        store.record(PROVIDER_ID, ProbeResult.success(120, 200));
        store.record(PROVIDER_ID, ProbeResult.failure(ProbeFailureKind.AUTH, 244));

        ProbeResult latest = store.latest(PROVIDER_ID);
        assertThat(latest).isNotNull();
        assertThat(latest.isSuccess()).isFalse();
        assertThat(latest.failureKind())
                .as("失败分类必须能穿过 Redis 往返还原，否则判死规则失效")
                .isEqualTo(ProbeFailureKind.AUTH);
    }

    @Test
    @DisplayName("窗口外的陈旧样本不计入（服务停一阵后不该拿旧结果当现状）")
    void staleSamples_outsideWindowAreIgnored() {
        RScoredSortedSet<String> raw = redissonClient.getScoredSortedSet(KEY);
        // 手工塞一条 2 小时前的失败：窗口默认 60 分钟，它必须被排除
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        raw.add(twoHoursAgo, "1|AUTH|0|90");

        assertThat(store.successRate(PROVIDER_ID))
                .as("陈旧失败日志被算进来就是 §3.1 的自锁：一条旧记录锁死 24 小时")
                .isNull();

        // 新样本进来后，只按窗口内的算
        store.record(PROVIDER_ID, ProbeResult.success(100, 150));
        assertThat(store.successRate(PROVIDER_ID)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("未知的失败枚举名按 UNKNOWN 处理，不能退化成「健康」")
    void unknownFailureName_decodesAsUnknown() {
        RScoredSortedSet<String> raw = redissonClient.getScoredSortedSet(KEY);
        // 模拟枚举改名后遗留的旧数据
        raw.add(System.currentTimeMillis(), "1|SOME_OLD_NAME|0|90");

        ProbeResult latest = store.latest(PROVIDER_ID);
        assertThat(latest.isSuccess())
                .as("认不出来就当成功，会让坏厂商静默留在候选池")
                .isFalse();
        assertThat(latest.failureKind()).isEqualTo(ProbeFailureKind.UNKNOWN);
    }
}
