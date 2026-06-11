package com.lilac.service;

import com.lilac.domain.entity.ModelProvider;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;

/**
 * 模型提供者服务
 */
public interface ModelProviderService extends IService<ModelProvider> {

    /**
     * 更新提供者的健康状态信息
     *
     * @param providerId   提供者ID
     * @param healthStatus 健康状态
     * @param avgLatency   平均延迟（毫秒）
     * @param successRate  成功率（百分比）
     */
    void updateHealthStatus(Long providerId, String healthStatus, Integer avgLatency, BigDecimal successRate);
}
