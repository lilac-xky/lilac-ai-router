package com.lilac.service.impl;

import com.lilac.domain.dto.billing.CallReservation;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.exception.CallRejectedException;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.service.BalanceService;
import com.lilac.service.CallSettlementService;
import com.lilac.service.QuotaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 调用结算编排：把「配额」与「余额」两次写放进同一个事务。
 */
@Service
@Slf4j
public class CallSettlementServiceImpl implements CallSettlementService {

    @Resource
    private QuotaService quotaService;
    @Resource
    private BalanceService balanceService;
    @Resource
    private AIMetricsCollector aiMetricsCollector;

    /**
     * 预留：调用模型前把配额与余额原子占住，是唯一的放行判据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserve(CallReservation reservation) {
        if (reservation == null || !reservation.isActive()) {
            return;
        }
        Long userId = reservation.userId();

        // 先占配额：这是唯一的放行判据，affected rows = 0 就是真的不够
        if (!quotaService.reserveTokens(userId, reservation.tokens())) {
            aiMetricsCollector.recordQuotaRejected(reservation.modelKey(), userId);
            throw new CallRejectedException(HttpsCodeEnum.OPERATION_ERROR,
                    "Token 配额不足：本次调用预计需要 " + reservation.tokens() + " 个 Token，请增加配额后重试");
        }

        // 再占余额：余额不足会抛异常 → 配额占用随事务一起回滚，不需要写补偿代码。
        BigDecimal cost = reservation.cost();
        if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
            try {
                balanceService.deductBalance(userId, cost, null, reservation.description("预留"));
            } catch (BusinessException e) {
                throw new CallRejectedException(HttpsCodeEnum.UNAUTHORIZED, e.getMessage());
            }
        }

        log.debug("调用预留成功：用户 {}, 模型 {}, {} Token / ¥{}", userId, reservation.modelKey(), reservation.tokens(), cost);
    }

    /**
     * 结算：把预估值对齐到实际值（多退少补）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settle(CallReservation reservation, int actualTokens, BigDecimal actualCost) {
        if (reservation == null || !reservation.isActive()) {
            return;
        }
        Long userId = reservation.userId();

        // 配额多退少补：补扣失败只在 QuotaServiceImpl 里告警，不抛异常
        quotaService.settleTokens(userId, reservation.tokens(), actualTokens);

        // 余额多退少补：补扣失败会抛异常 → 整个结算回滚（含上面的配额调整）。
        BigDecimal reserved = reservation.cost() != null ? reservation.cost() : BigDecimal.ZERO;
        BigDecimal actual = actualCost != null ? actualCost : BigDecimal.ZERO;
        BigDecimal diff = actual.subtract(reserved);
        int cmp = diff.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            balanceService.deductBalance(userId, diff, null, reservation.description("结算补扣"));
        } else if (cmp < 0) {
            balanceService.refundBalance(userId, diff.negate(), reservation.description("结算退回"));
        }

        log.debug("调用结算完成：用户 {}, 模型 {}, Token {} → {}, 费用 ¥{} → {}",
                userId, reservation.modelKey(), reservation.tokens(), actualTokens, reserved, actual);
    }

    /**
     * 退款：把预留的配额与余额整份退回（调用失败 / 流被取消）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refund(CallReservation reservation) {
        if (reservation == null || !reservation.isActive()) {
            return;
        }
        Long userId = reservation.userId();

        BigDecimal cost = reservation.cost();
        if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refundBalance(userId, cost, reservation.description("退款"));
        }
        quotaService.refundTokens(userId, reservation.tokens());

        log.info("调用预留已全额退回：用户 {}, 模型 {}, {} Token / ¥{}", userId, reservation.modelKey(), reservation.tokens(), cost);
    }
}
