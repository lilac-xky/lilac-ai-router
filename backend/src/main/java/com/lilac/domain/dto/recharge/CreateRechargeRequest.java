package com.lilac.domain.dto.recharge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 创建充值请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRechargeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 充值金额（元）
     */
    private BigDecimal amount;
}
