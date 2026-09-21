package com.lilac.service;

import com.lilac.domain.dto.billing.CallReservation;

import java.math.BigDecimal;

/**
 * 调用结算编排：把「配额」与「余额」两次写放进同一个事务。
 */
public interface CallSettlementService {

    /**
     * 预留：调用模型前把配额与余额原子占住，是唯一的放行判据。
     */
    void reserve(CallReservation reservation);

    /**
     * 结算：把预估值对齐到实际值（多退少补）。
     */
    void settle(CallReservation reservation, int actualTokens, BigDecimal actualCost);

    /**
     * 退款：把预留的配额与余额整份退回（调用失败 / 流被取消）。
     */
    void refund(CallReservation reservation);
}
