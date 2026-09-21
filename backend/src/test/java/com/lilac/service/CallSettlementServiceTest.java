package com.lilac.service;

import com.lilac.domain.dto.billing.CallReservation;
import com.lilac.domain.entity.User;
import com.lilac.exception.BusinessException;
import com.lilac.exception.CallRejectedException;
import com.lilac.mapper.UserMapper;
import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账单结算服务测试
 */
@Slf4j
public class CallSettlementServiceTest extends IntegrationTestSupport {

    private static final String SEED_MARKER = "settlement-test";
    private static final String ACCOUNT_PREFIX = "settlement-test-";

    @Resource
    private CallSettlementService callSettlementService;
    @Resource
    private UserMapper userMapper;
    @Resource
    private DataSource dataSource;

    private String runId;

    @BeforeEach
    void prepareRun() {
        runId = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        physicallyDeleteUsers(ACCOUNT_PREFIX + runId + "-%");
    }

    // ------------------------------------------------------------ 预留 reserve

    @Test
    @DisplayName("reserve：配额与余额同一条路径占住，两项都真实落库")
    void reserve_occupiesBothQuotaAndBalance() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("50.0000"));

        callSettlementService.reserve(reservation(userId, 1_000, "10.0000"));

        assertThat(currentUsedTokens(userId)).as("配额被占用").isEqualTo(1_000L);
        assertThat(currentBalance(userId)).as("余额被占用").isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("reserve：余额不足时必须整体回滚，配额不得被白占")
    void reserve_余额不足时必须整体回滚_配额不得被白占() {
        // 余额只有 1 元，却要预留 10 元
        long userId = insertUser(100_000L, 0L, new BigDecimal("1.0000"));

        assertThatThrownBy(() -> callSettlementService.reserve(reservation(userId, 1_000, "10.0000")))
                .as("余额不足应抛 CallRejectedException（账户资源不足，不该被 fallback 当成模型故障重试）")
                .isInstanceOf(CallRejectedException.class);

        assertThat(currentUsedTokens(userId))
                .as("【核心断言】余额不足导致整笔预留失败时，配额必须一起回滚："
                        + "否则用户会在每次被拒的请求里白掉 1000 Token")
                .isZero();
        assertThat(currentBalance(userId)).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("reserve：配额不足时抛异常，且不得改动余额")
    void reserve_配额不足时不得改动余额() {
        long userId = insertUser(500L, 0L, new BigDecimal("50.0000"));

        assertThatThrownBy(() -> callSettlementService.reserve(reservation(userId, 1_000, "10.0000")))
                .as("配额不足应抛 CallRejectedException")
                .isInstanceOf(CallRejectedException.class);

        assertThat(currentUsedTokens(userId)).isZero();
        assertThat(currentBalance(userId))
                .as("配额都没占上，钱更不能动")
                .isEqualByComparingTo("50.0000");
    }

    // ------------------------------------------------------------ 结算 settle

    @Test
    @DisplayName("settle：实际用量低于预留 → 配额与余额都退回差额")
    void settle_actualLowerThanReserved_refundsDifference() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("50.0000"));

        callSettlementService.reserve(reservation(userId, 1_000, "10.0000"));
        callSettlementService.settle(reservation(userId, 1_000, "10.0000"), 600, new BigDecimal("6.0000"));

        assertThat(currentUsedTokens(userId)).as("多占的 400 Token 要退回去").isEqualTo(600L);
        assertThat(currentBalance(userId))
                .as("预留扣了 ¥10、实际只花 ¥6 → 余额应当是 50 - 6 = ¥44")
                .isEqualByComparingTo("44.0000");
    }

    @Test
    @DisplayName("settle：实际用量高于预留 → 补扣差额")
    void settle_actualHigherThanReserved_chargesDifference() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("50.0000"));

        callSettlementService.reserve(reservation(userId, 1_000, "10.0000"));
        callSettlementService.settle(reservation(userId, 1_000, "10.0000"), 1_800, new BigDecimal("18.0000"));

        assertThat(currentUsedTokens(userId)).isEqualTo(1_800L);
        assertThat(currentBalance(userId)).isEqualByComparingTo("32.0000");
    }

    @Test
    @DisplayName("settle：差额补扣失败时整体回滚，不得留下半个结算")
    void settle_差额补扣失败时整体回滚_不得留下半个结算() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("10.0000"));
        callSettlementService.reserve(reservation(userId, 1_000, "10.0000"));
        // 预留把余额清空，实际费用却是 ¥100 → 差额 ¥90 一定补不上
        assertThat(currentBalance(userId)).isEqualByComparingTo("0.0000");

        assertThatThrownBy(() ->
                callSettlementService.settle(reservation(userId, 1_000, "10.0000"), 2_000, new BigDecimal("100.0000")))
                .as("差额补扣失败应抛 BusinessException")
                .isInstanceOf(BusinessException.class);

        assertThat(currentUsedTokens(userId))
                .as("【核心断言】余额补扣失败应把同一事务里的配额结算一起回滚："
                        + "终值必须还是预留值 1000，而不是结算值 2000")
                .isEqualTo(1_000L);
        assertThat(currentBalance(userId)).isEqualByComparingTo("0.0000");
    }

    // ------------------------------------------------------------ 退款 refund

    @Test
    @DisplayName("refund：失败路径全额退回预占的配额与余额")
    void refund_restoresBothQuotaAndBalance() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("50.0000"));

        callSettlementService.reserve(reservation(userId, 1_000, "10.0000"));
        callSettlementService.refund(reservation(userId, 1_000, "10.0000"));

        assertThat(currentUsedTokens(userId)).as("退回后配额应当像没调用过一样").isZero();
        assertThat(currentBalance(userId)).as("退款后余额应当回到原值").isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("refund：退回量大于占用量时不得退成负数（宁可少退，不错退）")
    void refund_neverGoesNegative() {
        long userId = insertUser(100_000L, 0L, new BigDecimal("50.0000"));

        callSettlementService.refund(reservation(userId, 5_000, "10.0000"));

        assertThat(currentUsedTokens(userId))
                .as("usedTokens 是 bigint，退多了会变成负数 —— 负的已用量会让剩余额度虚高")
                .isZero();
        assertThat(currentBalance(userId)).isEqualByComparingTo("60.0000");
    }

    // ------------------------------------------------------------ 匿名 / BYOK

    @Test
    @DisplayName("匿名与 BYOK 调用：三个结算动作都是空操作，不写任何数据")
    void anonymousCall_isNoOp() {
        CallReservation none = CallReservation.none();

        callSettlementService.reserve(none);
        callSettlementService.settle(none, 100, new BigDecimal("1.0000"));
        callSettlementService.refund(none);

        assertThat(none.isActive()).as("空凭据不该被认为是「占用了资源」").isFalse();
        log.info("匿名调用三个结算动作均为空操作，未抛异常");
    }

    // ---------------------------------------------------------------- helpers

    private CallReservation reservation(long userId, int tokens, String cost) {
        return new CallReservation(userId, "test-model", tokens, new BigDecimal(cost), "网页调用消费");
    }

    private long insertUser(long tokenQuota, long usedTokens, BigDecimal balance) {
        User user = User.builder()
                .userAccount(ACCOUNT_PREFIX + runId + "-" + UUID.randomUUID())
                .userPassword("irrelevant")
                .userName(SEED_MARKER)
                .userRole("user")
                .userStatus("active")
                .tokenQuota(tokenQuota)
                .usedTokens(usedTokens)
                .balance(balance)
                .isDelete(0)
                .build();
        userMapper.insertSelective(user);
        assertThat(user.getId()).isNotNull();
        return user.getId();
    }

    private long currentUsedTokens(long userId) {
        User fresh = userMapper.selectOneById(userId);
        assertThat(fresh).as("测试用户 %s 应存在", userId).isNotNull();
        return fresh.getUsedTokens() == null ? 0L : fresh.getUsedTokens();
    }

    private BigDecimal currentBalance(long userId) {
        User fresh = userMapper.selectOneById(userId);
        assertThat(fresh).as("测试用户 %s 应存在", userId).isNotNull();
        return fresh.getBalance() != null ? fresh.getBalance() : BigDecimal.ZERO;
    }

    private int physicallyDeleteUsers(String accountPattern) {
        String sql = "DELETE FROM `user` WHERE userName = ? AND userAccount LIKE ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SEED_MARKER);
            ps.setString(2, accountPattern);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("物理清理测试用户失败（pattern={}），本次残留需手工清理", accountPattern, e);
            return 0;
        }
    }
}
