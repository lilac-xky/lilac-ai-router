package com.lilac.service.impl;

import com.lilac.domain.entity.Model;
import com.lilac.enums.ModelStatusEnum;
import com.lilac.mapper.ModelMapper;
import com.lilac.service.ModelService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型服务实现类
 */
@Service
public class ModelServiceImpl extends ServiceImpl<ModelMapper, Model> implements ModelService {

    /**
     * 获取所有启用的模型
     *
     * @return 模型列表
     */
    @Override
    public List<Model> getActiveModels() {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", ModelStatusEnum.ACTIVE.getValue());
        return list(queryWrapper);
    }

    /**
     * 更新模型指标信息
     *
     * @param modelId 模型ID
     * @param healthStatus 模型健康状态
     * @param avgLatency 平均延迟
     * @param successRate 成功率
     * @param score 综合得分
     */
    @Override
    public void updateModelMetrics(Long modelId, String healthStatus, Integer avgLatency, BigDecimal successRate, BigDecimal score) {
        // 使用 UpdateEntity 只更新指定字段，避免覆盖其它列
        Model updateModel = UpdateEntity.of(Model.class, modelId);
        updateModel.setHealthStatus(healthStatus);
        updateModel.setAvgLatency(avgLatency);
        updateModel.setSuccessRate(successRate);
        updateModel.setScore(score);
        updateById(updateModel);
    }
}
