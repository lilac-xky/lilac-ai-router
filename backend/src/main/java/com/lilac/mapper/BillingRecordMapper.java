package com.lilac.mapper;

import com.lilac.domain.entity.BillingRecord;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账单记录 Mapper
 */
@Mapper
public interface BillingRecordMapper extends BaseMapper<BillingRecord> {
}
