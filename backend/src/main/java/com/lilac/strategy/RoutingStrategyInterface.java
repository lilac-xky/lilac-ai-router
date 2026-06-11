package com.lilac.strategy;

import com.lilac.domain.entity.Model;

import java.util.List;

/**
 * 路由策略接口
 */
public interface RoutingStrategyInterface {
    
    /**
     * 选择最优模型（从数据库查询并返回最符合策略的一条数据）
     *
     * @param modelType 模型类型
     * @param requestedModel 用户请求的模型
     * @return 最优模型
     */
    Model selectModel(String modelType, String requestedModel);
    
    /**
     * 获取 Fallback 模型列表（除了主选模型外的备选模型）
     *
     * @param modelType 模型类型
     * @param requestedModel 用户请求的模型
     * @return 回退模型
     */
    List<Model> getFallbackModels(String modelType, String requestedModel);
    
    /**
     * 获取路由策略类型
     *
     * @return 路由策略类型
     */
    String getStrategyType();
}