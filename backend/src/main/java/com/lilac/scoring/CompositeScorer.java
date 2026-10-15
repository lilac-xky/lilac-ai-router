package com.lilac.scoring;

import com.lilac.domain.entity.Model;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 加权组合评分：把各维度的分值按权重加权平均。
 *
 * <p>权重从 {@code lilac.routing.scoring.weights.<dimension>} 读取，缺省值与原实现一致，
 * 保证改造前后同一份数据算出同一个分数（见 {@code CompositeScorerTest} 的等价性用例）。
 */
@Slf4j
@Component
public class CompositeScorer {

    @Resource
    private List<Scorer> scorers;

    @Value("${lilac.routing.scoring.weights.cost:0.3}")
    private double costWeight = 0.3;
    @Value("${lilac.routing.scoring.weights.latency:0.3}")
    private double latencyWeight = 0.3;
    @Value("${lilac.routing.scoring.weights.successRate:0.3}")
    private double successRateWeight = 0.3;
    @Value("${lilac.routing.scoring.weights.priority:0.1}")
    private double priorityWeight = 0.1;
    // 健康度默认 0 权重：healthStatus 已经是各策略查询里的硬过滤条件，再计一次分是重复计算
    @Value("${lilac.routing.scoring.weights.health:0.0}")
    private double healthWeight = 0.0;

    /**
     * Spring 用的构造器。
     * 显式写出：下面那个测试用构造器会让隐式无参构造器消失，而 Spring 需要它来做字段注入。
     */
    public CompositeScorer() {
    }

    /**
     * 测试用构造器：绕开 {@code @Value}，直接给评分器与权重。
     * 目的是让等价性用例调用<b>真实的生产代码</b>，而不是在测试里另抄一份加权表达式
     * （抄一份就等于测自己，权重改了也发现不了）。
     */
    CompositeScorer(List<Scorer> scorers) {
        this.scorers = scorers;
    }

    /**
     * 启动时打印生效的维度与权重 —— 配错键名时权重会静默失效（拿不到 @Value 默认值），
     * 这一行是唯一能看出「我配了但没生效」的地方
     */
    @PostConstruct
    void logEffectiveWeights() {
        log.info("评分维度权重：cost={} latency={} successRate={} priority={} health={}；已注册维度={}",
                costWeight, latencyWeight, successRateWeight, priorityWeight, healthWeight,
                scorers.stream().map(Scorer::dimension).sorted().toList());
        double sum = costWeight + latencyWeight + successRateWeight + priorityWeight + healthWeight;
        // 权重和不为 1 会让分值整体等比偏移，排序正确但绝对值不可跨配置比较
        if (Math.abs(sum - 1.0) > 1e-9) {
            log.warn("评分权重之和为 {}（不等于 1.0）：分值会整体等比偏移，排序正确但绝对值不可跨配置比较", sum);
        }
    }

    public BigDecimal score(Model model, ScoringContext context) {
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (Scorer scorer : scorers) {
            double weight = weightOf(scorer.dimension());
            if (weight <= 0.0) {
                continue;
            }
            weighted += scorer.score(model, context) * weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0.0) {
            // 权重全配成 0 时不能返回 0：那会让所有模型同分，AUTO 退化成「看谁 id 小」
            log.warn("所有评分维度权重都是 0，返回默认分 0.5；请检查 lilac.routing.scoring.weights");
            return new BigDecimal("0.5");
        }
        // 权重和不为 1 会让分值整体等比偏移，排序正确但绝对值不可跨配置比较
        return BigDecimal.valueOf(weighted).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 维度的权重；未配置的维度按 0 处理，不影响既有分数
     */
    private double weightOf(String dimension) {
        return switch (dimension) {
            case "cost" -> costWeight;
            case "latency" -> latencyWeight;
            case "successRate" -> successRateWeight;
            case "priority" -> priorityWeight;
            case "health" -> healthWeight;
            default -> 0.0;
        };
    }
}
