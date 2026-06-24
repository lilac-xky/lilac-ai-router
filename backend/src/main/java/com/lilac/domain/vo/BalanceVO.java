package com.lilac.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 余额信息视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前余额
     */
    private BigDecimal balance;

    /**
     * 总消费
     */
    private BigDecimal totalSpending;

    /**
     * 总充值
     */
    private BigDecimal totalRecharge;
}
