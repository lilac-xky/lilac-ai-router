package com.lilac.service;

import com.lilac.domain.entity.ModelProvider;

/**
 * 健康检查服务
 */
public interface HealthCheckService {

    /**
     * 检查所有启用的提供者，并从请求日志同步模型指标
     */
    void checkAllProviders();

    /**
     * 检查单个提供者的健康状态
     *
     * @param provider 提供者
     * @return 是否健康
     */
    boolean checkProviderHealth(ModelProvider provider);

    /**
     * 从请求日志同步模型指标
     */
    void syncModelMetricsFromRequestLog();
}
