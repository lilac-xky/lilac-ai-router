package com.lilac.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 用户综合统计视图对象
 */
@Data
@NoArgsConstructor
public class UserSummaryStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 总Token数（来自日志统计）
     */
    private Long totalTokens;

    /**
     * Token配额（-1表示无限制）
     */
    private Long tokenQuota;

    /**
     * 已使用Token数（来自用户表）
     */
    private Long usedTokens;

    /**
     * 剩余配额（-1表示无限制）
     */
    private Long remainingQuota;

    /**
     * 总消费金额（元）
     */
    private BigDecimal totalCost;

    /**
     * 今日消费金额（元）
     */
    private BigDecimal todayCost;

    /**
     * 总请求数
     */
    private Long totalRequests;

    /**
     * 成功请求数
     */
    private Long successRequests;
}
