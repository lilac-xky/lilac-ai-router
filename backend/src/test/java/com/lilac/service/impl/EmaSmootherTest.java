package com.lilac.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EMA 平滑的两条边界与「可退回」性质。
 *
 * <h3>为什么单独测这个类</h3>
 * 它是纯函数（无 Spring、无 DB），把「平滑公式 + 两个哨兵值的处理」从
 * {@code HealthCheckServiceImpl} 里拆出来，就是为了能这样直接断言数字 ——
 * 否则只能靠起上下文 + 造 24h 请求日志去间接验证，代价高且难覆盖冷启动。
 *
 * <h3>两道防线</h3>
 * <ol>
 *   <li><b>冷启动</b>：{@code avgLatency = 0} 是「未测过」哨兵。若当旧值参与平滑，
 *       真实 800ms 的新模型首轮只得到 240ms，会被误判成最快。</li>
 *   <li><b>退回开关</b>：{@code alpha = 1.0} 必须逐字等于不平滑的结果 ——
 *       这是「改坏了能一键退回」的保证，也让改动前后的对比实验有干净的基线。</li>
 * </ol>
 */
class EmaSmootherTest {

    // ------------------------------------------------------------------ 延迟

    @Test
    @DisplayName("冷启动 · 旧值为 0（哨兵）时直接采纳首样本，不能按比例缩水")
    void latency_coldStart_takesSampleAsIs() {
        // 一个真实 800ms 的新模型：若把 0 当旧值，α=0.3 会算出 240ms
        assertThat(EmaSmoother.smoothLatency(0, 800, 0.3))
                .as("冷启动必须直接采纳首样本；算出 240 就说明拿了哨兵值当旧值")
                .isEqualTo(800);
    }

    @Test
    @DisplayName("平滑 · 稳态下按 α 加权，单次极值只能推动一部分")
    void latency_steadyState_weightsSampleByAlpha() {
        // 旧值 800，本轮中位数突然变成 85000（W7 那次 1352s 超时的量级）
        int smoothed = EmaSmoother.smoothLatency(800, 85000, 0.3);
        // 0.3 * 85000 + 0.7 * 800 = 26060
        assertThat(smoothed)
                .as("单次极值只能把延迟推到 26060，而不是直接跳到 85000")
                .isEqualTo(26060)
                .isLessThan(85000);
    }

    @Test
    @DisplayName("平滑 · 连续多轮极值最终会收敛到真实水平（平滑不等于永远迟钝）")
    void latency_repeatedExtremes_converge() {
        int value = 800;
        for (int i = 0; i < 50; i++) {
            value = EmaSmoother.smoothLatency(value, 85000, 0.3);
        }
        assertThat(value)
                .as("持续极值时应收敛到 85000 附近，否则等于把故障永久掩盖了")
                .isCloseTo(85000, org.assertj.core.data.Offset.offset(100));
    }

    @Test
    @DisplayName("无样本 · 本轮中位数 <= 0 时保持上一轮结论，不把指标清零")
    void latency_noSample_keepsPrevious() {
        assertThat(EmaSmoother.smoothLatency(800, 0, 0.3))
                .as("本轮没有有效延迟样本时不能把 800 清成 0（0 会被当成「未测过」）")
                .isEqualTo(800);
    }

    @Test
    @DisplayName("退回开关 · α = 1.0 时逐字等于不平滑的值")
    void latency_alphaOne_matchesNoSmoothing() {
        for (int sample : new int[]{1, 137, 800, 5000, 85000}) {
            for (int previous : new int[]{0, 1, 800, 5000}) {
                assertThat(EmaSmoother.smoothLatency(previous, sample, 1.0))
                        .as("α=1.0 必须退回改造前行为（previous=%d sample=%d）", previous, sample)
                        .isEqualTo(sample);
            }
        }
    }

    // ------------------------------------------------------------------ 成功率

    @Test
    @DisplayName("冷启动 · 不能拿 DDL 默认的 100.00 当旧值，否则真实 0% 会被算成 70%")
    void successRate_coldStart_ignoresDdlDefault() {
        // 库里 successRate 的 DDL 默认值是 100.00（不是 NULL），所以「从未测过」和「一直完美」长得一样
        BigDecimal ddlDefault = new BigDecimal("100.00");
        BigDecimal realSample = new BigDecimal("0.00");

        assertThat(EmaSmoother.smoothSuccessRate(ddlDefault, realSample, 0.3, true))
                .as("冷启动必须直接采纳首样本 0%，算成 70% 会让一个全失败的模型看起来基本健康")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("平滑 · 稳态下按 α 加权并保留 2 位小数（与库表 decimal(5,2) 对齐）")
    void successRate_steadyState_weightsByAlpha() {
        // 0.3 * 0 + 0.7 * 100 = 70.00
        assertThat(EmaSmoother.smoothSuccessRate(new BigDecimal("100.00"), new BigDecimal("0.00"), 0.3, false))
                .isEqualByComparingTo("70.00")
                .hasScaleOf(2);
    }

    @Test
    @DisplayName("平滑 · 成功率下降会被平滑，连续失败后收敛到 0.01 的小地板（不是 0，见类注释）")
    void successRate_repeatedFailures_convergeToFloor() {
        BigDecimal rate = new BigDecimal("100.00");
        for (int i = 0; i < 50; i++) {
            rate = EmaSmoother.smoothSuccessRate(rate, new BigDecimal("0.00"), 0.3, false);
        }
        // 0.01 是不动点：0.7 * 0.01 = 0.007 → HALF_UP 到 2 位又回到 0.01
        assertThat(rate)
                .as("持续失败应收敛到 0.01 这个地板（实测 60 轮不动）；它同属「<50」档，不影响判死")
                .isEqualByComparingTo("0.01");

        // 地板的存在不影响健康判定：0.00 与 0.01 都远低于 50 的 degraded 线
        assertThat(rate.doubleValue())
                .as("地板必须远低于健康阈值 50，否则会掩盖故障")
                .isLessThan(50.0);
    }

    @Test
    @DisplayName("无样本 · 本轮成功率为 null 时保持上一轮结论")
    void successRate_nullSample_keepsPrevious() {
        assertThat(EmaSmoother.smoothSuccessRate(new BigDecimal("88.00"), null, 0.3, false))
                .isEqualByComparingTo("88.00");
    }

    @Test
    @DisplayName("退回开关 · α = 1.0 时逐字等于本轮样本")
    void successRate_alphaOne_matchesNoSmoothing() {
        assertThat(EmaSmoother.smoothSuccessRate(new BigDecimal("100.00"), new BigDecimal("42.5"), 1.0, false))
                .isEqualByComparingTo("42.50");
    }
}
