package com.lilac.service;

import com.lilac.domain.entity.Model;

import java.util.List;

/**
 * 路由服务
 * 根据策略类型选择具体的路由策略，完成模型选择与回退
 */
public interface RoutingService {

    /**
     * 根据策略选择模型
     *
     * @param strategyType   路由策略类型
     * @param modelType      模型类型（如 chat）
     * @param requestedModel 用户请求的模型
     * @return 选中的模型，无可用模型时返回 null
     */
    Model selectModel(String strategyType, String modelType, String requestedModel);

    /**
     * 获取 Fallback 模型列表
     *
     * @param strategyType   路由策略类型
     * @param modelType      模型类型（如 chat）
     * @param requestedModel 用户请求的模型
     * @return 回退模型列表
     */
    List<Model> getFallbackModels(String strategyType, String modelType, String requestedModel);
}
