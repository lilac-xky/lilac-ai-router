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

/**
 * ChatClient 的重试配置
 */
@Slf4j
@Configuration
public class ChatClientRetryConfig {

    /**
     * 重试模板：2 次 + 200ms 固定退避。
     * 只重试「上游抖一下」的两类异常，次数与退避都远小于默认模板（10 次 / 指数退避封顶 180s）
     */
    @Bean
    public RetryTemplate chatRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(Duration.ofMillis(200))
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                // 添加日志监听器
                .withListener(new RetryListener() {
                    @Override
                    public <T, E extends Throwable> void onError(
                            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                        int failed = context.getRetryCount();
                        Object max = context.getAttribute(RetryContext.MAX_ATTEMPTS);
                        // 最后一次失败时 getRetryCount() 已等于总次数，要能区分「还会重试」和「这就放弃了」
                        if (max instanceof Integer limit && failed >= limit) {
                            log.warn("上游调用失败且已达重试上限（{} 次），放弃：{}", limit, throwable.toString());
                        } else {
                            log.warn("上游调用失败将重试，已失败 {} 次，原因：{}", failed, throwable.toString());
                        }
                    }
                })
                .build();
    }
}

