package com.lilac.config;

import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Async} 线程池必须是有界的。
 *
 * <p>不需要数据库，但走集成测试基类只为拿到一个缓存好的 Spring 上下文（不额外增加启动成本）。
 */
class AsyncConfigTest extends IntegrationTestSupport {

    @Resource(name = "applicationTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    @Test
    @DisplayName("@Async 线程池必须是有界的，且拒绝策略不能让任务悄悄消失")
    void asyncExecutorIsBounded() {
        assertThat(executor.getCorePoolSize()).isPositive();
        // Spring Boot 自动配置的默认值是 Integer.MAX_VALUE，这里一旦退回去就说明自定义 Bean 没了
        assertThat(executor.getMaxPoolSize()).isLessThan(Integer.MAX_VALUE);
        assertThat(executor.getQueueCapacity()).isLessThan(Integer.MAX_VALUE);

        ThreadPoolExecutor nativeExecutor = executor.getThreadPoolExecutor();
        assertThat(nativeExecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(nativeExecutor.getMaximumPoolSize()).isEqualTo(executor.getMaxPoolSize());
    }
}
