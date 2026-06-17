package com.lilac.service.impl;

import com.lilac.service.RateLimitService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 限流服务实现
 */
@Slf4j
@Service
public class RateLimitServiceImpl implements RateLimitService {

    @Resource
    private RedissonClient redissonClient;

    /**
     * API Key 限流前缀
     */
    private static final String RATE_LIMIT_API_KEY_PREFIX = "rate_limit:api_key:";

    /**
     * IP 限流前缀
     */
    private static final String RATE_LIMIT_IP_PREFIX = "rate_limit:ip:";

    /**
     * 尝试获取许可
     *
     * @param key 限流键
     * @param limit 限制数量
     * @param duration 限制时间
     * @return 是否获取成功
     */
    @Override
    public boolean tryAcquire(String key, int limit, Duration duration) {
        // 获取或创建限流器
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        // 设置限流规则（如果已存在则不会重复设置）
        rateLimiter.trySetRate(RateType.OVERALL, limit, duration);
        // 尝试获取许可
        boolean acquired = rateLimiter.tryAcquire(1);
        if (!acquired) {
            log.debug("Rate limit exceeded for key: {}", key);
        }
        return acquired;
    }

    /**
     * 获取剩余可用许可证数量
     *
     * @param key 限流键
     * @return 剩余可用许可证数量
     */
    @Override
    public long getAvailablePermits(String key) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        return rateLimiter.availablePermits();
    }

    /**
     * 检查 API Key 的限流
     *
     * @param apiKey API Key
     * @param limit 限制数量
     * @return 是否允许访问
     */
    @Override
    public boolean checkApiKeyRateLimit(String apiKey, int limit) {
        String key = RATE_LIMIT_API_KEY_PREFIX + apiKey;
        return tryAcquire(key, limit, Duration.ofSeconds(1));
    }

    /**
     * 检查 IP 的限流
     *
     * @param ip IP 地址
     * @param limit 限制数量
     * @return 是否允许访问
     */
    @Override
    public boolean checkIpRateLimit(String ip, int limit) {
        String key = RATE_LIMIT_IP_PREFIX + ip;
        return tryAcquire(key, limit, Duration.ofSeconds(1));
    }
}