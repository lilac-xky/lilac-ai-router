package com.lilac.scoring;

import java.math.BigDecimal;

/**
 * 单个模型参与评分时需要的输入：归一化边界 + 该模型自己的指标。
 *
 * @param bounds     本轮参与评分的全部模型的归一化边界；按模型逐个评分时拿不到全集，故由调用方算好传入
 * @param avgLatency 该模型 24h 窗口内的延迟统计（中位数）；0 表示还没测过
 * @param successRate 该模型 24h 窗口内的成功率（百分比）
 */
public record ScoringContext(
        ScoringBounds bounds,
        int avgLatency,
        BigDecimal successRate) {
}
