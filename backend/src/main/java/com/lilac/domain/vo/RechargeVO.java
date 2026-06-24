package com.lilac.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建充值响应视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RechargeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Stripe支付页面URL
     */
    private String checkoutUrl;

    /**
     * Stripe会话ID
     */
    private String sessionId;
}
