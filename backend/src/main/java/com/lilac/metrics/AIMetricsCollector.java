package com.lilac.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 指标收集器
 */
@Slf4j
@Component
public class AIMetricsCollector {

    private final MeterRegistry meterRegistry;

    private Counter totalRequestsCounter;
    private Counter totalTokensCounter;
    private Counter totalErrorsCounter;

    // 按模型和用户维度的计数器缓存
    private final ConcurrentHashMap<String, Counter> modelRequestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> modelTokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> userRequestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> quotaRejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> settlementDeficitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> modelTimers = new ConcurrentHashMap<>();

    public AIMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // 初始化全局计数器
        totalRequestsCounter = Counter.builder("ai.requests.total")
                .description("AI 请求总数")
                .register(meterRegistry);

        totalTokensCounter = Counter.builder("ai.tokens.total")
                .description("Token 消耗总量")
                .register(meterRegistry);

        totalErrorsCounter = Counter.builder("ai.errors.total")
                .description("错误总数")
                .register(meterRegistry);

        log.info("AI 指标收集器初始化完成");
    }

    /**
     * 记录请求
     */
    public void recordRequest(String modelKey, Long userId, String apiKeyId) {
        // 全局请求计数
        totalRequestsCounter.increment();

        // 按模型计数
        if (modelKey != null) {
            getOrCreateModelRequestCounter(modelKey).increment();
        }

        // 按用户计数
        if (userId != null) {
            getOrCreateUserRequestCounter(userId.toString()).increment();
        }
    }

    /**
     * 记录 Token 消耗
     */
    public void recordTokens(String modelKey, int tokens) {
        // 全局 Token 计数
        totalTokensCounter.increment(tokens);

        // 按模型计数
        if (modelKey != null) {
            getOrCreateModelTokenCounter(modelKey).increment(tokens);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(String modelKey, String errorType) {
        totalErrorsCounter.increment();

        Counter.builder("ai.errors")
                .tag("model", modelKey != null ? modelKey : "unknown")
                .tag("type", errorType != null ? errorType : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录响应时间
     */
    public void recordResponseTime(String modelKey, long durationMillis) {
        if (modelKey != null) {
            Timer timer = modelTimers.computeIfAbsent(modelKey, k ->
                    Timer.builder("ai.response.time")
                            .description("AI 响应时间")
                            .tag("model", k)
                            .register(meterRegistry)
            );
            timer.record(durationMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 获取或创建模型请求计数器
     */
    private Counter getOrCreateModelRequestCounter(String modelKey) {
        return modelRequestCounters.computeIfAbsent(modelKey, k ->
                Counter.builder("ai.requests.by_model")
                        .description("按模型统计的请求数")
                        .tag("model", k)
                        .register(meterRegistry)
        );
    }

    /**
     * 获取或创建模型 Token 计数器
     */
    private Counter getOrCreateModelTokenCounter(String modelKey) {
        return modelTokenCounters.computeIfAbsent(modelKey, k ->
                Counter.builder("ai.tokens.by_model")
                        .description("按模型统计的 Token 消耗")
                        .tag("model", k)
                        .register(meterRegistry)
        );
    }

    /**
     * 获取或创建用户请求计数器
     */
    private Counter getOrCreateUserRequestCounter(String userId) {
        return userRequestCounters.computeIfAbsent(userId, k ->
                Counter.builder("ai.requests.by_user")
                        .description("按用户统计的请求数")
                        .tag("user_id", k)
                        .register(meterRegistry)
        );
    }

    /**
     * 记录「配额预留被拒」的次数（额度不够，请求未打到上游）。
     * 只按模型打标签：user_id 是无界高基数维度，进 tag 会让时间序列爆炸，定位用户看日志
     */
    public void recordQuotaRejected(String modelKey, Long userId) {
        getOrCreateQuotaRejectedCounter(modelKey).increment();
        log.warn("配额预留被拒：模型 {}, 用户 {}", modelKey, userId);
    }

    /**
     * 记录「结算差额追缴失败」的次数 —— 欠费规模的直接度量。
     * 与「配额被拒」区分：前者是拦住了，这里是没拦住、钱漏了
     */
    public void recordSettlementDeficit(String modelKey, Long userId, BigDecimal deficit) {
        getOrCreateSettlementDeficitCounter(modelKey).increment();
        log.warn("结算差额追缴失败（欠费）：模型 {}, 用户 {}, 缺口 ¥{}", modelKey, userId, deficit);
    }

    private Counter getOrCreateQuotaRejectedCounter(String modelKey) {
        return quotaRejectedCounters.computeIfAbsent(modelKey != null ? modelKey : "unknown", k ->
                Counter.builder("ai.quota.rejected")
                        .description("配额预留被拒的次数")
                        .tag("model", k)
                        .register(meterRegistry)
        );
    }

    private Counter getOrCreateSettlementDeficitCounter(String modelKey) {
        return settlementDeficitCounters.computeIfAbsent(modelKey != null ? modelKey : "unknown", k ->
                Counter.builder("ai.settlement.deficit")
                        .description("结算差额追缴失败的次数")
                        .tag("model", k)
                        .register(meterRegistry)
        );
    }
}