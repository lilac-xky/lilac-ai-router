package com.lilac.service.impl;

import com.lilac.domain.entity.RequestLog;

import java.util.List;

/**
 * 延迟聚合：用中位数而不是均值。
 */
final class LatencyAggregator {

    private LatencyAggregator() {
    }

    /**
     * 窗口内延迟的中位数（毫秒）。
     *
     * @return 没有可用的延迟样本时返回 {@code 0} —— 沿用全链路既有的「0 表示未测过」哨兵值
     *         （{@code LatencyFirstRoutingStrategy} 的 SQL 与 {@code calculateScore} 都按这个约定处理）
     */
    static int medianLatency(List<RequestLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        int[] durations = logs.stream()
                .filter(l -> l.getDuration() != null && l.getDuration() >= 0)
                .mapToInt(RequestLog::getDuration)
                .sorted()
                .toArray();
        if (durations.length == 0) {
            return 0;
        }
        int mid = durations.length / 2;
        if (durations.length % 2 == 1) {
            return durations[mid];
        }
        // 偶数个取中间两个的平均：与常见中位数定义一致，且不会因取哪一侧而偏大偏小
        return (int) Math.round((durations[mid - 1] + durations[mid]) / 2.0);
    }
}
