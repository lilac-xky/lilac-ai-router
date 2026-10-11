package com.lilac.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;

/**
 * ChatClient 的重试配置
 */
@Slf4j
@Configuration
public class ChatClientRetryConfig {

    /**
     * 可重试的异常：只吃「上游抖一下」。
     * 401/403 走 NonTransientAiException（不在列表里），重试没意义
     */
    private static final List<Class<? extends Throwable>> RETRYABLE =
            List.of(TransientAiException.class, ResourceAccessException.class);

    /**
     * 重试模板：2 次 + 200ms 固定退避。
     * 次数与退避都远小于默认模板（10 次 / 指数退避封顶 180s）
     */
    @Bean
    public RetryTemplate chatRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(Duration.ofMillis(200))
                .retryOn(RETRYABLE)
                // 默认模板自带 listener 打 "Retry error. Retry count:N"，自定义模板要自己补回来，
                // 否则重试完全不留痕（只看得到最终失败，看不出试过几次、为什么试）
                .withListener(new RetryListener() {
                    @Override
                    public <T, E extends Throwable> void onError(
                            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                        int failed = context.getRetryCount();
                        Object max = context.getAttribute(RetryContext.MAX_ATTEMPTS);
                        // 不能一律说「将重试」：onError 对不可重试的异常（如 403）也会触发一次
                        if (RETRYABLE.stream().noneMatch(c -> c.isInstance(throwable))) {
                            log.warn("上游调用失败且属不可重试异常，直接放弃：{}", throwable.toString());
                        } else if (max instanceof Integer limit && failed >= limit) {
                            log.warn("上游调用失败且已达重试上限（{} 次），放弃：{}", limit, throwable.toString());
                        } else {
                            log.warn("上游调用失败将重试，已失败 {} 次，原因：{}", failed, throwable.toString());
                        }
                    }
                })
                .build();
    }
}

