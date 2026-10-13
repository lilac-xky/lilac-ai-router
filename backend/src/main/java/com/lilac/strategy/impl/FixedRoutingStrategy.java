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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 固定模型路由策略
 * 使用用户指定的模型
 */
@Slf4j
@Component
public class FixedRoutingStrategy implements RoutingStrategyInterface {

    @Resource
    private ModelService modelService;

    /**
     * 选择模型（从数据库查询并返回最符合策略的一条数据）
     *
     * @param modelType 模型类型
     * @param requestedModel 用户请求的模型
     * @return 最优模型
     */
    @Override
    public Model selectModel(String modelType, String requestedModel) {
        // 固定模型策略必须指定模型
        if (StrUtil.isBlank(requestedModel)) {
            return null;
        }
        // 直接按 modelKey + status 查询，不再用 healthStatus 判定：健康状态是 30s tick 的快照，可能已经过期
        Model requested = modelService.getOne(QueryWrapper.create()
                .eq("modelKey", requestedModel)
                .eq("status", ModelStatusEnum.ACTIVE.getValue()));
        if (requested != null && !isSelectable(requested.getHealthStatus())) {
            log.warn("指定的模型 {} 当前健康状态为 {}，仍按用户指定尝试一次，失败后走备选", requestedModel, requested.getHealthStatus());
        }
        return requested;
    }

    /**
     * 获取 Fallback 模型列表（除了主选模型外的备选模型）
     *
     * @param modelType 模型类型
     * @param requestedModel 用户请求的模型
     * @return 回退模型
     */
    @Override
    public List<Model> getFallbackModels(String modelType, String requestedModel) {
        List<Model> candidates = listActiveModels(modelType).stream()
                .filter(m -> !Objects.equals(m.getModelKey(), requestedModel))
                .collect(Collectors.toList());

        List<Model> selectable = candidates.stream()
                .filter(m -> isSelectable(m.getHealthStatus()))
                .collect(Collectors.toList());
        if (!selectable.isEmpty()) {
            return selectable;
        }
        // 全都不可用时仍然返回它们：备选链为空的后果是「一个备选都不试」，比试一个状态可疑的模型糟得多
        if (!candidates.isEmpty()) {
            log.warn("模型类型 {} 下没有健康备选，降级为使用 {} 个状态可疑的模型兜底", modelType, candidates.size());
        }
        return candidates;
    }

    /**
     * 获取路由策略类型
     *
     * @return 策略类型
     */
    @Override
    public String getStrategyType() {
        return RoutingStrategyTypeEnum.FIXED.getValue();
    }

    /**
     * 候选全集：只要 status=active，不按 healthStatus 过滤。
     * 原先主选与备选共用同一个 healthStatus 过滤条件，导致主选被判不健康时备选在结构上必然一起消失
     */
    private List<Model> listActiveModels(String modelType) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", ModelStatusEnum.ACTIVE.getValue());
        if (StrUtil.isNotBlank(modelType)) {
            queryWrapper.eq("modelType", modelType);
        }
        queryWrapper.orderBy("priority", false);
        // id 兜底：priority 并列时两次查询的顺序不保证一致，主选与备选各查一次就会「跳错一个」
        queryWrapper.orderBy("id", true);
        return modelService.list(queryWrapper);
    }

    /**
     * 健康状态是否可用于选路：healthy / degraded / unknown 都算，只排除 unhealthy
     */
    private boolean isSelectable(String healthStatus) {
        return HealthStatusEnum.HEALTHY.getValue().equals(healthStatus)
                || HealthStatusEnum.DEGRADED.getValue().equals(healthStatus)
                || HealthStatusEnum.UNKNOWN.getValue().equals(healthStatus);
    }
}
