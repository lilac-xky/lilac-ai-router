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

    @Resource
    private UserMapper userMapper;

    /**
     * 检查用户余额是否充足
     *
     * @param userId 用户ID
     * @param amount 需要的余额
     * @return 是否充足
     */
    @Override
    public boolean checkBalance(Long userId, BigDecimal amount) {
        // 匿名 / BYOK 不消耗平台余额
        if (userId == null) {
            return true;
        }
        // 无需付费（免费模型）→ 通过。返回 false 会让调用方的 !checkBalance(...) 抛出「余额不足 ¥0」
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
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
        // 判断与累加在同一条 SQL，影响行数为 0 即余额不足
        if (userMapper.deductBalanceAtomically(userId, amount) == 0) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "余额不足，本次需要：¥" + amount);
        }
        // after 取真实写回值，before 由 after 反推，保证账单前后自洽
        BigDecimal newBalance = getUserBalance(userId);
        BigDecimal currentBalance = newBalance.add(amount);
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
     * 充值用户余额（账单类型 {@code recharge}）
     *
     * @param userId      用户ID
     * @param amount      金额
     * @param description 描述
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addBalance(Long userId, BigDecimal amount, String description) {
        return increaseBalance(userId, amount, description, "recharge");
    }

    /**
     * 退还余额（账单类型 {@code refund}）—— 与充值只差账单类型，
     * 对账时要能区分「用户充的钱」和「没提供服务而还回去的钱」
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundBalance(Long userId, BigDecimal amount, String description) {
        return increaseBalance(userId, amount, description, "refund");
    }

    /**
     * 余额增加的公共实现：原子累加 + 记一条账单。
     * 私有方法，跑在调用方的事务里，因此「加钱 + 记账」同生共死。
     */
    private boolean increaseBalance(Long userId, BigDecimal amount, String description, String billingType) {
        if (userId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        // 原子累加，影响行数为 0 即用户不存在
        if (userMapper.addBalanceAtomically(userId, amount) == 0) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        }
        // after 取真实写回值，before 由 after 反推，保证账单前后自洽
        BigDecimal newBalance = getUserBalance(userId);
        BigDecimal currentBalance = newBalance.subtract(amount);
        // 记录账单
        BillingRecord billingRecord = BillingRecord.builder()
                .userId(userId)
                .requestLogId(null)
                .amount(amount)
                .balanceBefore(currentBalance)
                .balanceAfter(newBalance)
                .description(description != null ? description
                        : ("refund".equals(billingType) ? "余额退还" : "账户充值"))
                .billingType(billingType)
                .createTime(LocalDateTime.now())
                .build();
        billingRecordService.save(billingRecord);
        if ("refund".equals(billingType)) {
            log.info("用户 {} 退还余额成功：¥{} -> ¥{}", userId, currentBalance, newBalance);
        } else {
            log.info("用户 {} 充值成功：¥{} -> ¥{}", userId, currentBalance, newBalance);
        }
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
}