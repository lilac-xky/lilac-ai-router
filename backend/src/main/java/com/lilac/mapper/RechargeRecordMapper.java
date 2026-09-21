package com.lilac.mapper;

import com.lilac.domain.entity.RechargeRecord;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 充值记录 Mapper
 */
@Mapper
public interface RechargeRecordMapper extends BaseMapper<RechargeRecord> {

    /**
     * 抢占式置为成功，用作幂等门
     *
     * @param recordId  充值记录ID
     * @param paymentId 支付ID
     * @return 影响行数：1 = 本次抢到，0 = 已处理过或记录不存在
     */
    @Update("UPDATE recharge_record SET status = 'success', paymentId = #{paymentId} "
            + "WHERE id = #{recordId} AND status = 'pending'")
    int markSuccessIfPending(@Param("recordId") Long recordId, @Param("paymentId") String paymentId);
}