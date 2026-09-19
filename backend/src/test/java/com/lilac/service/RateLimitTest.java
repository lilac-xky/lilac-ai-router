package com.lilac.service;

import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流服务测试：验证超出配额时确实拒绝。
 *
 * <h3>只测服务层，不测切面</h3>
 * {@code RateLimitAspect} 依赖 {@code HttpServletRequest}（走 {@code RequestContextHolder}），
 * 还要解析 {@code Authorization} 头和客户端 IP —— 那是 web 层的事，需要 MockMvc。
 * 而且 {@code getLimitKey()} 是 private，想测就得先改可见性，那是重构不是加测试。
 *
 * <h3>不需要并发 —— 这是最容易走偏的地方</h3>
 * 看到「限流」很容易条件反射写 20 线程并发压测。<b>没必要，而且会变脆。</b>
 * {@code limit = N} 然后<b>串行</b>调 N+1 次就够了：前 N 次 true、第 N+1 次 false，完全确定性。
 * 并发是用来暴露竞态的；这里要验证的是「配额算得对」，要的是确定性 ——
 * 靠并发碰运气属于把简单问题复杂化。（Redisson 的 RRateLimiter 本身并发安全，不用操心。）
 *
 * <h3>⚠️ 限流状态在 Redis 里，跨测试运行持久 —— key 必须带 runId + 必须清理</h3>
 * 这是 {@code RoutingStrategyTest} 里 {@code modelKey} + {@code uk_modelKey} 那个坑的 <b>Redis 版</b>。
 * 麻烦点：{@code checkApiKeyRateLimit} / {@code checkIpRateLimit} <b>自己拼 key</b>（前缀写死），
 * 所以唯一性只能从「入参」注入，不能从「前缀」注入。
 *
 * <h3>⚠️⚠️ 一个逻辑限流器实际占 3 个 Redis 键，清理 pattern 必须前后都带 *</h3>
 * 实测（redis-cli KEYS 验证过），{@code rate_limit:test:<runId>:x} 会展开成：
 * <pre>
 *   rate_limit:test:&lt;runId&gt;:x              ← 主键
 *   {rate_limit:test:&lt;runId&gt;:x}:value      ← 辅助键，注意开头是「{」！
 *   {rate_limit:test:&lt;runId&gt;:x}:permits
 * </pre>
 * Redisson 用花括号做 hash-tag（集群下让这几个键落同一个 slot）。
 * 所以 {@code deleteByPattern("rate_limit:test:" + runId + "*")} <b>只能匹配到主键</b>，
 * 另外 2 个辅助键会永久残留 —— 实测踩过：跑完 3 个探针用例留下 6 个孤儿键。
 * <b>正确写法：{@code "*rate_limit:*" + runId + "*"}。</b>
 * （runId 唯一 + 所有测试键都在 {@code rate_limit:} 之下 ⇒ 一个 pattern 覆盖全部三种 key 形态。）
 *
 * <h3>三个「必须实测」的未知点 —— 结论已测出，不要凭直觉改断言</h3>
 * <ol>
 *   <li><b>{@code getAvailablePermits} 在未初始化的 key 上会抛 {@code RedisException}</b>
 *       （{@code RateLimiter is not initialized}）。所以读剩余额度前必须先 {@code tryAcquire} 一次建好限流器。
 *       详见 {@link #getAvailablePermits_throwsOnUninitializedKey_documentsWart()}。</li>
 *   <li><b>{@code trySetRate} 是 set-if-absent —— 配置变更不生效。</b>
 *       实测：先用 limit=2 耗尽，再用 limit=100 连调 3 次仍然全部 false ⇒ 新配置被静默忽略。
 *       详见 {@link #configChange_doesNotTakeEffect_documentsWart()}。</li>
 *   <li><b>补充粒度是「整个窗口」，不是「窗口 / limit」。</b>
 *       实测：limit=2 / window=1s 耗尽后，每 100ms 探测一次，+971ms 仍 false、+1084ms 才 true。
 *       如果按「窗口/limit=500ms」平滑补，+546ms 就该放行 —— 实测没有。
 *       ⇒ 断言只能写「耗尽后立刻再调被拒」（确定）+「sleep 一个完整窗口后恢复」（留余量），
 *       绝不能断言「sleep 半个窗口后仍被拒」。</li>
 * </ol>
 */
@Slf4j
public class RateLimitTest extends IntegrationTestSupport {

    /** 剩余额度：先建好限流器再读，值应等于 limit − 已消耗数 */
    private static final int PERMITS_LIMIT = 5;

    /**
     * 本次运行产生的所有限流键都含 runId，且都挂在 {@code rate_limit:} 之下
     * ⇒ 一个 pattern 就覆盖了「主键 + 花括号辅助键」的全部形态（见类注释）。
     */
    private static final String CLEAN_PATTERN = "*rate_limit:*%s*";

    /** 残留清理每个 JVM 只做一次，避免 7 个用例各扫一遍全库 */
    private static final AtomicBoolean STALE_PURGED = new AtomicBoolean(false);

    @Resource
    private RateLimitService rateLimitService;
    @Resource
    private RedissonClient redissonClient;

    /** 本次运行的唯一后缀 —— 绕开 Redis 里跨测试运行持久化的限流状态 */
    private String runId;
    /** 我完全控制键名的场景（测 tryAcquire 原始语义） */
    private String rawKey;
    /** 由 checkApiKeyRateLimit 自己拼键的场景 */
    private String testApiKey;
    /** 由 checkIpRateLimit 自己拼键的场景 */
    private String testIp;

    // ------------------------------------------------------------------ 数据准备

    @BeforeEach
    void setUp() {
        purgeStaleKeys();

        runId = UUID.randomUUID().toString().substring(0, 8);
        // test 标记只为在 redis-cli 里一眼认出是测试数据；真正保证不误删的是 runId
        rawKey = "rate_limit:test:" + runId;
        testApiKey = "sk-test-" + runId;
        testIp = "test-" + runId;
    }

    @AfterEach
    void cleanUp() {
        String pattern = String.format(CLEAN_PATTERN, runId);
        long deleted = deleteByPattern(pattern);
        if (deleted > 0) {
            log.debug("[清理·{}] 删除 {} 个限流键", runId, deleted);
        }
        // 自检：确实清干净了。残留键会在下一次跑测试时变成幽灵状态，
        // 而那种失败极难定位（症状是「单独跑绿、全量跑红」）—— 所以在这里就大声炸掉。
        assertThat(redissonClient.getKeys().getKeysByPattern(pattern))
                .as("清理后仍有残留键，pattern=%s", pattern)
                .isEmpty();
    }

    /**
     * 兜底：上一次运行被 IDE 强停（@AfterEach 没跑）时留下的垃圾。
     * 注意这里清理的是**只由测试使用**的命名空间，靠 test 标记圈范围，不会碰真实限流键。
     */
    private void purgeStaleKeys() {
        if (STALE_PURGED.compareAndSet(false, true)) {
            long n = deleteByPattern("*rate_limit:test:*")
                    + deleteByPattern("*rate_limit:api_key:sk-test-*")
                    + deleteByPattern("*rate_limit:ip:test-*");
            if (n > 0) {
                log.info("清掉上次运行残留的 {} 个限流键", n);
            }
        }
    }

    // ------------------------------------------------------------------ 核心语义

    @Test
    @DisplayName("限额内 · 连续请求全部放行")
    void withinLimit_allowsAllRequests() {
        int limit = 3;
        List<Boolean> results = acquireTimes(rawKey, limit, Duration.ofSeconds(60), limit);
        log.info("[限流·限额内·{}] limit={} 串行 {} 次 → {}", runId, limit, limit, brief(results));

        assertThat(results).as("限额内的请求必须全部放行").containsOnly(true);
    }

    @Test
    @DisplayName("超限 · 第 N+1 次被拒绝（核心用例）")
    void overLimit_rejectsRequest() {
        int limit = 2;
        List<Boolean> results = acquireTimes(rawKey, limit, Duration.ofSeconds(60), limit + 1);
        log.info("[限流·超限·{}] limit={} 串行 {} 次 → {}", runId, limit, limit + 1, brief(results));

        assertThat(results.subList(0, limit)).as("前 %d 次应全部放行", limit).containsOnly(true);
        assertThat(results.get(limit))
                .as("第 %d 次必须被拒绝，否则限流形同虚设", limit + 1)
                .isFalse();
    }

    @Test
    @DisplayName("隔离 · 不同 key 的额度互相独立")
    void eachKeyHasIndependentQuota() {
        int limit = 2;
        String keyB = rawKey + ":b";

        List<Boolean> keyA = acquireTimes(rawKey, limit, Duration.ofSeconds(60), limit + 1);
        List<Boolean> keyBResult = acquireTimes(keyB, limit, Duration.ofSeconds(60), limit);
        log.info("[限流·隔离·{}] keyA limit={} → {} | keyB → {}", runId, limit, brief(keyA), brief(keyBResult));

        assertThat(keyA.get(limit)).as("keyA 应已耗尽").isFalse();
        assertThat(keyBResult)
                .as("keyB 是独立的限流器，不能被 keyA 的消耗影响")
                .containsOnly(true);
    }

    @Test
    @DisplayName("前缀 · apiKey 与 ip 的限流键不同，同一个字符串互不干扰")
    void apiKeyAndIpLimits_useDistinctKeys() {
        int limit = 2;
        // 故意把同一个字符串分别当 apiKey 和 ip 传进去：
        // 一旦两个前缀写错、键撞在一起，这个用例立刻红 —— 用最小成本覆盖「前缀写错」这个真实风险
        String shared = "sk-test-" + runId + "-shared";

        List<Boolean> asApiKey = new ArrayList<>();
        for (int i = 0; i < limit + 1; i++) {
            asApiKey.add(rateLimitService.checkApiKeyRateLimit(shared, limit));
        }
        List<Boolean> asIp = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            asIp.add(rateLimitService.checkIpRateLimit(shared, limit));
        }
        log.info("[限流·前缀·{}] 同一字符串 limit={} 当 apiKey → {} | 当 ip → {}",
                runId, limit, brief(asApiKey), brief(asIp));

        assertThat(asApiKey.get(limit)).as("apiKey 维度应已耗尽").isFalse();
        assertThat(asIp)
                .as("ip 维度是另一个限流键，不该被 apiKey 的消耗影响 —— 被影响说明前缀撞了")
                .containsOnly(true);
    }

    @Test
    @DisplayName("窗口 · 耗尽后过完一个窗口会恢复")
    @Timeout(10)
    void windowRefills_allowsAgainAfterWindow() throws InterruptedException {
        int limit = 2;
        // 窗口取 1 秒：短到测试够快，长到能观察补充行为
        Duration window = Duration.ofSeconds(1);

        List<Boolean> first = acquireTimes(rawKey, limit, window, limit);
        boolean deniedRightAfterExhaustion = rateLimitService.tryAcquire(rawKey, limit, window);
        log.info("[限流·窗口·{}] limit={} window={} → {} | 耗尽后立刻再调 → {}",
                runId, limit, window, brief(first), deniedRightAfterExhaustion);

        assertThat(first).as("前 %d 次应放行", limit).containsOnly(true);
        // 这条是确定的：刚耗尽的那一刻必然被拒，不需要 sleep，也不会 flaky
        assertThat(deniedRightAfterExhaustion).as("耗尽后立刻再调必须被拒").isFalse();

        // 实测补充粒度是整个窗口（≈1000ms），这里留 50% 余量。
        // ⚠️ 不要把这个 sleep 缩短到窗口的一半 —— 见类注释「未知点 3」。
        Thread.sleep(window.toMillis() + 500);

        boolean afterWindow = rateLimitService.tryAcquire(rawKey, limit, window);
        log.info("[限流·窗口·{}] sleep {}ms 后再调 → {}", runId, window.toMillis() + 500, afterWindow);
        assertThat(afterWindow).as("过一个完整窗口后额度应恢复，否则限流器会永久锁死").isTrue();
    }

    @Test
    @DisplayName("额度 · 读剩余许可数：未初始化时抛异常，初始化后为 limit − 已消耗")
    void getAvailablePermits_reflectsConsumption() {
        rateLimitService.tryAcquire(rawKey, PERMITS_LIMIT, Duration.ofSeconds(60));
        long permits = rateLimitService.getAvailablePermits(rawKey);
        log.info("[限流·额度·{}] limit={} 消耗 1 次后 availablePermits={}", runId, PERMITS_LIMIT, permits);

        assertThat(permits).as("消耗 1 次后剩余应为 %d", PERMITS_LIMIT - 1).isEqualTo(PERMITS_LIMIT - 1);
    }

    // ------------------------------------------------------------------ 已知缺陷（断言的是当前行为，不是期望语义）

    @Test
    @DisplayName("已知缺陷 · trySetRate 是 set-if-absent，限流配置变更被静默忽略")
    void configChange_doesNotTakeEffect_documentsWart() {
        // 先用 limit=2 建立并耗尽
        List<Boolean> underOldConfig = acquireTimes(rawKey, 2, Duration.ofSeconds(60), 3);

        // 再用 limit=100 连调 3 次
        List<Boolean> underNewConfig = acquireTimes(rawKey, 100, Duration.ofSeconds(60), 3);
        log.info("[限流·缺陷·{}] limit=2 耗尽 → {} | 改用 limit=100 再调 → {}",
                runId, brief(underOldConfig), brief(underNewConfig));

        assertThat(underOldConfig.get(2)).as("前置：limit=2 时第 3 次已被拒").isFalse();

        // ⚠️ 下面断言的是【当前实现的行为】，不是期望语义。
        // RateLimitServiceImpl.tryAcquire 每次都调 rateLimiter.trySetRate(...)，看着像「按最新配置限流」，
        // 但 trySetRate 只在限流器不存在时生效。已存在时不改变配置。
        // 如果新配置生效，capacity=100 应该立刻放行这 3 次 —— 实际全被拒，说明仍按 limit=2 在算。
        // 影响：某个 key 的规则一旦建立过，之后改配置就不生效，必须等 key 过期或手工 DEL
        //      ⇒「限流规则可配置」目前是坏的。
        // 该修的是实现：配置变更时应按新参数重建限流器；另外 RateLimitService 目前没有提供
        // 任何「重置 / 删除限流器」的能力，运维上也补不了。
        assertThat(underNewConfig)
                .as("新 limit 未生效（这是缺陷，不是期望行为）—— 修好后本断言应改为 containsOnly(true)")
                .containsOnly(false);
    }

    @Test
    @DisplayName("已知缺陷 · 未初始化的限流器上读剩余额度会抛 RedisException")
    void getAvailablePermits_throwsOnUninitializedKey_documentsWart() {
        String freshKey = rawKey + ":never-initialized";

        // ⚠️ 断言的是【当前实现的行为】。RateLimitServiceImpl.getAvailablePermits 直接读 Redis，
        // 没有处理「限流器还不存在」这种情况，Redisson 底层会抛 RedisException。
        // 影响：任何在「第一次请求之前」查询剩余额度的接口都会 500（而不是返回一个合理的默认值）。
        // 该修的是实现：捕获未初始化的情况，返回配置的 limit（或 0），而不是把底层异常透给调用方。
        assertThatThrownBy(() -> rateLimitService.getAvailablePermits(freshKey))
                .as("未初始化时读剩余额度会抛底层异常（这是缺陷，不是期望行为）")
                .isInstanceOf(RedisException.class)
                .hasMessageContaining("not initialized");

        // 对照：先建好限流器再读就正常了 —— 这也是唯一可用的规避方式
        rateLimitService.tryAcquire(freshKey, PERMITS_LIMIT, Duration.ofSeconds(60));
        assertThat(rateLimitService.getAvailablePermits(freshKey))
                .as("先 tryAcquire 建好限流器后即可正常读取")
                .isEqualTo(PERMITS_LIMIT - 1);
    }

    // ------------------------------------------------------------------ helpers

    /** 连续申请 count 次许可，返回每次的结果（保留顺序，便于断言前几次与最后一次） */
    private List<Boolean> acquireTimes(String key, int limit, Duration window, int count) {
        List<Boolean> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(rateLimitService.tryAcquire(key, limit, window));
        }
        return results;
    }

    /**
     * 日志用：把结果列表打成 {@code true → true → false}。
     * 每个用例只打一行「实际结果」，且在断言之前 —— 断言挂了这行也已输出（沿用 RoutingStrategyTest 的约定）。
     */
    private String brief(List<Boolean> results) {
        if (results == null || results.isEmpty()) {
            return "[]";
        }
        return results.stream().map(String::valueOf).collect(Collectors.joining(" → "));
    }

    /**
     * 按 pattern 物理删除 Redis 键。
     *
     * <p>⚠️ pattern 必须能匹配到 Redisson 的花括号辅助键（{@code {key}:value} / {@code {key}:permits}），
     * 否则会留下永久残留 —— 详见类注释。删不动时只告警，不让清理失败把本来绿的用例染红。
     */
    private long deleteByPattern(String pattern) {
        try {
            return redissonClient.getKeys().deleteByPattern(pattern);
        } catch (Exception e) {
            log.warn("清理限流键失败（pattern={}），残留需手工处理：redis-cli --scan --pattern '*{}*' | xargs redis-cli DEL",
                    pattern, runId, e);
            return 0;
        }
    }
}
