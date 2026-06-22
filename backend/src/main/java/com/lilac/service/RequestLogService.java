package com.lilac.service;

import com.lilac.domain.dto.log.RequestLogQueryRequest;
import com.lilac.domain.entity.RequestLog;
import com.mybatisflex.core.paginate.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface RequestLogService {

    /**
     * 记录请求日志
     *
     * @param userId 用户ID
     * @param apiKeyId API密钥ID
     * @param modelName 模型名称
     * @param promptTokens 提示词Token数量
     * @param completionTokens 完成词Token数量
     * @param totalTokens 总Token数量
     * @param duration 耗时
     * @param status 状态
     * @param errorMessage 错误信息
     */
    void logRequest(Long userId, Long apiKeyId, String modelName, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                    Integer duration, String status, String errorMessage);

    /**
     * 记录请求日志（带模型ID，自动计算并记录费用）
     *
     * @param userId 用户ID
     * @param apiKeyId API密钥ID
     * @param modelId 模型ID（用于计算费用）
     * @param modelName 模型名称
     * @param promptTokens 提示词Token数量
     * @param completionTokens 完成词Token数量
     * @param totalTokens 总Token数量
     * @param duration 耗时
     * @param status 状态
     * @param errorMessage 错误信息
     */
    void logRequest(Long userId, Long apiKeyId, Long modelId, String modelName, Integer promptTokens, Integer completionTokens,
                    Integer totalTokens, Integer duration, String status, String errorMessage);

    /**
     * 获取用户请求日志
     *
     * @param userId 用户ID
     * @param limit  限制数量
     * @return 用户请求日志
     */
    List<RequestLog> listUserLogs(Long userId, Integer limit);

    /**
     * 获取用户总Token数量
     *
     * @param userId 用户ID
     * @return 用户总Token数量
     */
    Long countUserTokens(Long userId);

    /**
     * 统计用户总请求数
     *
     * @param userId 用户ID
     * @return 用户总请求数
     */
    Long countUserRequests(Long userId);

    /**
     * 统计用户成功请求数
     *
     * @param userId 用户ID
     * @return 用户成功请求数
     */
    Long countUserSuccessRequests(Long userId);

    /**
     * 获取用户每日统计数据
     *
     * @param userId 用户ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 用户每日统计数据
     */
    List<Map<String, Object>> getUserDailyStats(Long userId, LocalDate startDate, LocalDate endDate);

    /**
     * 分页查询日志
     *
     * @param queryRequest 查询参数
     * @return 分页结果
     */
    Page<RequestLog> pageByQuery(RequestLogQueryRequest queryRequest);

    /**
     * 根据ID查询日志
     *
     * @param id 日志ID
     * @return 日志
     */
    RequestLog getById(Long id);
}