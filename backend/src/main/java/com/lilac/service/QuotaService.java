package com.lilac.service;

/**
 * 配额服务接口
 */
public interface QuotaService {
    /**
     * 检查用户是否有足够的配额
     *
     * @param userId 用户ID
     */
    boolean checkQuota(Long userId);
    
    /**
     * 扣减用户的Token使用量
     *
     * @param userId 用户ID
     * @param tokens 使用的Token数量
     */
    boolean deductTokens(Long userId, int tokens);
    
    /**
     * 获取用户剩余配额
     *
     * @param userId 用户ID
     */
    long getRemainingQuota(Long userId);
}