package com.lilac.scoring;

import java.math.BigDecimal;

/**
 * 评分共用的归一化与空值处理。
 * 语义与改造前 HealthCheckServiceImpl 的私有方法逐字一致 —— 这是保证「重构不改分」的前提。
 */
public final class ScoringMath {

    /** 「未测过延迟」的占位值：延迟 0 表示没有样本，不能当成 0ms 最快 */
    public static final int UNKNOWN_LATENCY = 5000;

    private ScoringMath() {
    }

    public static double normalize(int value, int min, int max) {
        int range = max - min;
        if (range == 0) {
            return 0.0;
        }
        return (double) (value - min) / range;
    }

    public static double normalize(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null || min == null || max == null) {
            return 0.0;
        }
        double range = max.subtract(min).doubleValue();
        if (range == 0) {
            return 0.0;
        }
        return value.subtract(min).doubleValue() / range;
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
