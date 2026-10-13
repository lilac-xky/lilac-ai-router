package com.lilac.service.impl;

import com.lilac.domain.entity.RequestLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 延迟聚合必须抗极值 —— 数据取自 §3.4 的**真实 24h 分布**。
 *
 * <p>实测：{@code n=16, min=480ms, max=1352591ms}。一个 1352.591s 的连接超时把
 * {@code avgLatency} 从 ~1402ms 拉到 **85560ms**，AUTO 按 score 排序把该模型从第 1 名挤到第 3 名 ——
 * 一次故障注入改变了之后 24 小时的路由结果。
 *
 * <p>纯单测，不起 Spring 上下文。
 */
class LatencyAggregatorTest {

    /** §3.4 原始分布：15 个正常样本 + 1 个 1352.591s 的极值 */
    private static final int[] REAL_WINDOW = {
            480, 737, 795, 836, 880, 1037, 1426, 1514,
            1427, 1002, 978, 1145, 1310, 1201, 1338,
            1352591
    };

    @Test
    @DisplayName("真实分布：中位数保持秒级，均值被极值拉到 60 倍（这就是要修的东西）")
    void realDistribution_medianResistsOutlier() {
        List<RequestLog> logs = logsOf(REAL_WINDOW);

        double mean = logs.stream().mapToInt(RequestLog::getDuration).average().orElse(0);
        int median = LatencyAggregator.medianLatency(logs);

        // 均值确实被那一个极值毁了 —— 先把「问题存在」钉死，否则这个测试可能测了个寂寞
        assertThat(mean)
                .as("§3.4 记录的 85560ms 就是均值算法算出来的")
                .isGreaterThan(80_000);
        assertThat(median)
                .as("中位数应停在秒级，不能被一个极值带走")
                .isBetween(900, 1500);
    }

    @Test
    @DisplayName("极值无论多大都只占一票：放大到 10 倍不改变中位数")
    void outlierMagnitudeDoesNotMatter() {
        int[] modest = {500, 600, 700, 800, 900, 30000};
        int[] extreme = {500, 600, 700, 800, 900, 300_000};

        assertThat(LatencyAggregator.medianLatency(logsOf(extreme)))
                .as("中位数只看排序位置，极值放大 10 倍也不该动")
                .isEqualTo(LatencyAggregator.medianLatency(logsOf(modest)));
    }

    @Test
    @DisplayName("没有样本返回 0（沿用全链路「0 表示未测过」的哨兵值）")
    void emptySamples_returnsZero() {
        assertThat(LatencyAggregator.medianLatency(List.of())).isZero();
        assertThat(LatencyAggregator.medianLatency(null)).isZero();
    }

    @Test
    @DisplayName("duration 为 null 的记录被跳过，不参与也不能让整组变 0")
    void nullDurationsAreSkipped() {
        List<RequestLog> logs = new ArrayList<>();
        logs.add(log(1000));
        logs.add(log(null));
        logs.add(log(2000));

        assertThat(LatencyAggregator.medianLatency(logs))
                .as("null 不能让中位数退化成 0（0 会被当成「最快」）")
                .isEqualTo(1500);
    }

    @Test
    @DisplayName("偶数个样本取中间两个的平均；奇数个取正中")
    void evenAndOddSizes() {
        assertThat(LatencyAggregator.medianLatency(logsOf(new int[]{100, 200, 300})))
                .as("奇数个取正中").isEqualTo(200);
        assertThat(LatencyAggregator.medianLatency(logsOf(new int[]{100, 200, 300, 400})))
                .as("偶数个取中间两个的平均").isEqualTo(250);
    }

    @Test
    @DisplayName("单个样本时中位数就是它自己（不能因为「样本少」返回 0）")
    void singleSample_returnsItself() {
        assertThat(LatencyAggregator.medianLatency(logsOf(new int[]{1427}))).isEqualTo(1427);
    }

    private static List<RequestLog> logsOf(int[] durations) {
        List<RequestLog> logs = new ArrayList<>(durations.length);
        for (int d : durations) {
            logs.add(log(d));
        }
        return logs;
    }

    private static RequestLog log(Integer duration) {
        RequestLog entry = new RequestLog();
        entry.setDuration(duration);
        entry.setStatus("success");
        return entry;
    }
}
