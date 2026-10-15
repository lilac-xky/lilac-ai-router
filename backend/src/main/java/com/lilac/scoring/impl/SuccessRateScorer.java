package com.lilac.scoring.impl;

import com.lilac.domain.entity.Model;
import com.lilac.scoring.Scorer;
import com.lilac.scoring.ScoringContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 成功率维度：成功率越高得分越低。成功率本身已经是 0-100，不需要再做归一化。
 */
@Component
public class SuccessRateScorer implements Scorer {

    @Override
    public String dimension() {
        return "successRate";
    }

    @Override
    public double score(Model model, ScoringContext context) {
        BigDecimal rate = context.successRate() == null ? BigDecimal.ZERO : context.successRate();
        return 1.0 - rate.doubleValue() / 100.0;
    }
}
