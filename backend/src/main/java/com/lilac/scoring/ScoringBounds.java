package com.lilac.scoring;

import java.math.BigDecimal;

/**
 * 归一化边界：成本 / 延迟 / 优先级各自在「本轮参与评分的全部模型」里的最小最大值。
 * 按模型逐个评分时拿不到全集，所以要由调用方算好一次性传进来。
 */
public record ScoringBounds(
        BigDecimal minCost,
        BigDecimal maxCost,
        int minLatency,
        int maxLatency,
        int minPriority,
        int maxPriority) {
}
