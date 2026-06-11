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

import java.util.List;
import java.util.stream.Collectors;

/**
 * 延迟优先路由策略
 * 选择延迟最低的健康模型
 */
@Component
public class LatencyFirstRoutingStrategy implements RoutingStrategyInterface {

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
        QueryWrapper queryWrapper = buildBaseQueryWrapper(modelType);
        // 按延迟排序（延迟为0的排后面），取第一个
        queryWrapper.orderBy("CASE WHEN avgLatency = 0 THEN 999999 ELSE avgLatency END", true);
        queryWrapper.limit(1);
        return modelService.getOne(queryWrapper);
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
        QueryWrapper queryWrapper = buildBaseQueryWrapper(modelType);
        // 按延迟排序
        queryWrapper.orderBy("CASE WHEN avgLatency = 0 THEN 999999 ELSE avgLatency END", true);
        List<Model> models = modelService.list(queryWrapper);
        // 跳过第一个（已被 selectModel 选中）
        return models.stream().skip(1).collect(Collectors.toList());
    }

    /**
     * 获取路由策略类型
     *
     * @return 策略类型
     */
    @Override
    public String getStrategyType() {
        return RoutingStrategyTypeEnum.LATENCY_FIRST.getValue();
    }

    /**
     * 构建基础查询条件：状态为启用且健康状态为健康或降级或未知
     */
    private QueryWrapper buildBaseQueryWrapper(String modelType) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", ModelStatusEnum.ACTIVE.getValue())
                .in("healthStatus", HealthStatusEnum.HEALTHY.getValue(),
                        HealthStatusEnum.DEGRADED.getValue(), HealthStatusEnum.UNKNOWN.getValue());
        if (StrUtil.isNotBlank(modelType)) {
            queryWrapper.eq("modelType", modelType);
        }
        return queryWrapper;
    }
}