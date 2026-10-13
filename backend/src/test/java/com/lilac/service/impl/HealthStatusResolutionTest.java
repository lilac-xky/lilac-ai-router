package com.lilac.service.impl;

import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.enums.HealthStatusEnum;
import com.lilac.enums.ProbeFailureKind;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.service.ModelProviderService;
import com.lilac.service.ModelService;
import com.lilac.adapter.ModelAdapterFactory;
import com.lilac.task.ProbeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 健康判定表：任务 I 第二半（样本量下限）的落点。
 *
 * <p>为什么单测这张表而不是只靠端到端：端到端要等 tick、要造数据，且只能验一两个组合；
 * 这张表有「探测结论 × 样本量 × 成功率」三个维度，症状又是<b>静默的</b> ——
 * 判错不会报错，只会让一个坏模型留在候选池、或让一个好模型被踢出去。
 *
 * <h3>三档优先级（顺序本身就是规格）</h3>
 * <ol>
 *   <li><b>探测失败</b>优先：AUTH / BAD_REQUEST 判死，其余降到 degraded</li>
 *   <li><b>没有可信信号</b>（没探过 / 窗口无样本）→ unknown，既不当满分也不判死</li>
 *   <li><b>样本不足</b>（&lt; minSamples）→ unknown，<b>不据小样本判状态</b></li>
 *   <li>样本足够时，成功率 ≥ 50% 才 healthy，否则 degraded</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthStatusResolutionTest {

    private static final long PROVIDER_ID = 3L;

    @Mock
    private ModelProviderService modelProviderService;
    @Mock
    private ModelService modelService;
    @Mock
    private ModelAdapterFactory adapterFactory;
    @Mock
    private AIMetricsCollector aiMetricsCollector;
    @Mock
    private ProbeWindowStore probeWindowStore;

    @InjectMocks
    private HealthCheckServiceImpl service;

    @Test
    @DisplayName("探测失败且属「配置坏了」→ 判死（AUTH / BAD_REQUEST）")
    void probeFailureThatCannotSelfHeal_excluded() {
        for (ProbeFailureKind kind : new ProbeFailureKind[]{
                ProbeFailureKind.AUTH, ProbeFailureKind.BAD_REQUEST}) {
            when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.failure(kind, 100));

            assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("100.00"), 100))
                    .as("%s 等多久都不会自愈，必须排除出候选池", kind)
                    .isEqualTo(HealthStatusEnum.UNHEALTHY.getValue());
        }
    }

    @Test
    @DisplayName("探测失败但仍可能自愈 → 只降级，不判死（限流 / 5xx / 不可达）")
    void probeFailureThatMaySelfHeal_onlyDegraded() {
        for (ProbeFailureKind kind : new ProbeFailureKind[]{
                ProbeFailureKind.RATE_LIMITED, ProbeFailureKind.SERVER_ERROR,
                ProbeFailureKind.UNREACHABLE, ProbeFailureKind.UNKNOWN}) {
            when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.failure(kind, 100));

            assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("100.00"), 100))
                    .as("%s 不该把厂商一棍子打死（限流恰恰说明上游活着）", kind)
                    .isEqualTo(HealthStatusEnum.DEGRADED.getValue());
        }
    }

    @Test
    @DisplayName("探测失败优先于成功率：成功率 100% 也不能翻案")
    void probeFailureWinsOverPerfectSuccessRate() {
        when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.failure(ProbeFailureKind.AUTH, 100));

        // 这正是任务 F 修的那个盲区：窗口内「没有失败日志」→ 成功率 100% → 老逻辑判 healthy
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("100.00"), 999))
                .as("成功率满分但 Key 已失效，判 healthy 就是让每个请求白跑一次上游")
                .isEqualTo(HealthStatusEnum.UNHEALTHY.getValue());
    }

    @Test
    @DisplayName("样本不足 → unknown，不据小样本判状态（§3.1 自锁的防线）")
    void insufficientSamples_doNotDecide() {
        when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.success(120, 200));

        // §3.1：1 条失败日志 → 成功率 0% → 拉黑 24 小时。下限之内一律不动状态
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("0.00"), 4))
                .as("4 条样本（< 下限 5）算出 0%%，不能据此判 unhealthy")
                .isEqualTo(HealthStatusEnum.UNKNOWN.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("100.00"), 1))
                .as("样本不足时也不该判 healthy —— 那等于回到「没数据=满分」")
                .isEqualTo(HealthStatusEnum.UNKNOWN.getValue());
    }

    @Test
    @DisplayName("样本刚好到达下限 → 开始按成功率判（下限是边界，不是永久豁免）")
    void samplesAtThreshold_startDeciding() {
        when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.success(120, 200));

        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("90.00"), 5))
                .as("5 条恰好达标，应正常判 healthy").isEqualTo(HealthStatusEnum.HEALTHY.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("0.00"), 5))
                .as("5 条达标且全失败，这次该认了")
                .isEqualTo(HealthStatusEnum.DEGRADED.getValue());
    }

    @Test
    @DisplayName("没探过 / 窗口无样本 → unknown（既不当满分，也不判死）")
    void noSignal_returnsUnknown() {
        when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(null);

        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("100.00"), 100))
                .as("没测过不能当满分 —— 那正是 issue 问题一")
                .isEqualTo(HealthStatusEnum.UNKNOWN.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, null, 0))
                .as("窗口内没有日志时传 null 成功率")
                .isEqualTo(HealthStatusEnum.UNKNOWN.getValue());
    }

    @Test
    @DisplayName("样本足够时按成功率分档：80/50 两条线")
    void enoughSamples_bucketsBySuccessRate() {
        when(probeWindowStore.latest(PROVIDER_ID)).thenReturn(ProbeResult.success(120, 200));

        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("80.00"), 10))
                .as("80 是 healthy 的下边界").isEqualTo(HealthStatusEnum.HEALTHY.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("79.99"), 10))
                .as("略低于 80 应落到 degraded").isEqualTo(HealthStatusEnum.DEGRADED.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("50.00"), 10))
                .as("50 是 degraded 的下边界").isEqualTo(HealthStatusEnum.DEGRADED.getValue());
        assertThat(service.resolveHealthStatus(PROVIDER_ID, new BigDecimal("49.99"), 10))
                .as("低于 50 只降到 degraded —— 模型层面不因成功率判死，"
                        + "因为「可达但成功率低」交给熔断器按真实调用收敛")
                .isEqualTo(HealthStatusEnum.DEGRADED.getValue());
    }
}
