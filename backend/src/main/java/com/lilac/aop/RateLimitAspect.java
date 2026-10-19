package com.lilac.aop;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.lilac.annotation.RateLimit;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.RateLimitService;
import com.lilac.utils.ApiKeyHasher;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private RateLimitService rateLimitService;

    /** 压测用倍率开关，默认 1 表示完全按注解上的 limit。只放大、不缩小 */
    @Value("${lilac.rate-limit.multiplier:1}")
    private int rateLimitMultiplier = 1;

    @Around("@annotation(rateLimit)")
    public Object doRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 获取请求对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attributes.getRequest();

        // 根据限流类型获取限流 key
        String limitKey = getLimitKey(rateLimit, request);
        if (limitKey == null) {
            return joinPoint.proceed();
        }
        // 将 TimeUnit 转换为 Duration
        Duration duration = toDuration(rateLimit.window(), rateLimit.timeUnit());
        int limit = effectiveLimit(rateLimit.limit());
        // 执行限流检查
        boolean allowed = rateLimitService.tryAcquire(limitKey, limit, duration);
        if (!allowed) {
            log.warn("Rate limit exceeded, key: {}, limit: {}/{} {}", limitKey, limit, rateLimit.window(), rateLimit.timeUnit());
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "请求过于频繁，请稍后再试");
        }
        return joinPoint.proceed();
    }

    /**
     * 生效限额。{@code lilac.rate-limit.multiplier} 默认 1，只在压测时调大。
     *
     * <p>注解上的 limit 是编译期常量，没法直接读配置；而压测又必须突破 60/s 才能测到网关
     * 真实承载能力（否则测出来的是限流器的拒绝能力）。所以这里留一个倍率开关：
     * 生产默认 1，压测启动参数传 {@code --lilac.rate-limit.multiplier=100}。
     *
     * <p>倍率只能放大不能缩小 —— 缩小限流会让线上更不安全，这不该由配置随手决定。
     */
    private int effectiveLimit(int annotationLimit) {
        if (rateLimitMultiplier <= 1) {
            return annotationLimit;
        }
        long scaled = (long) annotationLimit * rateLimitMultiplier;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    /**
     * 根据限流类型获取限流 key
     */
    private String getLimitKey(RateLimit rateLimit, HttpServletRequest request) {
        switch (rateLimit.type()) {
            case API_KEY:
                String authorization = request.getHeader("Authorization");
                if (authorization != null && authorization.startsWith("Bearer ")) {
                    // 用哈希做限流键：切面跑在鉴权之前，这时还没有 ApiKey 实体可用，
                    // 而哈希的输入就是明文本身 —— 既能避开明文落 Redis（redis-cli --scan 捞得到可用凭据），
                    // 又不需要为此调整切面顺序
                    return "rate_limit:api_key:" + ApiKeyHasher.sha256Hex(authorization.substring(7));
                }
                return null;
            case IP:
                return "rate_limit:ip:" + JakartaServletUtil.getClientIP(request);
            default:
                return null;
        }
    }

    /**
     * 将 TimeUnit 转换为 Duration
     */
    private Duration toDuration(long amount, TimeUnit timeUnit) {
        return switch (timeUnit) {
            case NANOSECONDS -> Duration.ofNanos(amount);
            case MICROSECONDS -> Duration.ofNanos(amount * 1000);
            case MILLISECONDS -> Duration.ofMillis(amount);
            case SECONDS -> Duration.ofSeconds(amount);
            case MINUTES -> Duration.ofMinutes(amount);
            case HOURS -> Duration.ofHours(amount);
            case DAYS -> Duration.ofDays(amount);
        };
    }
}