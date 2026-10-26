package com.lilac.service.impl;

import com.lilac.service.RateLimitService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    // 键空闲 TTL。Redisson 的限流器键默认 60 秒过期，窗口长于默认 TTL 时按窗口放大：Redisson 要求 keepAliveTime ≥ rateInterval，违反直接抛异常
    private static final Duration KEY_IDLE_TTL = Duration.ofSeconds(60);

    // 本地已设规则集合的上限：轮换 key 时它和 Redis 里的键同步膨胀，同样必须封顶
    private static final int MAX_TRACKED_KEYS = 10_000;

    // 已设过规则的 key → 本地信任截止时刻（毫秒）。必须比 Redis 的键先失效，否则键过期后本地仍认为规则在，
    // tryAcquire 会撞 "RateLimiter is not initialized"；信任期取空闲 TTL 一半，多出来的 trySetRate 是幂等的
    private final Map<String, Long> configuredRateKeys = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

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
        // 规则只在本地信任过期时设置一次。原先每个请求都调 trySetRate，那是「先读配置再按需写」的 Redis 往返，
        // 等于每请求固定多一跳；压测实测 20 并发下单请求 166ms 而串行只要 36ms
        String rateKey = key + "|" + limit + "|" + duration.toMillis();
        Duration idleTtl = idleTtlFor(duration);
        long now = System.currentTimeMillis();
        Long trustedUntil = configuredRateKeys.get(rateKey);
        if (trustedUntil == null || trustedUntil <= now) {
            ensureRate(rateLimiter, limit, duration, idleTtl);
            configuredRateKeys.put(rateKey, now + idleTtl.toMillis() / 2);
        }
        // 尝试获取许可
        boolean acquired = rateLimiter.tryAcquire(1);
        if (!acquired) {
            log.debug("Rate limit exceeded for key: {}", key);
        }
        return acquired;
    }

    // 确保限流器规则设置
    private void ensureRate(RRateLimiter rateLimiter, int limit, Duration window, Duration idleTtl) {
        if (rateLimiter.trySetRate(RateType.OVERALL, limit, window, idleTtl)) {
            return;
        }
        // 键已存在。修复前建的键没有 keepAliveTime，续期分支永远进不去，光补 expire 会在 60 秒后误杀活跃键，
        // 只能整条重写；用「TTL 缺失」当判据，免得健康键被重写而白丢窗口计数
        if (rateLimiter.remainTimeToLive() < 0) {
            rateLimiter.setRate(RateType.OVERALL, limit, window, idleTtl);
        }
    }

    // 窗口长于默认 TTL 时按窗口放大：Redisson 要求 keepAliveTime ≥ rateInterval，违反直接抛异常
    private Duration idleTtlFor(Duration window) {
        return window.compareTo(KEY_IDLE_TTL) < 0 ? KEY_IDLE_TTL : window.multipliedBy(2);
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
