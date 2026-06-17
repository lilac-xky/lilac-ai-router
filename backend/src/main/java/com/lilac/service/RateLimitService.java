package com.lilac.service;

import java.time.Duration;

/**
 * 限流服务
 */
public interface RateLimitService {
    
    /**
     * 尝试获取限流许可
     *
     * @param key 限流键
     * @param limit 限流限制
     * @param duration 限流时间
     * @return 是否获取成功
     */
    boolean tryAcquire(String key, int limit, Duration duration);
    
    /**
     * 获取当前可用许可数
     *
     * @param key 限流键
     * @return 剩余可用的限流许可数
     */
    long getAvailablePermits(String key);
    
    /**
     * 检查 API Key 是否被限流
     *
     * @param apiKey API Key
     * @param limit 限流限制
     * @return 是否被限流
     */
    boolean checkApiKeyRateLimit(String apiKey, int limit);

    /**
     * 检查 IP 是否被限流
     *
     * @param ip IP 地址
     * @param limit 限流限制
     * @return 是否被限流
     */
    boolean checkIpRateLimit(String ip, int limit);
}