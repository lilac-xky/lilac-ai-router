package com.lilac.service.impl;

import com.lilac.domain.entity.BillingRecord;
import com.lilac.domain.entity.User;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.UserMapper;
import com.lilac.service.BalanceService;
import com.lilac.service.BillingRecordService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户余额服务实现类
 */
@Service
@Slf4j
public class BalanceServiceImpl extends ServiceImpl<UserMapper, User> implements BalanceService {

    @Resource
    private BillingRecordService billingRecordService;

    /**
     * 检查用户余额是否充足
     *
     * @param userId 用户ID
     * @param amount 需要的余额
     * @return 是否充足
     */
    @Override
    public boolean checkBalance(Long userId, BigDecimal amount) {
        if (userId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        }
        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        return balance.compareTo(amount) >= 0;
    }

    /**
     * 扣减用户余额
     *
     * @param userId      用户ID
     * @param amount      金额
     * @param requestLogId 请求日志ID
     * @param description 描述
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductBalance(Long userId, BigDecimal amount, Long requestLogId, String description) {
        if (userId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        // 获取用户当前余额
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        }
        BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        // 检查余额是否充足
        if (currentBalance.compareTo(amount) < 0) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "余额不足，当前余额：¥" + currentBalance + "，需要：¥" + amount);
        }
        // 扣减余额
        BigDecimal newBalance = currentBalance.subtract(amount);
        user.setBalance(newBalance);
        boolean updated = updateById(user);
        if (!updated) {
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "扣减余额失败");
        }
        // 记录账单
        BillingRecord billingRecord = BillingRecord.builder()
                .userId(userId)
                .requestLogId(requestLogId)
                .amount(amount)
                .balanceBefore(currentBalance)
                .balanceAfter(newBalance)
                .description(description != null ? description : "API调用消费")
                .billingType("api_call")
                .createTime(LocalDateTime.now())
                .build();
        billingRecordService.save(billingRecord);
        log.info("用户 {} 扣减余额成功：¥{} -> ¥{}", userId, currentBalance, newBalance);
        return true;
    }

    /**
     * 充值用户余额
     *
     * @param userId      用户ID
     * @param amount      金额
     * @param description 描述
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addBalance(Long userId, BigDecimal amount, String description) {
        if (userId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        }
        BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.add(amount);
        user.setBalance(newBalance);
        boolean updated = updateById(user);
        if (!updated) {
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "充值失败");
        }
        // 记录账单
        BillingRecord billingRecord = BillingRecord.builder()
                .userId(userId)
                .requestLogId(null)
                .amount(amount)
                .balanceBefore(currentBalance)
                .balanceAfter(newBalance)
                .description(description != null ? description : "账户充值")
                .billingType("recharge")
                .createTime(LocalDateTime.now())
                .build();
        billingRecordService.save(billingRecord);
        log.info("用户 {} 充值成功：¥{} -> ¥{}", userId, currentBalance, newBalance);
        return true;
    }

    /**
     * 获取用户余额
     *
     * @param userId 用户ID
     * @return 余额
     */
    @Override
    public BigDecimal getUserBalance(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        }
        return user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
    }

    /**
     * 更新用户余额
     *
     * @param user 用户
     * @return 是否成功
     */
    @Override
    public boolean updateBalance(User user) {
        if (user == null || user.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        return this.updateById(user);
    }
}