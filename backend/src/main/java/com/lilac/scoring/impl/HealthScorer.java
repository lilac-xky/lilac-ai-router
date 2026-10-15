package com.lilac.scoring.impl;

import com.lilac.enums.HealthStatusEnum;
import com.lilac.domain.entity.Model;
import com.lilac.scoring.Scorer;
import com.lilac.scoring.ScoringContext;
import org.springframework.stereotype.Component;

/**
 * 健康度维度。
 * 默认权重为 0：healthStatus 已经是各策略查询里的硬过滤条件（healthy/degraded/unknown 才进候选池），
 * 再计一次分等于重复计算。想让它参与排序就把 {@code lilac.routing.scoring.weights.health} 调大。
 */
@Component
public class HealthScorer implements Scorer {

    @Override
    public String dimension() {
        return "health";
    }

    @Override
    public double score(Model model, ScoringContext context) {
        String status = model.getHealthStatus();
        if (HealthStatusEnum.HEALTHY.getValue().equals(status)) {
            return 0.0;
        }
        // unknown 排在 degraded 之前：还没测过不等于坏，但也不该比已知健康更优
        if (HealthStatusEnum.UNKNOWN.getValue().equals(status)) {
            return 0.25;
        }
        if (HealthStatusEnum.DEGRADED.getValue().equals(status)) {
            return 0.5;
        }
        return 1.0;
    }
}
