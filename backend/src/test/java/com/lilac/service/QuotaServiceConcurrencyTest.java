package com.lilac.service;

import com.lilac.domain.entity.User;
import com.lilac.mapper.UserMapper;
import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * 配额并发扣减测试。
 *
 * <p>W6 两条用例<b>默认执行</b>（开关默认 true），修好之前必红。CI 上关掉：
 * {@code mvn verify -Dw6pending=false}（已写进 .github/workflows/ci.yml）。</p>
 */
@Slf4j
public class QuotaServiceConcurrencyTest extends IntegrationTestSupport {

    private static final int THREADS = 20;
    private static final int PER_CALL = 100;

    /**
     * W6 开关属性名
     */
    private static final String W6_PROP = "w6pending";

    /**
     * W6 开关
     */
    private static final boolean W6_ARMED = Boolean.parseBoolean(System.getProperty(W6_PROP, "true"));

    /** 闸门条件：读常量而不是原始属性。 */
    static boolean w6Armed() {
        return W6_ARMED;
    }

    /** 唯一的开关 */
    @BeforeAll
    static void announceGate() {
        String raw = System.getProperty(W6_PROP);
        log.warn("W6 开关 {}（-D{}={}）| {}", W6_ARMED ? "已开启" : "未开启", W6_PROP,
                raw == null ? "<未设置，取默认 true>" : raw,
                W6_ARMED ? "W6-1 / W6-2 会真正执行" : "W6-1 / W6-2 被【跳过】");
    }

    @Resource
    private QuotaService quotaService;
    @Resource
    private UserMapper userMapper;

    private final List<Long> createdUserIds = new ArrayList<>();

    /** 清理测试用户 */
    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(id -> {
            try {
                userMapper.deleteById(id);
            } catch (Exception e) {
                log.warn("清理测试用户 {} 失败", id, e);
            }
        });
        createdUserIds.clear();
    }

    // ------------------------------------------------------------------ 基线

    /** 环境自检 + 防"修过头"：串行扣减的累加语义不能被 W6 改动破坏。 */
    @Test
    @DisplayName("基线：串行扣减应正确累加，且不触碰配额上限时不得误拒")
    void sequentialDeduct_accumulatesCorrectly() {
        long userId = insertUser(10_000L, 0L);

        for (int i = 0; i < 3; i++) {
            assertThat(quotaService.deductTokens(userId, PER_CALL)).isTrue();
        }

        assertThat(currentUsedTokens(userId)).isEqualTo(300L);
        assertThat(quotaService.getRemainingQuota(userId)).isEqualTo(9_700L);
        assertThat(quotaService.checkQuota(userId)).isTrue();
    }

    // ------------------------------------------------------- W6 门槛用例（今天必红）

    @Test
    @EnabledIf("w6Armed")
    @DisplayName("W6-1 · 配额刚好够：20 线程并发扣减不得丢失更新")
    void deductTokens_shouldNotLoseUpdate_whenQuotaExactlyEnough() throws Exception {
        long quota = (long) THREADS * PER_CALL;      // 2000，刚好够 20 次
        long userId = insertUser(quota, 0L);

        BurstResult result = burstDeduct(userId, PER_CALL);
        long used = currentUsedTokens(userId);
        long claimed = (long) result.success() * PER_CALL;

        assertThat(result.timedOut()).as("并发扣减超时，疑似死锁或连接池饥饿").isFalse();
        assertThat(used)
                .as("计数守恒被打破：自称成功 %d 次 × %d = %d，库里只有 %d（丢 %d）",
                        result.success(), PER_CALL, claimed, used, claimed - used)
                .isEqualTo(quota);
        assertThat(result.success())
                .as("配额充足时 20 次调用必须全部成功，实际成功 %d 次", result.success())
                .isEqualTo(THREADS);
        assertThat(quotaService.getRemainingQuota(userId))
                .as("扣满后剩余额度应为 0，实际 %d", quotaService.getRemainingQuota(userId))
                .isZero();
    }

    @Test
    @EnabledIf("w6Armed")
    @DisplayName("W6-2 · 配额只够一半：并发扣减不得超出配额")
    void deductTokens_shouldRejectInsufficient_whenQuotaNotEnough() throws Exception {
        long quota = 10L * PER_CALL;                 // 1000，只够 10 次
        long userId = insertUser(quota, 0L);

        BurstResult result = burstDeduct(userId, PER_CALL);
        long used = currentUsedTokens(userId);

        assertThat(result.timedOut()).as("并发扣减超时，疑似死锁或连接池饥饿").isFalse();
        assertThat(used)
                .as("已用额度超出配额：quota = %d，库里 usedTokens = %d", quota, used)
                .isLessThanOrEqualTo(quota);
        assertThat(used)
                .as("被拒绝的调用不得计入已用额度：库里应为 %d，实际 %d（少记 %d）", quota, used, quota - used)
                .isEqualTo(quota);
        assertThat(result.success())
                .as("配额只够 10 次，成功次数必须恰为 10，实际 %d 次（超额放行 %d 次）",
                        result.success(), result.success() - 10)
                .isEqualTo(10);
    }

    // ---------------------------------------------------------------- helpers

    private record BurstResult(int success, boolean timedOut) {
    }

    /**
     * 20 个线程用 startGate 对齐后同时发起扣减。
     *
     * <p>不用 @Transactional：并发线程不在测试事务里，看不到未提交的种子数据，
     * 而且工作线程的写入也不会跟着回滚 —— 这是并发测试最经典的假绿来源。</p>
     */
    private BurstResult burstDeduct(long userId, int perCall) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();              // 对齐起跑线，保证真并发
                        if (quotaService.deductTokens(userId, perCall)) {
                            success.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        log.error("并发扣减线程异常", t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startGate.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            return new BurstResult(success.get(), !finished);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 用 Mapper 直连造数据，不要用被测的 service 造数据（否则测的是自己） */
    private long insertUser(long tokenQuota, long usedTokens) {
        User user = User.builder()
                .userAccount("quota-test-" + UUID.randomUUID())
                .userPassword("irrelevant")
                .userName("quota-test")
                .userRole("user")
                .userStatus("active")
                .tokenQuota(tokenQuota)
                .usedTokens(usedTokens)
                .balance(BigDecimal.ZERO)
                .isDelete(0)
                .build();

        // 必须 insertSelective：insert() 会把 null 写进 editTime/createTime 这类
        // "NOT NULL DEFAULT CURRENT_TIMESTAMP" 列，显式 NULL 会覆盖默认值直接报错
        userMapper.insertSelective(user);
        assertThat(user.getId()).isNotNull();

        createdUserIds.add(user.getId());
        return user.getId();
    }

    /** 独立查询验证终值，不复用被测服务的读缓存/返回值 */
    private long currentUsedTokens(long userId) {
        User fresh = userMapper.selectOneById(userId);
        assertThat(fresh).as("测试用户 %s 应存在", userId).isNotNull();
        return fresh.getUsedTokens() == null ? 0L : fresh.getUsedTokens();
    }
}
