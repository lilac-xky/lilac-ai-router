package com.lilac.config;

import ai.z.openapi.service.model.ZAiHttpException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.openai.api.common.OpenAiApiClientErrorException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 熔断器配置：按 provider 隔离，一个厂商挂掉不影响其它厂商
 */
@Configuration
public class CircuitBreakerConfigurer {

    // 默认值写在字段上而不是只写在 @Value 的 ":" 后面：直接 new 出来（如单测/独立验证程序）时
    // 字段注入不会发生，若默认值只在注解里，这几个值会是 0，而 0 在这里等于「永不熔断」的静默失效
    @Value("${lilac.circuit-breaker.sliding-window-size:10}")
    private int slidingWindowSize = 10;

    // 实测教训：定 5 时，单 provider 一轮故障注入只有 3-5 次调用，样本永远凑不满，熔断器全程沉默。
    // 定 3 才能在本机流量下触发；代价是连续 3 次抖动就会封 60s。
    @Value("${lilac.circuit-breaker.minimum-number-of-calls:3}")
    private int minimumNumberOfCalls = 3;

    @Value("${lilac.circuit-breaker.wait-duration-seconds:60}")
    private int waitDurationSeconds = 60;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                // 与健康检查的 degraded 线（50%）保持一致，两个口径不要各说各话
                .failureRateThreshold(50)
                .slowCallRateThreshold(50)
                // 适配器已设 connectTimeout=2s / responseTimeout=60s，正常调用远快于 30s
                .slowCallDurationThreshold(Duration.ofSeconds(30))
                // 默认 60s 后进入半开，放一个探针请求试探；成功即闭合，失败再等一轮
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationSeconds))
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(CircuitBreakerConfigurer::isClientError)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * 只有 4xx 才算作客户端错误，应该被熔断器忽略
     */
    private static boolean isClientError(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            // 4xx / 参数错误：上游明确告诉我们请求有问题
            if (t instanceof NonTransientAiException) {
                return true;
            }
            // OpenAI 的 4xx 是独立类型，并不继承 NonTransientAiException，必须单独判
            if (t instanceof OpenAiApiClientErrorException) {
                return true;
            }
            // 智谱 SDK 不走 Spring AI，4xx 只能看 statusCode
            if (t instanceof ZAiHttpException zai) {
                return zai.statusCode >= 400 && zai.statusCode < 500;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        // 其余（连接超时、5xx、限流、未知异常）一律计入失败：宁可多熔断，也不要静默放过真故障
        return false;
    }
}
