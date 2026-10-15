package com.lilac.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 指标 EMA 平滑。
 */
final class EmaSmoother {

    private EmaSmoother() {
    }

    /**
     * @param previous 上一轮平滑值；{@code <= 0} 表示从未测过
     * @param sample   本轮窗口内延迟中位数；{@code <= 0} 表示本轮无有效样本
     */
    static int smoothLatency(int previous, int sample, double alpha) {
        if (sample <= 0) {
            // 本轮无样本：保持上一轮结论，清零会被当成「未测过」
            return previous;
        }
        if (previous <= 0) {
            // 0 是「未测过」哨兵：当旧值参与平滑会把新模型首轮压成 α 倍，误判成最快
            return sample;
        }
        return (int) Math.round(alpha * sample + (1 - alpha) * previous);
    }

    /**
     * @param previous  上一轮平滑值；{@code null} 表示从未测过
     * @param coldStart 由调用方按 {@code avgLatency <= 0} 判定 —— successRate 的 DDL 默认值是
     *                  100.00 而非 NULL，单看它分不出「从未测过」和「一直完美」
     */
    static BigDecimal smoothSuccessRate(BigDecimal previous, BigDecimal sample, double alpha, boolean coldStart) {
        if (sample == null) {
            return previous;
        }
        if (previous == null || coldStart) {
            return sample.setScale(2, RoundingMode.HALF_UP);
        }
        double smoothed = alpha * sample.doubleValue() + (1 - alpha) * previous.doubleValue();
        // 连续失败会停在 0.01 而非 0：0.7 * 0.01 = 0.007 进位回 0.01。阈值是 50/80，同档，无影响
        return BigDecimal.valueOf(smoothed).setScale(2, RoundingMode.HALF_UP);
    }
}
