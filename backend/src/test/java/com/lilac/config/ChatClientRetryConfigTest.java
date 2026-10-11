package com.lilac.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试模板必须是有界的：上游不可达时，单请求实测 22 分钟（10 次 × 指数退避封顶 180s）。
 * 纯单测，不起 Spring 上下文。
 */
class ChatClientRetryConfigTest {

    private final RetryTemplate template = new ChatClientRetryConfig().chatRetryTemplate();

    @Test
    @DisplayName("连接层异常最多 2 次：默认模板是 10 次，退回去就是 22 分钟")
    void connectionFailure_retriesOnlyTwice() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template.execute(ctx -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("connect timed out");
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(calls.get())
                .as("默认模板 maxAttempts=10；一旦变回 10 次，单请求耗时会回到分钟级")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("上游 5xx/429（TransientAiException）同样最多 2 次")
    void transientAiException_retriesOnlyTwice() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template.execute(ctx -> {
            calls.incrementAndGet();
            throw new TransientAiException("429 Too Many Requests");
        })).isInstanceOf(TransientAiException.class);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("非瞬时异常不重试：401/403 走 NonTransientAiException，重试只是白等")
    void nonTransientFailure_isNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template.execute(ctx -> {
            calls.incrementAndGet();
            throw new IllegalStateException("401 Unauthorized");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).as("不在 retryOn 列表里的异常必须一次就放弃").isEqualTo(1);
    }

    @Test
    @DisplayName("NonTransientAiException（403 配额用尽）一次就放弃，不能重试")
    void nonTransientAiException_isNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template.execute(ctx -> {
            calls.incrementAndGet();
            throw new NonTransientAiException("403 AllocationQuota.FreeTierOnly");
        })).isInstanceOf(NonTransientAiException.class);

        assertThat(calls.get())
                .as("403 重试改变不了账户配额，只会让用户多等一次往返")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("退避是 200ms 固定值，不是默认的 2s 起指数递增")
    void backoffIsShort() {
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> template.execute(ctx -> {
            throw new ResourceAccessException("boom");
        })).isInstanceOf(ResourceAccessException.class);

        long elapsed = System.currentTimeMillis() - start;
        // 默认模板首次退避就是 2000ms，这里刻意卡在 1500ms 以下以示区分
        assertThat(elapsed).as("退避被改回指数递增时，耗时会立刻超过 2s").isLessThan(1500);
    }

    @Test
    @DisplayName("第 2 次成功时正常返回值，重试不吞掉结果")
    void succeedsOnSecondAttempt_returnsValue() {
        AtomicInteger calls = new AtomicInteger();

        String result = template.execute(ctx -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResourceAccessException("transient blip");
            }
            return "ok-after-retry";
        });

        assertThat(calls.get()).as("一次抖动应被重试吸收").isEqualTo(2);
        assertThat(result).isEqualTo("ok-after-retry");
    }
}
