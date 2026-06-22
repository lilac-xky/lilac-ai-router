package com.lilac.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 费用统计视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 总消费金额（元）
     */
    private BigDecimal totalCost;

    /**
     * 今日消费金额（元）
     */
    private BigDecimal todayCost;
}
