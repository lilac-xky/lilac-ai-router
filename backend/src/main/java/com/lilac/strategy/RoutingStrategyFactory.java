package com.lilac.strategy;

import com.lilac.enums.RoutingStrategyTypeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由策略工厂
 * 启动时收集所有路由策略，按策略类型分发
 */
@Slf4j
@Component
public class RoutingStrategyFactory {

    @Resource
    private List<RoutingStrategyInterface> strategies;

    /**
     * 策略类型 -> 策略实例
     */
    private final Map<String, RoutingStrategyInterface> strategyMap = new HashMap<>();

    /**
     * 初始化策略映射
     */
    @PostConstruct
    public void init() {
        for (RoutingStrategyInterface strategy : strategies) {
            strategyMap.put(strategy.getStrategyType(), strategy);
            log.info("注册路由策略: {} -> {}", strategy.getStrategyType(), strategy.getClass().getSimpleName());
        }
    }

    /**
     * 根据策略类型获取对应的路由策略
     * 未匹配到时回退到自动路由策略
     *
     * @param strategyType 策略类型
     * @return 路由策略
     */
    public RoutingStrategyInterface getStrategy(String strategyType) {
        RoutingStrategyInterface strategy = strategyMap.get(strategyType);
        if (strategy == null) {
            log.warn("未找到路由策略: {}，回退到自动路由策略", strategyType);
            return strategyMap.get(RoutingStrategyTypeEnum.AUTO.getValue());
        }
        return strategy;
    }
}
