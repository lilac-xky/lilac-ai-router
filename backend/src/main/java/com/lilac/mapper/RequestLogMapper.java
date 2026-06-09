package com.lilac.mapper;

import com.lilac.domain.entity.RequestLog;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 请求日志 Mapper
 */
@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {
}