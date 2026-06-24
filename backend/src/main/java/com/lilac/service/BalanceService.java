package com.lilac.service;

import com.lilac.domain.entity.User;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;

/**
 * 账户余额服务
 */
public interface BalanceService extends IService<User> {
    
    /**
     * 检查余额是否充足
     *
     * @param userId 用户ID
     * @param amount  金额
     */
    boolean checkBalance(Long userId, BigDecimal amount);
    
    /**
     * 扣减余额
     *
     * @param userId 用户ID
     * @param amount  金额
     * @param requestLogId 请求日志ID
     * @param description 描述
     */
    boolean deductBalance(Long userId, BigDecimal amount, Long requestLogId, String description);
    
    /**
     * 增加余额
     *
     * @param userId 用户ID
     * @param amount  金额
     * @param description 描述
     */
    boolean addBalance(Long userId, BigDecimal amount, String description);
    
    /**
     * 获取用户余额
     *
     * @param userId 用户ID
     */
    BigDecimal getUserBalance(Long userId);
    
    /**
     * 更新用户余额
     *
     * @param user 用户
     */
    boolean updateBalance(User user);
}