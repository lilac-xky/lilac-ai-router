package com.lilac.service.impl;

import com.lilac.domain.entity.User;
import com.lilac.mapper.UserMapper;
import com.lilac.service.QuotaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 配额服务实现类
 */
@Service
@Slf4j
public class QuotaServiceImpl implements QuotaService {

    @Resource
    private UserMapper userMapper;
    
    /**
     * 无限制配额标识
     */
    private static final long UNLIMITED_QUOTA = -1L;

    /**
     * 检查用户是否有足够的配额
     *
     * @param userId 用户ID
     */
    @Override
    public boolean checkQuota(Long userId) {
        if (userId == null) {
            return true;
        }
        User user = userMapper.selectOneById(userId);
        if (user == null) {
            return false;
        }
        Long tokenQuota = user.getTokenQuota();
        // 无限制配额直接返回 true
        if (tokenQuota == null || tokenQuota == UNLIMITED_QUOTA) {
            return true;
        }
        Long usedTokens = user.getUsedTokens();
        if (usedTokens == null) {
            usedTokens = 0L;
        }
        // 检查是否还有剩余配额
        return usedTokens < tokenQuota;
    }

    /**
     * 扣减用户的Token使用量
     *
     * @param userId 用户ID
     * @param tokens 扣减的Token数量
     */
    @Override
    public boolean deductTokens(Long userId, int tokens) {
        if (userId == null || tokens <= 0) {
            return true;
        }
        try {
            // 使用原子更新方法扣减Token
            return userMapper.deductTokensAtomically(userId, tokens) > 0;
        } catch (Exception e) {
            log.error("用户 {} 扣减Token失败", userId, e);
            return false;
        }
    }

    /**
     * 获取用户剩余配额
     *
     * @param userId 用户ID
     */
    @Override
    public long getRemainingQuota(Long userId) {
        if (userId == null) {
            return UNLIMITED_QUOTA;
        }
        User user = userMapper.selectOneById(userId);
        if (user == null) {
            return 0;
        }
        Long tokenQuota = user.getTokenQuota();
        // 无限制配额返回 -1
        if (tokenQuota == null || tokenQuota == UNLIMITED_QUOTA) {
            return UNLIMITED_QUOTA;
        }
        Long usedTokens = user.getUsedTokens();
        if (usedTokens == null) {
            usedTokens = 0L;
        }
        return Math.max(0, tokenQuota - usedTokens);
    }
}
