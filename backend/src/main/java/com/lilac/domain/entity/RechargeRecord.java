package com.lilac.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值记录表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "recharge_record", camelToUnderline = false)
public class RechargeRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 充值金额（元）
     */
    private BigDecimal amount;

    /**
     * 支付方式：stripe/alipay/wechat
     */
    private String paymentMethod;

    /**
     * 第三方支付ID
     */
    private String paymentId;

    /**
     * 状态：pending/success/failed/refunded
     */
    private String status;

    /**
     * 充值说明
     */
    private String description;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}