package com.lilac.scoring;

import com.lilac.domain.entity.Model;

/**
 * 单个评分维度：返回 0-1 的分值，越小越好。\
 */
public interface Scorer {

    /**
     * 维度名，同时是权重配置的键（如 {@code cost}）
     */
    String dimension();

    /**
     * @return 0-1 的分值，0 表示这个维度上最优
     */
    double score(Model model, ScoringContext context);
}
