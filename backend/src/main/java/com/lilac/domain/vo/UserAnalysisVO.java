package com.lilac.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 用户使用分析视图对象
 */
@Data
@NoArgsConstructor
public class UserAnalysisVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户状态
     */
    private String userStatus;

    /**
     * 用户角色
     */
    private String userRole;

    /**
     * Token配额（-1表示无限制）
     */
    private Long tokenQuota;

    /**
     * 已使用Token数
     */
    private Long usedTokens;

    /**
     * 剩余配额（-1表示无限制）
     */
    private Long remainingQuota;

    /**
     * 总请求数
     */
    private Long totalRequests;

    /**
     * 成功请求数
     */
    private Long successRequests;

    /**
     * 总Token数
     */
    private Long totalTokens;

    /**
     * 总消费金额（元）
     */
    private BigDecimal totalCost;

    /**
     * 今日消费金额（元）
     */
    private BigDecimal todayCost;
}
