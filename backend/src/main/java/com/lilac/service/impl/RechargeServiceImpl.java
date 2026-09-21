package com.lilac.service.impl;

import com.lilac.domain.entity.RechargeRecord;
import com.lilac.mapper.RechargeRecordMapper;
import com.lilac.service.BalanceService;
import com.lilac.service.RechargeService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值服务实现类
 */
@Service
@Slf4j
public class RechargeServiceImpl extends ServiceImpl<RechargeRecordMapper, RechargeRecord> implements RechargeService {

    @Resource
    private BalanceService balanceService;

    @Resource
    private RechargeRecordMapper rechargeRecordMapper;

    /**
     * 创建充值记录
     *
     * @param userId 用户ID
     * @param amount 充值金额
     * @param paymentMethod 支付方式
     * @return 充值记录
     */
    @Override
    public RechargeRecord createRechargeRecord(Long userId, BigDecimal amount, String paymentMethod) {
        RechargeRecord record = RechargeRecord.builder()
                .userId(userId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .status("pending")
                .description("账户充值")
                .createTime(LocalDateTime.now())
                .build();
        save(record);
        log.info("创建充值记录成功：用户 {}，金额 ¥{}", userId, amount);
        return record;
    }

    /**
     * 完成充值，同一记录重复或并发回调只会入账一次
     *
     * @param recordId 充值记录ID
     * @param paymentId 支付ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeRecharge(Long recordId, String paymentId) {
        // 条件更新抢占：1 = 本次抢到，0 = 已被处理过
        if (rechargeRecordMapper.markSuccessIfPending(recordId, paymentId) == 0) {
            log.warn("充值记录 {} 已处理过，跳过重复回调", recordId);
            return;
        }
        // 抢占成功说明记录存在
        RechargeRecord record = getById(recordId);
        // 增加用户余额
        String description = String.format("Stripe充值 ¥%s", record.getAmount());
        balanceService.addBalance(record.getUserId(), record.getAmount(), description);
        log.info("完成充值：记录 {}，用户 {}，金额 ¥{}", recordId, record.getUserId(), record.getAmount());
    }

    /**
     * 获取用户充值记录
     *
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 充值记录列表
     */
    @Override
    public Page<RechargeRecord> listUserRechargeRecords(Long userId, int pageNum, int pageSize) {
        QueryWrapper query = QueryWrapper.create()
                .where("userId = " + userId)
                .orderBy("createTime DESC");
        return page(Page.of(pageNum, pageSize), query);
    }

    /**
     * 获取用户充值总额
     *
     * @param userId 用户ID
     * @return 充值总额
     */
    public BigDecimal getTotalRechargeAmount(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        QueryWrapper query = QueryWrapper.create()
                .where("userId = " + userId)
                .and("status = 'success'");
        List<RechargeRecord> records = list(query);
        BigDecimal total = BigDecimal.ZERO;
        for (RechargeRecord record : records) {
            if (record.getAmount() != null) {
                total = total.add(record.getAmount());
            }
        }
        return total;
    }
}