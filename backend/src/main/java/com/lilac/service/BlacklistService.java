package com.lilac.service;

import java.util.Set;

/**
 * 黑名单服务
 */
public interface BlacklistService {

    /**
     * 检查 IP 是否被加入黑名单
     *
     * @param ip IP 地址
     * @return 是否被加入黑名单
     */
    boolean isBlocked(String ip);

    /**
     * 将 IP 加入黑名单
     *
     * @param ip IP 地址
     * @param reason 黑名单原因
     */
    void addToBlacklist(String ip, String reason);

    /**
     * 将 IP 从黑名单中移除
     *
     * @param ip IP 地址
     */
    void removeFromBlacklist(String ip);

    /**
     * 获取所有黑名单 IP 地址
     *
     * @return 黑名单 IP 地址列表
     */
    Set<String> getAllBlacklist();

    /**
     * 清空黑名单
     */
    void clearBlacklist();

    /**
     * 获取黑名单数量
     *
     * @return 黑名单数量
     */
    long getBlacklistCount();
}
