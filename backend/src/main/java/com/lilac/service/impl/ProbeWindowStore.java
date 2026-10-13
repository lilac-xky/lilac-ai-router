package com.lilac.service.impl;

import com.lilac.enums.ProbeFailureKind;
import com.lilac.task.ProbeResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 探针结果的滑动窗口，存 Redis ZSET（score = 探测时刻的毫秒时间戳）。
 */
@Slf4j
@Component
public class ProbeWindowStore {

    private static final String KEY_PREFIX = "lilac:health:probe:window:";
    private static final String DELIMITER = "|";
    /** 成功用这个标记，失败直接用枚举名，便于 redis-cli 里肉眼可读 */
    private static final String SUCCESS_MARK = "OK";

    @Resource
    private RedissonClient redissonClient;

    /** 窗口内最多保留多少条（与原先的 MAX_HISTORY_SIZE 对齐：30s 一次 ≈ 50 分钟） */
    @Value("${lilac.health-check.window.max-size:100}")
    private int maxSize = 100;

    /** 窗口时长（分钟）。按时间过滤，避免服务停一阵后把陈旧结果当成现状 */
    @Value("${lilac.health-check.window.minutes:60}")
    private int windowMinutes = 60;

    // member 必须唯一：同一毫秒内的两次探测若结果相同，ZSET 会当成同一个成员覆盖掉，样本就丢了
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 记录一次探测结果
     */
    void record(Long providerId, ProbeResult result) {
        if (providerId == null || result == null) {
            return;
        }
        try {
            RScoredSortedSet<String> window = windowOf(providerId);
            window.add(System.currentTimeMillis(), encode(result));
            trim(window);
            // 服务停掉后别把键永久留在 Redis 里；运行期间每次写入都会续期
            window.expire(Duration.ofMinutes(windowMinutes * 2L));
        } catch (Exception e) {
            // Redis 抖动不能让整轮健康检查挂掉。这里刻意只告警：
            // 指标缺失最多让状态停在 unknown，而抛异常会让所有 provider 都探不完
            log.warn("写入探针窗口失败（providerId={}），本轮该 provider 指标缺失：{}", providerId, e.getMessage());
        }
    }

    /**
     * 窗口内的成功率；窗口为空返回 null（表示「没有可信样本」，而不是 0%）
     */
    BigDecimal successRate(Long providerId) {
        Collection<String> entries = windowEntries(providerId);
        if (entries.isEmpty()) {
            return null;
        }
        long success = entries.stream().filter(ProbeWindowStore::isSuccessEntry).count();
        return BigDecimal.valueOf(success * 100.0 / entries.size()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 最近一次探测结果；窗口内没有记录返回 null
     */
    ProbeResult latest(Long providerId) {
        Collection<String> entries = windowEntries(providerId);
        if (entries.isEmpty()) {
            return null;
        }
        // valueRange 按 score 升序返回，所以最后一条就是最近一次
        String last = null;
        for (String entry : entries) {
            last = entry;
        }
        return decode(last);
    }

    private RScoredSortedSet<String> windowOf(Long providerId) {
        return redissonClient.getScoredSortedSet(KEY_PREFIX + providerId);
    }

    private Collection<String> windowEntries(Long providerId) {
        if (providerId == null) {
            return List.of();
        }
        try {
            long now = System.currentTimeMillis();
            return windowOf(providerId).valueRange(now - windowMillis(), true, now, true);
        } catch (Exception e) {
            log.warn("读取探针窗口失败（providerId={}），本轮按「无样本」处理：{}", providerId, e.getMessage());
            return List.of();
        }
    }

    private void trim(RScoredSortedSet<String> window) {
        // 时间维度：丢掉窗口外的陈旧样本
        window.removeRangeByScore(0, true, System.currentTimeMillis() - windowMillis(), false);
        // 数量维度：只留最近 maxSize 条，防止窗口时长被调大后无限增长
        int size = window.size();
        if (size > maxSize) {
            window.removeRangeByRank(0, size - maxSize - 1);
        }
    }

    private long windowMillis() {
        return Duration.ofMinutes(windowMinutes).toMillis();
    }

    private String encode(ProbeResult result) {
        String kind = result.isSuccess() ? SUCCESS_MARK : result.failureKind().name();
        return sequence.incrementAndGet() + DELIMITER + kind + DELIMITER
                + result.ttftMillis() + DELIMITER + result.totalMillis();
    }

    private ProbeResult decode(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.split("\\" + DELIMITER, 4);
        if (parts.length < 4) {
            return null;
        }
        int total = parseIntOr(parts[3], 0);
        if (SUCCESS_MARK.equals(parts[1])) {
            return ProbeResult.success(parseIntOr(parts[2], -1), total);
        }
        try {
            return ProbeResult.failure(ProbeFailureKind.valueOf(parts[1]), total);
        } catch (IllegalArgumentException e) {
            // 枚举改过名之后的旧数据：不认识就当 UNKNOWN，不要让它变成「健康」
            return ProbeResult.failure(ProbeFailureKind.UNKNOWN, total);
        }
    }

    private static boolean isSuccessEntry(String entry) {
        // 必须按字段数切满：用 limit=2 时 parts[1] 会是 "OK|120|200" 整段，永远不等于 "OK"，
        // 结果是所有成功都被算成失败（这个 bug 被 successRate 的用例抓到过）
        String[] parts = entry.split("\\" + DELIMITER);
        return parts.length > 1 && SUCCESS_MARK.equals(parts[1]);
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
