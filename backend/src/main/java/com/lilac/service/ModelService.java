package com.lilac.service;

import com.lilac.domain.entity.Model;
import com.mybatisflex.core.service.IService;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型服务
 */
public interface ModelService extends IService<Model> {

    /**
     * 获取所有启用状态的模型
     *
     * @return 启用的模型列表
     */
    List<Model> getActiveModels();

    /**
     * 更新模型的运行指标
     *
     * @param modelId      模型ID
     * @param healthStatus 健康状态
     * @param avgLatency   平均延迟（毫秒）
     * @param successRate  成功率（百分比）
     * @param score        综合得分（越低越好）
     */
    void updateModelMetrics(Long modelId, String healthStatus, Integer avgLatency, BigDecimal successRate, BigDecimal score);
}
