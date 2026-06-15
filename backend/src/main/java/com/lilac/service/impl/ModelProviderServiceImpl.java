package com.lilac.service.impl;

import com.lilac.domain.entity.ModelProvider;
import com.lilac.mapper.ModelProviderMapper;
import com.lilac.service.ModelProviderService;
import com.mybatisflex.core.util.UpdateEntity;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 模型提供者服务实现
 */
@Service
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, ModelProvider> implements ModelProviderService {

    /**
     * 更新模型提供者的健康状态
     *
     * @param providerId      模型提供者的ID
     * @param healthStatus    健康状态
     * @param avgLatency      平均延迟
     * @param successRate     成功率
     */
    @Override
    public void updateHealthStatus(Long providerId, String healthStatus, Integer avgLatency, BigDecimal successRate) {
        // 使用 UpdateEntity 只更新指定字段，避免覆盖其它列
        ModelProvider updateProvider = UpdateEntity.of(ModelProvider.class, providerId);
        updateProvider.setHealthStatus(healthStatus);
        updateProvider.setAvgLatency(avgLatency);
        updateProvider.setSuccessRate(successRate);
        updateById(updateProvider);
    }
}
