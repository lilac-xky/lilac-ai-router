package com.lilac.service;

import com.lilac.domain.entity.User;
import com.lilac.mapper.UserMapper;
import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * 配额并发扣减回归测试。<b>常驻，无任何开关。</b>
 *
 * <p>被测契约（修好后必须一直成立，断言写的就是契约本身）：
 * <ol>
 *   <li><b>计数守恒</b>：{@code deductTokens} 自称成功 n 次，库里 {@code usedTokens} 就必须多记 n × 单次量；</li>
 *   <li><b>不超额</b>：放行次数不得超过 {@code floor(tokenQuota / 单次量)}；</li>
 *   <li><b>剩余额度自洽</b>：扣满后 {@code getRemainingQuota} 必须为 0。</li>
 * </ol>
 *
 * <p>故意<b>不断言实现</b>（不要求特定 SQL / 锁类型），这样换修法不用改测试。
 *
 * <p>并发档位取 20 / 100 / 500：20 是本机 Hikari 连接池（test profile 里 32）能真正并行的量级，
 * 100 与 500 用于观察错误率随并发上升的走势。每个档位跑两个场景：
 * <ul>
 *   <li>A 配额刚好够 —— 暴露「记账丢失」（lost update）；</li>
 *   <li>B 配额只够一半 —— 暴露「超额放行 + 记账丢失」。</li>
 * </ul>
 * 每个用例先算完全部指标再断言（断言失败也不会丢数据），{@code @AfterAll} 再打一张总表。
 *
 * <p><b>不用 {@code @Transactional}</b>：并发线程不在测试事务里，看不到未提交的种子数据，
 * 而且工作线程的写入也不会跟着回滚 —— 这是并发测试最经典的假绿来源。
 */
@Slf4j
public class QuotaServiceConcurrencyTest extends IntegrationTestSupport {

    /** 单次扣减量。`quota = 次数 × 100`，数字好算、日志好读 */
    private static final int PER_CALL = 100;

    /** 并发档位：低 / 中 / 高 */
    private static final List<Integer> LEVELS = List.of(20, 100, 500);

    /** 汇总行，{@code @AfterAll} 打总表用（并发用例跑完顺序不定，所以按档位排序输出） */
    private static final List<Outcome> SUMMARY = Collections.synchronizedList(new ArrayList<>());

    /** 盖章字段：只有本类造的种子 {@code userName} 是这个值，清理时靠它圈范围 */
    private static final String SEED_MARKER = "quota-test";

    /** 账号前缀；真实账号（实测是 {@code lilac}）不带这个前缀 —— 双条件 = 绝不误删 */
    private static final String ACCOUNT_PREFIX = "quota-test-";

    /** 残留清理每个 JVM 只做一次，避免 7 个用例各扫一遍全表 */
    private static final AtomicBoolean STALE_PURGED = new AtomicBoolean(false);

    static Stream<Integer> levels() {
        return LEVELS.stream();
    }

    @Resource
    private QuotaService quotaService;
    @Resource
    private UserMapper userMapper;
    /** 只用于物理清理，不参与业务断言 */
    @Resource
    private DataSource dataSource;

    /** 本次运行的唯一后缀，兼作清理范围 */
    private String runId;

    @BeforeEach
    void prepareRun() {
        purgeStaleRows();
        runId = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        // 测试数据的生命周期 = 测试的生命周期。
        // userMapper.deleteById() 是逻辑删除（UPDATE `user` SET isDelete=1），行会永久留在表里，
        // 跑一次攒一批，用户就得手工 DELETE。本类根本不验删除语义，所以直接物理删掉。
        physicallyDelete(ACCOUNT_PREFIX + runId + "-%");
    }

    @AfterAll
    static void printSummary() {
        List<Outcome> rows = new ArrayList<>(SUMMARY);
        rows.sort(Comparator.comparing(Outcome::scenario).thenComparingInt(Outcome::threads));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("════════════════════ 配额并发实测汇总 ════════════════════\n");
        sb.append(String.format("%-12s %6s %8s %16s %16s %12s %12s%n",
                "场景", "线程", "quota", "放行/应放行", "used/自称", "记账丢失率", "超额放行率"));
        for (Outcome o : rows) {
            sb.append(String.format("%-12s %6d %8d %16s %16s %11.2f%% %11.2f%%%n",
                    o.scenario(), o.threads(), o.quota(),
                    o.success() + "/" + o.expectedSuccess(),
                    o.used() + "/" + o.claimed(),
                    o.lostRatePct(), o.overReleasePct()));
        }
        sb.append("═════════════════════════════════════════════════════════");
        log.warn("{}", sb);
    }

    // ------------------------------------------------------------------ 基线

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

    // ------------------------------------------------------------ 并发回归用例

    @ParameterizedTest(name = "配额刚好够 · {0} 线程")
    @MethodSource("levels")
    @DisplayName("A · 配额刚好够：并发扣减不得丢失更新")
    void quotaExactlyEnough_noLostUpdate(int threads) throws Exception {
        long quota = (long) threads * PER_CALL;          // 刚好够所有线程各扣一次
        long userId = insertUser(quota, 0L);

        Burst burst = burstDeduct(userId, threads, PER_CALL);
        Outcome o = observe("配额刚好够", threads, quota, userId, burst);

        assertThat(burst.timedOut())
                .as("%d 线程并发扣减超时（30s），疑似死锁或连接池饥饿", threads)
                .isFalse();
        assertThat(o.used())
                .as("计数守恒被打破：自称成功 %d 次 × %d = %d，库里只有 %d（丢 %d，丢失率 %.2f%%）",
                        o.success(), PER_CALL, o.claimed(), o.used(),
                        o.claimed() - o.used(), o.lostRatePct())
                .isEqualTo(quota);
        assertThat(o.success())
                .as("配额充足时 %d 次调用必须全部成功，实际只成功 %d 次（误拒 %d 次）",
                        threads, o.success(), threads - o.success())
                .isEqualTo(threads);
        assertThat(o.remaining())
                .as("扣满后剩余额度应为 0，实际 %d（虚高 %d）", o.remaining(), o.remaining())
                .isZero();
    }

    @ParameterizedTest(name = "配额只够一半 · {0} 线程")
    @MethodSource("levels")
    @DisplayName("B · 配额只够一半：并发扣减不得超额放行")
    void quotaNotEnough_noOverRelease(int threads) throws Exception {
        int expectedSuccess = threads / 2;               // 配额只够一半线程
        long quota = (long) expectedSuccess * PER_CALL;
        long userId = insertUser(quota, 0L);

        Burst burst = burstDeduct(userId, threads, PER_CALL);
        Outcome o = observe("配额只够一半", threads, quota, userId, burst);

        assertThat(burst.timedOut())
                .as("%d 线程并发扣减超时（30s），疑似死锁或连接池饥饿", threads)
                .isFalse();
        assertThat(o.success())
                .as("超额放行：配额 %d 只够 %d 次，实际放行 %d 次（多放 %d 次，超额率 %.2f%%）",
                        quota, expectedSuccess, o.success(),
                        o.success() - expectedSuccess, o.overReleasePct())
                .isEqualTo(expectedSuccess);
        assertThat(o.used())
                .as("被拒绝的调用不得计入已用额度：库里应为 %d，实际 %d（少记 %d）",
                        quota, o.used(), quota - o.used())
                .isEqualTo(quota);
    }

    // ---------------------------------------------------------------- helpers

    /** 一次并发压测的原始观测值 */
    private record Burst(int success, boolean timedOut, long elapsedMs) {
    }

    /**
     * 观测结果 + 派生错误率。分场景看不同指标：
     * <ul>
     *   <li>A 场景看 {@link #lostRatePct()}：记账丢失率（自称扣掉的钱有多少没落库）；</li>
     *   <li>B 场景看 {@link #overReleasePct()}：超额放行率（相对"配额只够的次数"多放了多少）。</li>
     * </ul>
     */
    private record Outcome(String scenario, int threads, long quota, int success,
                           long used, long remaining, long elapsedMs) {

        /** 自称成功的调用一共"扣"掉了多少 token */
        long claimed() {
            return (long) success * PER_CALL;
        }

        /** 按配额本该成功多少次 */
        long expectedSuccess() {
            return quota / PER_CALL;
        }

        /** 记账丢失率（%） */
        double lostRatePct() {
            return claimed() == 0 ? 0.0 : (claimed() - used) * 100.0 / claimed();
        }

        /** 超额放行率（%） */
        double overReleasePct() {
            long expected = expectedSuccess();
            return expected == 0 ? 0.0 : Math.max(0, success - expected) * 100.0 / expected;
        }

        /** 单行结构化日志，方便 grep / 复制进文档 */
        String line() {
            return String.format(
                    "[QUOTA-RACE] %s | %3d 线程 | quota=%d | 放行 %d/%d | used=%d(自称 %d) | remaining=%d "
                            + "| 记账丢失 %d token = %.2f%% | 超额放行 %d 次 = %.2f%% | %.0f ms",
                    scenario, threads, quota, success, expectedSuccess(), used, claimed(), remaining,
                    claimed() - used, lostRatePct(),
                    Math.max(0, success - (int) expectedSuccess()), overReleasePct(), (double) elapsedMs);
        }
    }

    /** 采一次观测：读库拿终值（不复用被测服务的返回值），打日志，收进汇总 */
    private Outcome observe(String scenario, int threads, long quota, long userId, Burst burst) {
        Outcome o = new Outcome(scenario, threads, quota, burst.success(),
                currentUsedTokens(userId), quotaService.getRemainingQuota(userId), burst.elapsedMs());
        log.warn("{}", o.line());
        SUMMARY.add(o);
        return o;
    }

    /**
     * N 个线程用 startGate 对齐后同时发起扣减。
     *
     * <p>起跑闸门是并发测试的关键：不闸的话前几个线程可能已经跑完，后几个才刚开始，
     * 交错窗口被拉长，覆盖冲突的概率会随机器负载乱跳。
     */
    private Burst burstDeduct(long userId, int threads, int perCall) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
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
            return new Burst(success.get(), !finished, (System.nanoTime() - t0) / 1_000_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 用 Mapper 直连造数据，不要用被测的 service 造数据（否则测的是自己） */
    private long insertUser(long tokenQuota, long usedTokens) {
        User user = User.builder()
                .userAccount(ACCOUNT_PREFIX + runId + "-" + UUID.randomUUID())
                .userPassword("irrelevant")
                .userName(SEED_MARKER)
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
        return user.getId();
    }

    // ---------------------------------------------------------------- 物理清理

    /**
     * 兜底：上一次运行被 IDE 强停（{@code @AfterEach} 没来得及跑）时留下的垃圾。
     * 每个 JVM 只扫一次 —— 不加这层，那些行会一直堆在表里，表越来越脏。
     */
    private void purgeStaleRows() {
        if (STALE_PURGED.compareAndSet(false, true)) {
            int n = physicallyDelete(ACCOUNT_PREFIX + "%");
            if (n > 0) {
                log.info("清掉上次运行残留的 {} 行测试用户", n);
            }
        }
    }

    /**
     * 物理删除测试用户 —— 本类唯一"绕开 ORM"的地方，但它是必要的。
     *
     * <p>{@code userMapper.deleteById()} 是逻辑删除（{@code UPDATE `user` SET isDelete=1}），
     * 行会永久堆在表里，每跑一次测试攒一批，得手工 {@code DELETE}。
     *
     * <p>WHERE 写两个条件，双保险不误删真实账号：
     * <ol>
     *   <li>{@code userName = 'quota-test'} —— 只有本类造的种子带这个标记</li>
     *   <li>{@code userAccount LIKE ?} —— 由调用方决定圈本次运行还是圈全部残留</li>
     * </ol>
     *
     * @return 实际删除行数；失败不抛（清理失败不应让一个本来绿了的用例变红），只告警
     */
    private int physicallyDelete(String accountPattern) {
        String sql = "DELETE FROM `user` WHERE userName = ? AND userAccount LIKE ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SEED_MARKER);
            ps.setString(2, accountPattern);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("物理清理测试用户失败（pattern={}），本次残留需手工清理："
                    + "DELETE FROM `user` WHERE userName = 'quota-test'", accountPattern, e);
            return 0;
        }
    }

    /** 独立查询验证终值，不复用被测服务的读缓存/返回值 */
    private long currentUsedTokens(long userId) {
        User fresh = userMapper.selectOneById(userId);
        assertThat(fresh).as("测试用户 %s 应存在", userId).isNotNull();
        return fresh.getUsedTokens() == null ? 0L : fresh.getUsedTokens();
    }
}
