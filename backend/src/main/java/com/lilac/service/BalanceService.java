package com.lilac.service;

import com.lilac.domain.entity.User;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;

/**
 * 账户余额服务
 */
public interface BalanceService extends IService<User> {
    
    /**
     * 检查余额是否充足。
     */
    boolean checkBalance(Long userId, BigDecimal amount);
    
    /**
     * 扣减余额
     *
     * @return true = 已扣减；false = 无需扣减（入参非法）。余额不足 / 用户不存在时抛 BusinessException
     */
    boolean deductBalance(Long userId, BigDecimal amount, Long requestLogId, String description);
    
    /**
     * 退回余额（结算多退 / 失败退款），账单类型记 {@code refund}。
     */
    boolean refundBalance(Long userId, BigDecimal amount, String description);

    /**
     * 增加余额（仅用于充值到账），账单类型记 {@code recharge}。
     */
    boolean addBalance(Long userId, BigDecimal amount, String description);
    
    /**
     * 获取用户余额
     */
    BigDecimal getUserBalance(Long userId);
}
