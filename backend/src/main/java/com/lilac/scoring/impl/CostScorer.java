package com.lilac.scoring.impl;

import com.lilac.domain.entity.Model;
import com.lilac.scoring.Scorer;
import com.lilac.scoring.ScoringContext;
import com.lilac.scoring.ScoringMath;
import org.springframework.stereotype.Component;

/**
 * 成本维度：输入 + 输出单价之和，归一化后越低越好。
 */
@Component
public class CostScorer implements Scorer {

    @Override
    public String dimension() {
        return "cost";
    }

    @Override
    public double score(Model model, ScoringContext context) {
        return ScoringMath.normalize(
                ScoringMath.nullToZero(model.getInputPrice()).add(ScoringMath.nullToZero(model.getOutputPrice())),
                context.bounds().minCost(),
                context.bounds().maxCost());
    }
}
