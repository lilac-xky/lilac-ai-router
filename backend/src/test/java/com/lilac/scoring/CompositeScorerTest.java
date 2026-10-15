package com.lilac.scoring;

import com.lilac.domain.entity.Model;
import com.lilac.enums.HealthStatusEnum;
import com.lilac.scoring.impl.CostScorer;
import com.lilac.scoring.impl.HealthScorer;
import com.lilac.scoring.impl.LatencyScorer;
import com.lilac.scoring.impl.PriorityScorer;
import com.lilac.scoring.impl.SuccessRateScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评分器拆分的核心约定：<b>重构不改分</b>。
 *
 * <h3>为什么这个测试必须存在</h3>
 * S4 把 {@code HealthCheckServiceImpl} 里硬编码的四维加权抽成了可插拔评分器。
 * 纯重构唯一可接受的验收标准就是「同一份输入算出同一个分数」，但这个等价性眼睛看不出来，
 * 因为四维加权里藏着一个浮点陷阱：
 * <pre>
 * 0.3 + 0.3 + 0.3 + 0.1 = 0.9999999999999999   （不是 1.0）
 * </pre>
 * 我第一版实现为了「调单个权重时不必保证总和为 1」，把加权和除以了权重和
 * （即 {@code / 0.9999999999999999}）。实测这在「四个维度分值都逼近舍入中点」的取值上
 * 会翻掉第 4 位小数 —— 是货真价实的行为变更，不是浮点噪音。现在改为只做加权求和。
 *
 * <h3>⚠️ 本类第一版是「假绿」，靠变异测试发现的</h3>
 * 第一版的边界用例用真实评分器构造输入，但 {@code Math.round(target)} 让延迟分值只会是
 * 0 或 1、优先级只取到 0..10，四个分量根本没同时逼近 target，
 * 加权和落不到 {@code .xxxx5} 的舍入中点上 —— 于是我把 bug 重新植入后<b>用例依然全绿</b>。
 * 现在改用 {@link FixedScorer} 直接给出分值，才能稳定命中舍入中点。
 * 「测试全绿」本身不能证明测试有效，必须验证它能红。
 */
class CompositeScorerTest {

    /** 直接返回预设分值的评分器：绕开归一化的取整，才能稳定构造出舍入中点 */
    private record FixedScorer(String dimension, double value) implements Scorer {
        @Override
        public double score(Model model, ScoringContext context) {
            return value;
        }
    }

    /** 旧实现：改造前 HealthCheckServiceImpl.calculateScore 的四维加权，逐字保留作为对照 */
    private static BigDecimal legacyScore(double cost, double latency, double successRate, double priority) {
        double score = cost * 0.3 + latency * 0.3 + successRate * 0.3 + priority * 0.1;
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    /** 生产实现（默认权重 0.3/0.3/0.3/0.1，不含 health） */
    private static CompositeScorer productionScorer() {
        return new CompositeScorer(List.of(
                new CostScorer(), new LatencyScorer(), new SuccessRateScorer(), new PriorityScorer()));
    }

    /** 用固定分值替换各维度，权重仍然是生产的那一套 */
    private static CompositeScorer fixedScorer(double cost, double latency, double successRate, double priority) {
        return new CompositeScorer(List.of(
                new FixedScorer("cost", cost),
                new FixedScorer("latency", latency),
                new FixedScorer("successRate", successRate),
                new FixedScorer("priority", priority)));
    }

    private static ScoringBounds bounds(String minCost, String maxCost, int minLat, int maxLat, int minPri, int maxPri) {
        return new ScoringBounds(new BigDecimal(minCost), new BigDecimal(maxCost), minLat, maxLat, minPri, maxPri);
    }

    private static ScoringContext ctx() {
        return new ScoringContext(bounds("0", "1", 0, 1, 0, 1), 1, BigDecimal.valueOf(50));
    }

    private static Model anyModel() {
        return Model.builder()
                .inputPrice(BigDecimal.ZERO)
                .outputPrice(BigDecimal.ZERO)
                .priority(0)
                .healthStatus(HealthStatusEnum.HEALTHY.getValue())
                .build();
    }

    @Test
    @DisplayName("等价性 · 四维分值网格 11^4 组，生产权重 vs 旧实现逐位一致")
    void score_matchesLegacyImplementation_onGrid() {
        int checked = 0, mismatched = 0;
        String firstDiff = null;

        for (int a = 0; a <= 10; a++) {
            for (int b = 0; b <= 10; b++) {
                for (int c = 0; c <= 10; c++) {
                    for (int d = 0; d <= 10; d++) {
                        double cost = a / 10.0, lat = b / 10.0, succ = c / 10.0, pri = d / 10.0;
                        BigDecimal legacy = legacyScore(cost, lat, succ, pri);
                        BigDecimal now = fixedScorer(cost, lat, succ, pri).score(anyModel(), ctx());

                        checked++;
                        if (legacy.compareTo(now) != 0) {
                            mismatched++;
                            if (firstDiff == null) {
                                firstDiff = "(" + cost + "," + lat + "," + succ + "," + pri
                                        + ") 旧=" + legacy + " 新=" + now;
                            }
                        }
                    }
                }
            }
        }

        assertThat(checked).as("应为 11^4 = 14641 组").isEqualTo(14641);
        assertThat(mismatched).as("新旧分值必须逐位一致；第一处差异：%s", firstDiff).isZero();
    }

    @Test
    @DisplayName("等价性 · 四个维度都等于 target，扫过全部 4 位小数舍入中点（旧版按权重和归一化会大面积翻面）")
    void score_matchesLegacyImplementation_atRoundingMidpoint() {
        int mismatched = 0;
        String firstDiff = null;

        // target 从 0.00005 起，步进 0.0001：让加权和稳定落在 x.xxxx5 的舍入中点上
        for (int i = 0; i < 10000; i++) {
            double target = 0.00005 + i * 0.0001;
            BigDecimal legacy = legacyScore(target, target, target, target);
            BigDecimal now = fixedScorer(target, target, target, target).score(anyModel(), ctx());

            if (legacy.compareTo(now) != 0) {
                mismatched++;
                if (firstDiff == null) {
                    firstDiff = "target=" + target + " 旧=" + legacy + " 新=" + now;
                }
            }
        }

        assertThat(mismatched)
                .as("落在舍入中点时必须与旧实现一致（不为 0 说明分值被 /权重和 之类改变了）；第一处：%s", firstDiff)
                .isZero();
    }

    @Test
    @DisplayName("等价性 · 只有成本维度变化时也一致（防止某个维度被漏加或加错权重）")
    void score_matchesLegacyImplementation_singleDimensionSweep() {
        for (int dim = 0; dim < 4; dim++) {
            for (int i = 0; i <= 1000; i++) {
                double v = i / 1000.0;
                double[] s = {0.5, 0.5, 0.5, 0.5};
                s[dim] = v;
                assertThat(fixedScorer(s[0], s[1], s[2], s[3]).score(anyModel(), ctx()))
                        .as("第 %d 个维度取值 %s 时与旧实现不一致", dim, v)
                        .isEqualByComparingTo(legacyScore(s[0], s[1], s[2], s[3]));
            }
        }
    }

    @Test
    @DisplayName("权重和 · 0.3+0.3+0.3+0.1 不是精确 1.0，这就是不能按权重和归一化的原因")
    void defaultWeights_doNotSumToOne() {
        assertThat(0.3 + 0.3 + 0.3 + 0.1)
                .as("默认权重之和不是精确 1.0，除以它就会改变分值舍入结果")
                .isNotEqualTo(1.0);
    }

    @Test
    @DisplayName("健康度维度 · healthy=0 / unknown=0.25 / degraded=0.5 / unhealthy=1，unknown 不得优于 healthy")
    void healthScorer_ordersUnknownBetweenHealthyAndDegraded() {
        HealthScorer scorer = new HealthScorer();
        ScoringContext context = new ScoringContext(bounds("0", "1", 0, 1, 0, 1), 100, BigDecimal.valueOf(100));

        double healthy = scorer.score(withHealth(HealthStatusEnum.HEALTHY.getValue()), context);
        double unknown = scorer.score(withHealth(HealthStatusEnum.UNKNOWN.getValue()), context);
        double degraded = scorer.score(withHealth(HealthStatusEnum.DEGRADED.getValue()), context);
        double unhealthy = scorer.score(withHealth(HealthStatusEnum.UNHEALTHY.getValue()), context);

        assertThat(healthy).as("healthy 应是最优（0）").isZero();
        assertThat(unknown)
                .as("unknown 必须比 healthy 差、比 degraded 好 —— 「还没测过」不该被当成健康")
                .isGreaterThan(healthy)
                .isLessThan(degraded);
        assertThat(degraded).isLessThan(unhealthy);
        assertThat(unhealthy).isEqualTo(1.0);
    }

    @Test
    @DisplayName("健康度默认 0 权重 · 默认配置下不参与 AUTO 排序（healthStatus 另有硬过滤）")
    void healthDimension_hasZeroWeightByDefault() {
        CompositeScorer scorer = productionScorer();
        ScoringContext context = new ScoringContext(bounds("0", "0", 500, 500, 0, 0), 500, BigDecimal.valueOf(100));

        assertThat(scorer.score(withHealth(HealthStatusEnum.HEALTHY.getValue()), context))
                .as("默认权重里没有 health 项，所以两者得分相同（与改造前一致）")
                .isEqualByComparingTo(scorer.score(withHealth(HealthStatusEnum.DEGRADED.getValue()), context));
    }

    @Test
    @DisplayName("延迟哨兵值 · avgLatency=0（未测过）按 5000 参与归一化，不能当成 0ms 最快")
    void latencyScorer_treatsZeroAsUnknownNotFastest() {
        LatencyScorer scorer = new LatencyScorer();
        ScoringBounds b = bounds("0", "0", 0, 5000, 0, 0);
        Model m = anyModel();

        assertThat(scorer.score(m, new ScoringContext(b, 0, BigDecimal.valueOf(100))))
                .as("0 是「没测过」的哨兵值，按默认 5000ms 处理 → 落在 max 端")
                .isEqualTo(1.0);
        assertThat(scorer.score(m, new ScoringContext(b, 5000, BigDecimal.valueOf(100))))
                .isEqualTo(1.0);
    }

    private static Model withHealth(String status) {
        return Model.builder()
                .inputPrice(BigDecimal.ZERO)
                .outputPrice(BigDecimal.ZERO)
                .priority(0)
                .healthStatus(status)
                .build();
    }
}
