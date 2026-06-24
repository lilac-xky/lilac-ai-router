package com.lilac.service;

import com.lilac.domain.entity.BillingRecord;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;

/**
 * 账单记录服务接口
 */
public interface BillingRecordService extends IService<BillingRecord> {

    /**
     * 获取用户消费账单分页列表（按创建时间倒序）
     *
     * @param userId   用户ID
     * @param pageNum  页码
     * @param pageSize 页大小
     * @return 账单分页结果
     */
    Page<BillingRecord> listUserBillingRecords(Long userId, int pageNum, int pageSize);

    /**
     * 获取用户累计消费金额（billingType = api_call）
     *
     * @param userId 用户ID
     * @return 累计消费金额
     */
    BigDecimal getUserTotalSpending(Long userId);

    /**
     * 获取用户累计充值金额（billingType = recharge）
     *
     * @param userId 用户ID
     * @return 累计充值金额
     */
    BigDecimal getUserTotalRecharge(Long userId);
}
