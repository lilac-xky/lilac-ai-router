package com.lilac.scoring.impl;

import com.lilac.domain.entity.Model;
import com.lilac.scoring.Scorer;
import com.lilac.scoring.ScoringContext;
import com.lilac.scoring.ScoringMath;
import org.springframework.stereotype.Component;

/**
 * 延迟维度：延迟越低越好。
 *
 * <p>avgLatency = 0 表示「还没测过」（哨兵值，见 {@link ScoringMath#UNKNOWN_LATENCY}），
 * 用默认值参与归一化而不是当成 0ms 最快 —— 否则新接入的模型会被误判为最优。
 */
@Component
public class LatencyScorer implements Scorer {

    @Override
    public String dimension() {
        return "latency";
    }

    @Override
    public double score(Model model, ScoringContext context) {
        int latency = context.avgLatency() > 0 ? context.avgLatency() : ScoringMath.UNKNOWN_LATENCY;
        return ScoringMath.normalize(latency, context.bounds().minLatency(), context.bounds().maxLatency());
    }
}
