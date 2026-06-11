package com.lilac.strategy.impl;

import cn.hutool.core.util.StrUtil;
import com.lilac.domain.entity.Model;
import com.lilac.enums.HealthStatusEnum;
import com.lilac.enums.ModelStatusEnum;
import com.lilac.enums.RoutingStrategyTypeEnum;
import com.lilac.service.ModelService;
import com.lilac.strategy.RoutingStrategyInterface;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询路由策略
 * 在可用模型之间按顺序轮流分配请求，实现负载均衡
 */
@Component
public class RoundRobinRoutingStrategy implements RoutingStrategyInterface {

    @Resource
    private ModelService modelService;

    /**
     * 按 modelType 维护各自的轮询计数器
     */
    private final Map<String, AtomicInteger> counterMap = new ConcurrentHashMap<>();

    @Override
    public Model selectModel(String modelType, String requestedModel) {
        List<Model> models = listAvailableModels(modelType);
        if (models.isEmpty()) {
            return null;
        }
        // 取出当前轮询位置对应的模型
        int index = nextIndex(modelType, models.size());
        return models.get(index);
    }

    @Override
    public List<Model> getFallbackModels(String modelType, String requestedModel) {
        List<Model> models = listAvailableModels(modelType);
        if (models.size() <= 1) {
            return new ArrayList<>();
        }
        // 当前已选位置的下一个位置开始，按轮询顺序排列剩余模型作为回退列表
        int current = currentIndex(modelType, models.size());
        List<Model> fallbacks = new ArrayList<>(models.size() - 1);
        for (int i = 1; i < models.size(); i++) {
            fallbacks.add(models.get((current + i) % models.size()));
        }
        return fallbacks;
    }

    @Override
    public String getStrategyType() {
        return RoutingStrategyTypeEnum.ROUND_ROBIN.getValue();
    }

    /**
     * 查询可用模型列表，按优先级降序、id 升序保证顺序稳定
     */
    private List<Model> listAvailableModels(String modelType) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", ModelStatusEnum.ACTIVE.getValue())
                .in("healthStatus", HealthStatusEnum.HEALTHY.getValue(), HealthStatusEnum.DEGRADED.getValue(), HealthStatusEnum.UNKNOWN.getValue());

        if (StrUtil.isNotBlank(modelType)) {
            queryWrapper.eq("modelType", modelType);
        }
        // 稳定排序，保证轮询顺序在多次请求间一致
        queryWrapper.orderBy("priority", false);
        queryWrapper.orderBy("id", true);

        return modelService.list(queryWrapper);
    }

    /**
     * 获取并递增轮询计数，返回本次应选用的下标
     */
    private int nextIndex(String modelType, int size) {
        AtomicInteger counter = counterMap.computeIfAbsent(modelType, k -> new AtomicInteger(0));
        // getAndIncrement 保证并发安全；对 size 取模映射到下标
        return Math.floorMod(counter.getAndIncrement(), size);
    }

    /**
     * 获取当前轮询位置（不递增），用于构建回退列表
     */
    private int currentIndex(String modelType, int size) {
        AtomicInteger counter = counterMap.get(modelType);
        if (counter == null) {
            return 0;
        }
        // selectModel 已经自增过一次，这里回退一位得到本次实际选中的下标
        return Math.floorMod(counter.get() - 1, size);
    }
}
