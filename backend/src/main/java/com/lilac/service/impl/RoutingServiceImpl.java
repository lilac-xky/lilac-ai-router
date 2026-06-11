package com.lilac.service.impl;

import com.lilac.domain.entity.Model;
import com.lilac.service.RoutingService;
import com.lilac.strategy.RoutingStrategyFactory;
import com.lilac.strategy.RoutingStrategyInterface;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 路由服务实现
 * 将策略类型解析为具体策略后委托执行
 */
@Slf4j
@Service
public class RoutingServiceImpl implements RoutingService {

    @Resource
    private RoutingStrategyFactory routingStrategyFactory;

    /**
     * 根据策略类型选择模型
     *
     * @param strategyType 策略类型
     * @param modelType 模型类型
     * @param requestedModel 请求的模型
     * @return 模型
     */
    @Override
    public Model selectModel(String strategyType, String modelType, String requestedModel) {
        RoutingStrategyInterface strategy = routingStrategyFactory.getStrategy(strategyType);
        Model model = strategy.selectModel(modelType, requestedModel);
        if (model != null) {
            log.info("路由策略 {} 选中模型: {}", strategyType, model.getModelKey());
        }
        return model;
    }

    /**
     * 获取回退模型
     *
     * @param strategyType 策略类型
     * @param modelType 模型类型
     * @param requestedModel 请求的模型
     * @return 回退模型
     */
    @Override
    public List<Model> getFallbackModels(String strategyType, String modelType, String requestedModel) {
        RoutingStrategyInterface strategy = routingStrategyFactory.getStrategy(strategyType);
        return strategy.getFallbackModels(modelType, requestedModel);
    }
}
