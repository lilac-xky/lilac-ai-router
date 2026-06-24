package com.lilac.service.impl;

import com.lilac.domain.entity.BillingRecord;
import com.lilac.mapper.BillingRecordMapper;
import com.lilac.service.BillingRecordService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账单记录服务实现类
 */
@Service
@Slf4j
public class BillingRecordServiceImpl extends ServiceImpl<BillingRecordMapper, BillingRecord> implements BillingRecordService {

    /**
     * 获取用户消费账单分页列表（按创建时间倒序）
     *
     * @param userId   用户ID
     * @param pageNum  页码
     * @param pageSize 页大小
     * @return 账单分页结果
     */
    @Override
    public Page<BillingRecord> listUserBillingRecords(Long userId, int pageNum, int pageSize) {
        QueryWrapper query = QueryWrapper.create()
                .where("userId = " + userId)
                .orderBy("createTime DESC");
        return page(Page.of(pageNum, pageSize), query);
    }

    /**
     * 获取用户累计消费金额（billingType = api_call）
     *
     * @param userId 用户ID
     * @return 累计消费金额
     */
    @Override
    public BigDecimal getUserTotalSpending(Long userId) {
        return sumAmountByType(userId, "api_call");
    }

    /**
     * 获取用户累计充值金额（billingType = recharge）
     *
     * @param userId 用户ID
     * @return 累计充值金额
     */
    @Override
    public BigDecimal getUserTotalRecharge(Long userId) {
        return sumAmountByType(userId, "recharge");
    }

    /**
     * 按账单类型累计用户金额
     *
     * @param userId      用户ID
     * @param billingType 账单类型
     * @return 累计金额
     */
    private BigDecimal sumAmountByType(Long userId, String billingType) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        QueryWrapper query = QueryWrapper.create()
                .where("userId = " + userId)
                .and("billingType = '" + billingType + "'");
        List<BillingRecord> records = list(query);
        BigDecimal total = BigDecimal.ZERO;
        for (BillingRecord record : records) {
            if (record.getAmount() != null) {
                total = total.add(record.getAmount());
            }
        }
        return total;
    }
}
