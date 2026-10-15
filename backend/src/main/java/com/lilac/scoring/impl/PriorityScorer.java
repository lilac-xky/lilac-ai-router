package com.lilac.scoring.impl;

import com.lilac.domain.entity.Model;
import com.lilac.scoring.Scorer;
import com.lilac.scoring.ScoringContext;
import com.lilac.scoring.ScoringMath;
import org.springframework.stereotype.Component;

/**
 * 优先级维度：priority 越大越优先，所以取反后归一化。
 */
@Component
public class PriorityScorer implements Scorer {

    @Override
    public String dimension() {
        return "priority";
    }

    @Override
    public double score(Model model, ScoringContext context) {
        int priority = model.getPriority() != null ? model.getPriority() : 0;
        return 1.0 - ScoringMath.normalize(priority, context.bounds().minPriority(), context.bounds().maxPriority());
    }
}
