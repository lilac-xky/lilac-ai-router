package com.lilac.service.impl;

import com.lilac.adapter.StreamChunk;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.metrics.AIMetricsCollector;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 提供者熔断器
 */
@Component
@Slf4j
public class ProviderCircuitBreaker {

    @Resource
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Resource
    private AIMetricsCollector aiMetricsCollector;

    // 熔断器状态是枚举，Prometheus 只能存数值，这里定死映射关系
    private static final Map<CircuitBreaker.State, Integer> CIRCUIT_STATE_CODES = Map.of(
            CircuitBreaker.State.CLOSED, 0,
            CircuitBreaker.State.OPEN, 1,
            CircuitBreaker.State.HALF_OPEN, 2);

    /** 每个 provider 的 Gauge 只注册一次，重复注册会创建同名 meter */
    private final Set<String> gaugesRegistered = ConcurrentHashMap.newKeySet();

    /** 关掉后完全退回原行为：不统计、不拦截，排障时可以二分定位问题是否出在熔断上 */
    @Value("${lilac.circuit-breaker.enabled:true}")
    private boolean enabled;

    /**
     * 非流式调用：由熔断器决定放行还是快速失败
     */
    public ChatResponse invoke(Model model, ModelProvider provider, Supplier<ChatResponse> call) {
        if (!enabled) {
            return call.get();
        }
        CircuitBreaker breaker = breakerFor(provider);
        try {
            // executeSupplier 会在放行时统计成败，打开时直接抛 CallNotPermittedException
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            // 熔断器打开，快速失败
            log.warn("提供者 {} 熔断器已打开（state={}），快速失败，拒绝调用模型 {}", provider.getProviderName(), breaker.getState(), model.getModelKey());
            throw e;
        }
    }

    /**
     * 流式调用：成败要等流结束才知道，所以许可与记账都推迟到订阅时
     */
    public Flux<StreamChunk> invokeStreamChunk(Model model, ModelProvider provider, Supplier<Flux<StreamChunk>> call) {
        if (!enabled) {
            return call.get();
        }
        CircuitBreaker breaker = breakerFor(provider);
        // defer：每次订阅才取许可，且起始时间按本次订阅算（流可能被订阅多次）
        return Flux.defer(() -> {
            if (!breaker.tryAcquirePermission()) {
                log.warn("提供者 {} 熔断器已打开（state={}），拒绝调用模型 {}", provider.getProviderName(), breaker.getState(), model.getModelKey());
                return Flux.error(CallNotPermittedException.createCallNotPermittedException(breaker));
            }
            long start = System.nanoTime();
            Flux<StreamChunk> flux;
            try {
                flux = call.get();
            } catch (RuntimeException e) {
                // 适配器建客户端/建 Prompt 是同步的，失败会直接抛而不是进 Flux，
                // 此时 doOnError 还没挂上；不在这里结算，这次失败对熔断器不可见、许可也退不回来
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
                return Flux.error(e);
            }
            return flux
                    .doOnComplete(() -> breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS))
                    .doOnError(e -> breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e))
                    // 客户端中途断开：既没成功也没失败，释放许可但不计入统计
                    .doOnCancel(breaker::releasePermission);
        });
    }

    /**
     * 根据提供者名称获取熔断器实例
     */
    private CircuitBreaker breakerFor(ModelProvider provider) {
        String name = provider.getProviderName();
        // 按 providerName 隔离：DeepSeek 挂了不该连坐 qwen
        String key = name == null || name.isBlank() ? "unknown" : name;
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(key);
        // 熔断是 W7 花了整周做的能力，但在 Prometheus 里此前完全看不见。
        // 每个 provider 只登记一次，Gauge 持 supplier，采集时才读真实状态
        if (gaugesRegistered.add(key)) {
            aiMetricsCollector.registerCircuitStateGauge(key,
                    () -> CIRCUIT_STATE_CODES.getOrDefault(breaker.getState(), 0));
        }
        return breaker;
    }
}
