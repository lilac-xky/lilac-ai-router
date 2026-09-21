package com.lilac.service;

import com.lilac.domain.entity.RechargeRecord;
import com.lilac.domain.entity.User;
import com.lilac.mapper.RechargeRecordMapper;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 余额服务并发测试
 */
@Slf4j
public class BalanceServiceConcurrencyTest extends IntegrationTestSupport {

    /** 单笔扣费金额。余额种子取倍数，数字好算、日志好读 */
    private static final BigDecimal PER_CALL = new BigDecimal("1.0000");

    /** 并发档位：低 / 中 / 高 */
    private static final List<Integer> LEVELS = List.of(20, 100, 500);

    private static final List<Outcome> SUMMARY = Collections.synchronizedList(new ArrayList<>());

    /** 盖章字段：只有本类造的种子 {@code userName} 是这个值 */
    private static final String SEED_MARKER = "balance-test";

    /** 账号前缀；与 quota 测试的 {@code quota-test-} 前缀互不干扰 */
    private static final String ACCOUNT_PREFIX = "balance-test-";

    private static final AtomicBoolean STALE_PURGED = new AtomicBoolean(false);

    static Stream<Integer> levels() {
        return LEVELS.stream();
    }

    @Resource
    private BalanceService balanceService;
    @Resource
    private RechargeService rechargeService;
    @Resource
    private UserMapper userMapper;
    @Resource
    private RechargeRecordMapper rechargeRecordMapper;
    /** 只用于物理清理，不参与业务断言 */
    @Resource
    private DataSource dataSource;

    private String runId;

    @BeforeEach
    void prepareRun() {
        purgeStaleRows();
        runId = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        physicallyDeleteUsers(ACCOUNT_PREFIX + runId + "-%");
    }

    @AfterAll
    static void printSummary() {
        List<Outcome> rows = new ArrayList<>(SUMMARY);
        rows.sort(Comparator.comparing(Outcome::scenario).thenComparingInt(Outcome::threads));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("════════════════════ 余额并发实测汇总 ════════════════════\n");
        sb.append(String.format("%-14s %6s %14s %14s %16s %14s%n",
                "场景", "线程", "余额种子", "放行/应放行", "余额终值/期望", "丢更新金额"));
        for (Outcome o : rows) {
            sb.append(String.format("%-14s %6d %14s %14s %16s %14s%n",
                    o.scenario(), o.threads(), o.seed(), o.success() + "/" + o.expectedSuccess(),
                    o.finalBalance() + "/" + o.expectedBalance(), o.lostAmount()));
        }
        sb.append("═════════════════════════════════════════════════════════");
        log.warn("{}", sb);
    }

    // ------------------------------------------------------------------ 用例

    @ParameterizedTest(name = "余额刚好够 · {0} 线程")
    @MethodSource("levels")
    @DisplayName("A · 余额刚好够：并发扣费不得丢更新")
    void balanceExactlyEnough_noLostUpdate(int threads) {
        long userId = insertUser(PER_CALL.multiply(new BigDecimal(threads)));
        BigDecimal expectedBalance = BigDecimal.ZERO;

        Burst burst = burstDeduct(userId, threads);
        Outcome o = observe("余额刚好够", threads, userId,
                PER_CALL.multiply(new BigDecimal(threads)), expectedBalance, burst);

        assertThat(burst.timedOut())
                .as("%d 线程并发扣费超时（30s），疑似死锁或连接池饥饿", threads)
                .isZero();
        assertThat(o.success())
                .as("余额充足时 %d 次扣费必须全部成功，实际只成功 %d 次", threads, o.success())
                .isEqualTo(threads);
        assertThat(o.finalBalance())
                .as("余额守恒被打破：种子 %s - 成功 %d × %s 应为 %s，实际 %s（丢更新 = 平台少收钱）",
                        o.seed(), o.success(), PER_CALL, expectedBalance, o.finalBalance())
                .isEqualByComparingTo(expectedBalance);
    }

    @ParameterizedTest(name = "余额只够一半 · {0} 线程")
    @MethodSource("levels")
    @DisplayName("B · 余额只够一半：并发扣费不得超额放行，且余额不得为负")
    void balanceNotEnough_noOverRelease(int threads) {
        int expectedSuccess = threads / 2;
        BigDecimal seed = PER_CALL.multiply(new BigDecimal(expectedSuccess));
        long userId = insertUser(seed);

        Burst burst = burstDeduct(userId, threads);
        Outcome o = observe("余额只够一半", threads, userId, seed, BigDecimal.ZERO, burst);

        assertThat(burst.timedOut())
                .as("%d 线程并发扣费超时（30s），疑似死锁或连接池饥饿", threads)
                .isZero();
        assertThat(o.success())
                .as("超额放行：余额 %s 只够 %d 次，实际放行 %d 次", seed, expectedSuccess, o.success())
                .isEqualTo(expectedSuccess);
        assertThat(o.finalBalance())
                .as("余额终值应为 0（种子刚好被扣完），实际 %s", o.finalBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(o.finalBalance().compareTo(BigDecimal.ZERO))
                .as("余额永远不得为负，实际 %s", o.finalBalance())
                .isNotNegative();
        assertThat(o.invalidArgRejections())
                .as("被拒绝的应当是「余额不足」，不该有人是因为入参非法而返回 false：%d 次",
                        o.invalidArgRejections())
                .isZero();
    }

    @Test
    @DisplayName("C · 充值回调并发重复投递：余额只到账一次")
    void duplicateRechargeCallback_creditsOnce() throws Exception {
        BigDecimal amount = new BigDecimal("100.0000");
        long userId = insertUser(BigDecimal.ZERO);
        long recordId = insertPendingRecharge(userId, amount);

        int threads = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger noop = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final String paymentId = "pay-" + i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        rechargeService.completeRecharge(recordId, paymentId);
                    } catch (Exception e) {
                        // 抢不到幂等门时会直接 return，不会抛异常；真抛了说明实现有问题
                        log.error("重复回调线程异常", e);
                    } finally {
                        noop.incrementAndGet();
                        doneLatch.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS))
                    .as("并发回调超时（30s）")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        BigDecimal balance = currentBalance(userId);
        RechargeRecord record = rechargeRecordMapper.selectOneById(recordId);

        log.warn("[RECHARGE-IDEMPOTENT] 并发 {} 次回调 | 记录 {} | 余额 {}(应为 {}) | status={} | paymentId={}",
                noop.get(), recordId, balance, amount, record.getStatus(), record.getPaymentId());

        assertThat(balance)
                .as("重复回调导致重复到账：期望只加一次 %s，实际余额 %s", amount, balance)
                .isEqualByComparingTo(amount);
        assertThat(record.getStatus()).isEqualTo("success");
    }

    @Test
    @DisplayName("基线：串行扣费应正确累加，余额不足时抛异常且不改变余额")
    void sequentialDeduct_andInsufficientRejected() {
        long userId = insertUser(new BigDecimal("10.0000"));

        assertThat(balanceService.deductBalance(userId, PER_CALL, null, "串行测试")).isTrue();
        assertThat(currentBalance(userId)).isEqualByComparingTo(new BigDecimal("9.0000"));

        // 余额不足：扣 100 应当抛异常，且余额一分不动
        try {
            balanceService.deductBalance(userId, new BigDecimal("100.0000"), null, "超额测试");
            throw new AssertionError("余额不足时必须抛 BusinessException，实际静默成功了");
        } catch (com.lilac.exception.BusinessException expected) {
            log.info("余额不足按预期被拒：{}", expected.getMessage());
        }
        assertThat(currentBalance(userId))
                .as("被拒绝的扣费不得改动余额")
                .isEqualByComparingTo(new BigDecimal("9.0000"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 一次并发压测的原始观测值。
     * timedOut 用 int 而非 boolean：record 的组件访问器自动生成，同名的辅助方法会编译报错
     */
    private record Burst(int success, int timedOut, int invalidArgRejections, long elapsedMs) {
    }

    private record Outcome(String scenario, int threads, BigDecimal seed, int success,
                           BigDecimal finalBalance, BigDecimal expectedBalance,
                           int invalidArgRejections, long elapsedMs) {

        long expectedSuccess() {
            return seed.divide(PER_CALL, 0, java.math.RoundingMode.DOWN).longValueExact();
        }

        BigDecimal lostAmount() {
            return seed.subtract(PER_CALL.multiply(new BigDecimal(success))).subtract(finalBalance);
        }
    }

    private Outcome observe(String scenario, int threads, long userId, BigDecimal seed,
                            BigDecimal expectedBalance, Burst burst) {
        Outcome o = new Outcome(scenario, threads, seed, burst.success(),
                currentBalance(userId), expectedBalance, burst.invalidArgRejections(), burst.elapsedMs());
        log.warn("[BALANCE-RACE] {} | {} 线程 | 种子 {} | 放行 {}/{} | 终值 {}（期望 {}）| 丢更新 {} | {} ms",
                o.scenario(), threads, seed, o.success(), o.expectedSuccess(),
                o.finalBalance(), o.expectedBalance(), o.lostAmount(), burst.elapsedMs());
        SUMMARY.add(o);
        return o;
    }

    /**
     * N 个线程用起跑闸门对齐后同时扣费。
     * 余额不足抛的 BusinessException 是预期的拒绝，必须和「入参非法返回 false」分开统计
     */
    private Burst burstDeduct(long userId, int threads) {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger thrown = new AtomicInteger();
        AtomicInteger returnedFalse = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (balanceService.deductBalance(userId, PER_CALL, null, "并发测试")) {
                            success.incrementAndGet();
                        } else {
                            returnedFalse.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (com.lilac.exception.BusinessException expected) {
                        thrown.incrementAndGet();          // 余额不足，正常拒绝
                    } catch (Throwable t) {
                        log.error("并发扣费线程异常", t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startGate.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            log.info("并发扣费完成：成功 {}，余额不足被拒 {}，返回 false {}，总耗时 {} ms",
                    success.get(), thrown.get(), returnedFalse.get(), (System.nanoTime() - t0) / 1_000_000);
            return new Burst(success.get(), finished ? 0 : 1, returnedFalse.get(),
                    (System.nanoTime() - t0) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Burst(success.get(), 1, returnedFalse.get(), 0);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 用 Mapper 直连造数据，不要用被测的 service 造数据（否则测的是自己） */
    private long insertUser(BigDecimal balance) {
        User user = User.builder()
                .userAccount(ACCOUNT_PREFIX + runId + "-" + UUID.randomUUID())
                .userPassword("irrelevant")
                .userName(SEED_MARKER)
                .userRole("user")
                .userStatus("active")
                .tokenQuota(100_000L)
                .usedTokens(0L)
                .balance(balance)
                .isDelete(0)
                .build();
        // 必须 insertSelective：insert() 会把 null 写进 createTime 这类 NOT NULL DEFAULT 列
        userMapper.insertSelective(user);
        assertThat(user.getId()).isNotNull();
        return user.getId();
    }

    /** 造一条 pending 充值记录（直接走 Mapper，绕开 Stripe 下单流程） */
    private long insertPendingRecharge(long userId, BigDecimal amount) {
        RechargeRecord record = RechargeRecord.builder()
                .userId(userId)
                .amount(amount)
                .paymentMethod("stripe")
                .status("pending")
                .description("并发幂等测试")
                .createTime(LocalDateTime.now())
                .build();
        rechargeRecordMapper.insertSelective(record);
        assertThat(record.getId()).isNotNull();
        return record.getId();
    }

    private void purgeStaleRows() {
        if (STALE_PURGED.compareAndSet(false, true)) {
            int n = physicallyDeleteUsers(ACCOUNT_PREFIX + "%");
            if (n > 0) {
                log.info("清掉上次运行残留的 {} 行测试用户", n);
            }
        }
    }

    /**
     * 物理删除测试用户 —— deleteById 是逻辑删除，行会永久堆在表里。
     * WHERE 用盖章字段 + 账号前缀双条件，绝不误删真实账号；连带删掉这些用户名下的充值记录
     */
    private int physicallyDeleteUsers(String accountPattern) {
        String sql = "DELETE FROM `user` WHERE userName = ? AND userAccount LIKE ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SEED_MARKER);
            ps.setString(2, accountPattern);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                physicallyDeleteRecharges();
            }
            return deleted;
        } catch (SQLException e) {
            log.warn("物理清理测试用户失败（pattern={}），本次残留需手工清理", accountPattern, e);
            return 0;
        }
    }

    private void physicallyDeleteRecharges() {
        String sql = "DELETE FROM recharge_record WHERE description = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "并发幂等测试");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("物理清理测试充值记录失败，本次残留需手工清理", e);
        }
    }

    /** 独立查询验证终值，不复用被测服务的返回值 */
    private BigDecimal currentBalance(long userId) {
        User fresh = userMapper.selectOneById(userId);
        assertThat(fresh).as("测试用户 %s 应存在", userId).isNotNull();
        return fresh.getBalance() != null ? fresh.getBalance() : BigDecimal.ZERO;
    }
}
