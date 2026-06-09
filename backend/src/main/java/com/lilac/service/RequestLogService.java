package com.lilac.service;

import com.lilac.domain.entity.RequestLog;

import java.util.List;

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
}