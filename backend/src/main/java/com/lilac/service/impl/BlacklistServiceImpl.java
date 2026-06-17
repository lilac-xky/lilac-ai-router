package com.lilac.service.impl;

import com.lilac.service.BlacklistService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 黑名单服务实现
 */
@Slf4j
@Service
public class BlacklistServiceImpl implements BlacklistService {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 黑名单 Redis Key
     */
    private static final String BLACKLIST_KEY = "blacklist:ip";

    /**
     * 判断 IP 是否被加入黑名单
     *
     * @param ip IP 地址
     * @return 是否被加入黑名单
     */
    @Override
    public boolean isBlocked(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        return blacklist.contains(ip);
    }

    /**
     * 将 IP 加入黑名单
     *
     * @param ip IP 地址
     * @param reason 添加黑名单的原因
     */
    @Override
    public void addToBlacklist(String ip, String reason) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        blacklist.add(ip);
        log.info("IP added to blacklist: {}, reason: {}", ip, reason);
    }

    /**
     * 将 IP 从黑名单中移除
     *
     * @param ip IP 地址
     */
    @Override
    public void removeFromBlacklist(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        blacklist.remove(ip);
        log.info("IP removed from blacklist: {}", ip);
    }

    /**
     * 获取所有黑名单 IP
     *
     * @return 黑名单 IP 列表
     */
    @Override
    public Set<String> getAllBlacklist() {
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        return new HashSet<>(blacklist.readAll());
    }

    /**
     * 清空黑名单
     */
    @Override
    public void clearBlacklist() {
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        blacklist.delete();
        log.info("Blacklist cleared");
    }

    /**
     * 获取黑名单数量
     *
     * @return 黑名单数量
     */
    @Override
    public long getBlacklistCount() {
        RSet<String> blacklist = redissonClient.getSet(BLACKLIST_KEY);
        return blacklist.size();
    }
}