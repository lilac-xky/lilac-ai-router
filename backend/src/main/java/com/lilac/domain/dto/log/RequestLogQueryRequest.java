package com.lilac.domain.dto.log;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 请求日志查询请求
 */
@Data
public class RequestLogQueryRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 模型标识（模糊查询）
     */
    private String requestModel;

    /**
     * 请求类型
     */
    private String requestType;

    /**
     * 调用来源
     */
    private String source;

    /**
     * 状态：success/failed
     */
    private String status;

    /**
     * 开始日期（yyyy-MM-dd）
     */
    private String startDate;

    /**
     * 结束日期（yyyy-MM-dd）
     */
    private String endDate;

    /**
     * 当前页
     */
    private long pageNum = 1;

    /**
     * 每页大小
     */
    private long pageSize = 10;
}
