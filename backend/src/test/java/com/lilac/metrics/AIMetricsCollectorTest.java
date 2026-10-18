package com.lilac.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「同名 meter 的 tag key 集合必须完全一致」这条 Prometheus 硬约束。
 *
 * <p>违反它的表现很隐蔽：{@code MeterRegistry.register} 不抛异常，只是把 meter 丢掉，
 * 于是指标静默消失、面板一直是空的（W7 §3.5 踩过一次）。所以这里逐个方法调用一遍，
 * 再检查注册表里同名 meter 的 tag key 是否一致。
 */
class AIMetricsCollectorTest {

    /**
     * 全量调用一遍所有埋点方法：新增方法若引用了未覆盖的 tag 组合，这里的断言会失败
     */
    private static AIMetricsCollector newCollectorWithAllMetrics(SimpleMeterRegistry registry) {
        AIMetricsCollector collector = new AIMetricsCollector(registry);
        collector.recordRequest("deepseek-flash");
        collector.recordRequest(null);
        collector.recordTokens("deepseek-flash", 128);
        collector.recordTokens("deepseek-flash", 0);
        collector.recordError("deepseek-flash", "MODEL_ERROR");
        collector.recordError(null, null);
        collector.recordResponseTime("deepseek-flash", 320);
        collector.recordResponseTime(null, 320);
        collector.recordQuotaRejected("deepseek-flash", 1L);
        collector.recordQuotaRejected(null, 1L);
        collector.recordSettlementDeficit("deepseek-flash", 1L, new BigDecimal("0.01"));
        collector.recordSettlementDeficit(null, 1L, new BigDecimal("0.01"));
        collector.recordProbeSuccess("deepseek", "deepseek-flash", 210);
        collector.recordProbeSuccess(null, null, 210);
        collector.recordProbeFailure("deepseek", "deepseek-flash", "AUTH");
        collector.recordProbeFailure(null, null, null);
        return collector;
    }

    @Test
    void sameMeterName_alwaysCarriesTheSameTagKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newCollectorWithAllMetrics(registry);

        Map<String, Set<Set<String>>> tagKeysByName = registry.getMeters().stream()
                .collect(Collectors.groupingBy(meter -> meter.getId().getName()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .map(meter -> meter.getId().getTags().stream()
                                .map(Tag::getKey)
                                .collect(Collectors.toSet()))
                        .collect(Collectors.toSet())));

        assertTrue(tagKeysByName.size() >= 8, "埋点数量不对，实际=" + tagKeysByName.keySet());
        tagKeysByName.forEach((name, tagKeySets) -> assertEquals(1, tagKeySets.size(),
                "meter「" + name + "」出现了多套 tag key：" + tagKeySets
                        + "；Prometheus 会丢弃后注册的 meter，指标会静默消失"));
    }

    /**
     * user_id 是无界高基数维度，一旦进 tag，压测时时间序列数量等于虚拟用户数
     */
    @Test
    void noMeterIsTaggedByUserId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newCollectorWithAllMetrics(registry);

        List<String> offenders = registry.getMeters().stream()
                .filter(meter -> meter.getId().getTags().stream()
                        .anyMatch(tag -> tag.getKey().equals("user_id")))
                .map(meter -> meter.getId().getName())
                .toList();

        assertTrue(offenders.isEmpty(), "禁止用 user_id 做 tag（无界高基数）：" + offenders);
    }

    /**
     * 撞名的直接后果是 ai.errors 与 ai.errors.total 都导出成 ai_errors_total
     */
    @Test
    void noTwoMetersExportedUnderTheSamePrometheusName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newCollectorWithAllMetrics(registry);

        // 同一 name 的不同 tag 值在导出后本来就同名（这是时间序列，不是重名），只有 name 重复才是问题
        Set<String> names = registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());

        Set<String> seen = new HashSet<>();
        List<String> duplicates = names.stream()
                .map(name -> name.replace('.', '_'))
                .filter(exported -> !seen.add(exported))
                .distinct()
                .toList();

        assertTrue(duplicates.isEmpty(), "导出后重名（会互相覆盖）：" + duplicates
                + "；原始 name=" + names);
    }
}
