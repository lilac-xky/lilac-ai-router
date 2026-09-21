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
     * 预留额度：按预估值原子占用配额。与 {@link #deductTokens} 同一条 SQL，差别只在语义
     */
    @Override
    public boolean reserveTokens(Long userId, int estimatedTokens) {
        return deductTokens(userId, estimatedTokens);
    }

    /**
     * 结算：实际低于预留则退回，高于则补扣。
     */
    @Override
    public void settleTokens(Long userId, int reservedTokens, int actualTokens) {
        if (userId == null) {
            return;
        }
        int diff = actualTokens - reservedTokens;
        if (diff == 0) {
            return;
        }
        if (diff < 0) {
            refundTokens(userId, -diff);
            return;
        }
        if (!deductTokens(userId, diff)) {
            log.warn("用户 {} 结算补扣失败，产生欠费：预留 {} Token，实际 {} Token，缺口 {} Token", userId, reservedTokens, actualTokens, diff);
        }
    }

    /**
     * 退回预留的 Token 配额。失败只告警，不把「调用失败」升级成「接口 500」
     */
    @Override
    public void refundTokens(Long userId, int tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        try {
            int affected = userMapper.refundTokensAtomically(userId, tokens);
            if (affected == 0) {
                log.warn("用户 {} 退回 {} Token 失败：用户不存在或已删除", userId, tokens);
            }
        } catch (Exception e) {
            log.error("用户 {} 退回 {} Token 异常，需人工核对配额账目", userId, tokens, e);
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
