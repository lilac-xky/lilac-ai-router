package com.lilac.service;

import com.lilac.domain.entity.RechargeRecord;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;

/**
 * 充值服务接口
 */
public interface RechargeService extends IService<RechargeRecord> {
    
    /**
     * 创建充值记录
     *
     * @param userId 用户ID
     * @param amount 充值金额
     * @param paymentMethod 支付方式
     * @return 充值记录
     */
    RechargeRecord createRechargeRecord(Long userId, BigDecimal amount, String paymentMethod);
    
    /**
     * 完成充值
     *
     * @param recordId 充值记录ID
     * @param paymentId 支付ID
     */
    void completeRecharge(Long recordId, String paymentId);
    
    /**
     * 获取用户充值记录
     *
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 充值记录列表
     */
    Page<RechargeRecord> listUserRechargeRecords(Long userId, int pageNum, int pageSize);
}