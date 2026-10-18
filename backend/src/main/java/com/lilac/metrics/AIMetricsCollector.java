package com.lilac.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 链路指标收集器
 */
@Slf4j
@Component
public class AIMetricsCollector {

    private final MeterRegistry meterRegistry;

    // Prometheus 要求同名 meter 的 tag key 集合完全一致，撞了直接注册失败且不报给调用方。
    // 所以每个名字固定一套 tag、统一在这里建，调用方只给值
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public AIMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次成功请求
     */
    public void recordRequest(String modelKey) {
        counter("ai.requests", "AI 请求数", "model", modelKey).increment();
    }

    /**
     * 记录 Token 消耗
     */
    public void recordTokens(String modelKey, int tokens) {
        if (tokens <= 0) {
            return;
        }
        counter("ai.tokens", "Token 消耗总量", "model", modelKey).increment(tokens);
    }

    /**
     * 记录错误
     */
    public void recordError(String modelKey, String errorType) {
        counter("ai.errors", "错误总数", "model", modelKey, "type", errorType).increment();
    }

    /**
     * 记录端到端响应时间
     */
    public void recordResponseTime(String modelKey, long durationMillis) {
        if (durationMillis < 0) {
            return;
        }
        timer("ai.response.time", "AI 端到端响应时间", "model", modelKey)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录「配额预留被拒」：额度不够，请求未打到上游。
     * 只按模型打标签：user_id 是无界高基数维度，进 tag 会让时间序列爆炸，定位用户看日志
     */
    public void recordQuotaRejected(String modelKey, Long userId) {
        counter("ai.quota.rejected", "配额预留被拒次数", "model", modelKey).increment();
        log.warn("配额预留被拒：模型 {}, 用户 {}", modelKey, userId);
    }

    /**
     * 记录「结算差额追缴失败」：欠费规模的直接度量。
     * 与「配额被拒」区分：前者是拦住了，这里是没拦住、钱漏了
     */
    public void recordSettlementDeficit(String modelKey, Long userId, BigDecimal deficit) {
        counter("ai.settlement.deficit", "结算差额追缴失败次数", "model", modelKey).increment();
        log.warn("结算差额追缴失败（欠费）：模型 {}, 用户 {}, 缺口 ¥{}", modelKey, userId, deficit);
    }

    /**
     * 记录健康探针成功时的 TTFT。
     * 与 {@code ai.response.time} 刻意分开：探针的价值就在「首字节等了多久」，
     * 混进端到端会把生成时间算进去，看不出上游变慢还是输出变长
     */
    public void recordProbeSuccess(String providerName, String modelKey, long ttftMillis) {
        timer("ai.probe.ttft", "健康探针首 token 耗时", "provider", providerName, "model", modelKey)
                .record(ttftMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录探针失败，按分类打标签 —— 这是「可达但全错」能被看见的地方
     */
    public void recordProbeFailure(String providerName, String modelKey, String failureKind) {
        counter("ai.probe.failed", "健康探针失败次数（按错误分类）",
                "provider", providerName, "model", modelKey, "kind", failureKind).increment();
    }

    /**
     * 记录一次路由决策：选中了哪个厂商、走的是哪条策略。
     * 这是「加权评分路由到底有没有生效」唯一的线上证据 —— 只看日志要翻几千行才能统计
     */
    public void recordRoutingDecision(String strategy, String providerName, String modelKey) {
        counter("ai.routing.decisions", "路由决策次数",
                "strategy", strategy, "provider", providerName, "model", modelKey).increment();
    }

    /**
     * 记录首 token 耗时（TTFT）。
     * 与 {@code ai.response.time} 分开：端到端时长混了生成长度，看不出是上游变慢还是输出变长
     */
    public void recordTtft(String providerName, String modelKey, long ttftMillis) {
        if (ttftMillis < 0) {
            return;
        }
        timer("ai.ttft", "首 token 耗时", "provider", providerName, "model", modelKey)
                .record(ttftMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次降级：主选失败换到备选。
     * from/to 分别是两个模型，便于回答「哪个模型最不稳定、谁在给它兜底」
     */
    public void recordFallback(String fromModelKey, String toModelKey, String reason) {
        counter("ai.fallback", "降级次数",
                "from", fromModelKey, "to", toModelKey, "reason", reason).increment();
    }

    /**
     * 登记熔断器状态：0=关闭 1=打开 2=半开。
     * Gauge 持的是 supplier 而不是快照值，采集时才读，才跟得上熔断器的真实状态
     */
    public void registerCircuitStateGauge(String providerName, java.util.function.IntSupplier stateSupplier) {
        io.micrometer.core.instrument.Gauge
                .builder("ai.circuit.state", stateSupplier, source -> source.getAsInt())
                .description("熔断器状态：0=关闭 1=打开 2=半开")
                .tag("provider", safe(providerName))
                .register(meterRegistry);
    }

    private Counter counter(String name, String description, String... tags) {
        String key = cacheKey(name, tags);
        return counters.computeIfAbsent(key, k ->
                Counter.builder(name).description(description).tags(normalize(tags)).register(meterRegistry));
    }

    private Timer timer(String name, String description, String... tags) {
        String key = cacheKey(name, tags);
        return timers.computeIfAbsent(key, k ->
                Timer.builder(name).description(description).tags(normalize(tags)).register(meterRegistry));
    }

    /**
     * Tag.of 对 null 值直接抛 NPE，而 modelKey / provider 在上游缺数据时确实会是 null
     */
    private static String[] normalize(String... tags) {
        String[] normalized = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            normalized[i] = i % 2 == 0 ? tags[i] : safe(tags[i]);
        }
        return normalized;
    }

    /**
     * tag 顺序由调用方固定（名字在前、值在后），拼出来的 key 才能稳定命中缓存
     */
    private static String cacheKey(String name, String... tags) {
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            sb.append('|').append(tags[i]).append('=').append(safe(tags[i + 1]));
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value != null ? value : "unknown";
    }
}
