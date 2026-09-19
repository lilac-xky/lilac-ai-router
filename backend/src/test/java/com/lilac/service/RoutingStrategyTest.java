package com.lilac.service;

import com.lilac.domain.entity.Model;
import com.lilac.enums.RoutingStrategyTypeEnum;
import com.lilac.mapper.ModelMapper;
import com.lilac.strategy.RoutingStrategyFactory;
import com.lilac.strategy.RoutingStrategyInterface;
import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由策略选路结果测试：5 个策略各选哪一个模型。
 *
 * <h3>为什么是集成测试而不是 Mockito 单测</h3>
 * 5 个策略的"逻辑"全在 SQL 里 —— {@code ORDER BY score} / {@code ORDER BY (inputPrice + outputPrice)}
 * / {@code ORDER BY CASE WHEN avgLatency = 0 ...}，连 {@code status} + {@code healthStatus} 的过滤
 * 也都在 QueryWrapper 里。所以用 mock 给 {@code ModelService} 喂一个"排好序的 list"，
 * 验的是你自己喂进去的顺序 —— 把被测逻辑整个替换掉了，是最典型的假绿。
 * 这里走真库 + 真 QueryWrapper，断言的是选出来的 {@code modelKey}。
 *
 * <h3>数据隔离靠 modelType，不靠账号</h3>
 * 策略查询只按 {@code status} + {@code healthStatus}（+ modelType 非空时的 {@code modelType}）过滤，
 * 本机真实模型会被一起捞进来（库里已有 3 行 chat/image 模型，AUTO 会优先选到它们）。
 * 因此每个用例都用一个随机的 {@code modelType}，只有本用例插入的行会命中。
 *
 * <h3>种子里两行负对照故意"每个排序键都取最优"</h3>
 * {@code rt-*-neg-inactive} / {@code rt-*-neg-unhealthy} 的 score 最小、价格最低、延迟最低、priority 最高。
 * 一旦 status / healthStatus 过滤失效，它们必然被选中 —— "过滤坏了"会明确表现为选错，而不是静默通过。
 *
 * <h3>怎么读这个类的日志</h3>
 * 每个用例<b>只打一行</b>，格式固定：
 * <pre>
 * [AUTO·a1b2c3d4] 主选=fast-pricey | fallback=cheap → mid → untested
 * </pre>
 * 方括号里是「策略 · 本次运行号」，冒号后是<b>实际选出来的东西</b>（不是期望值）。
 * 一行 = 一次运行的一块事实，多次跑直接 diff 就能看出结果有没有变。
 * 日志放在断言之前 —— 断言挂了这一行也已经打出来了，能看到"当时实际选了什么"。
 *
 * <p>注意 AUTO 与 LATENCY 的主选都是 {@code fast-pricey}，两者的差别只在 fallback 顺序上；
 * 所以日志把主选和 fallback 打在一起，策略之间的差异才能肉眼可比。
 *
 * <h3>⚠️ 三个必须知道的库表约束</h3>
 * <ol>
 *   <li>{@code uk_modelKey} 是 <b>只含 modelKey 的唯一索引</b>，而 {@code Model} 是逻辑删除 ——
 *       被清理掉的行仍然占着唯一键。所以种子 modelKey 必须带本次运行的 runId，
 *       否则第二次跑必然 duplicate key。</li>
 *   <li>{@code providerId} 是 NOT NULL 且无外键，种子必须给值；{@code createTime}/{@code updateTime}
 *       是 NOT NULL DEFAULT CURRENT_TIMESTAMP，所以必须用 {@code insertSelective}。</li>
 *   <li><b>清理必须物理删除</b>。见 {@link #cleanUp()} / {@link #purgeStaleRows()} 的注释 ——
 *       走 ORM 的 {@code deleteById} 是逻辑删除，行会永久堆在表里。</li>
 * </ol>
 *
 * <h3>⚠️ 依赖：健康检查定时任务已被关掉</h3>
 * 本类断言的是 {@code healthStatus} 过滤是否生效，因此对"种子行被后台改写"零容忍。
 * {@code HealthCheckTask}（{@code @Scheduled(30s)}）会调 {@code syncModelMetricsFromRequestLog()}，
 * 其查询 {@code WHERE status='active' AND isDelete=0} <b>没有 modelType 过滤</b>，
 * 会把本类的种子行改成 {@code healthStatus='healthy'}（{@code ModelStats} 默认 successRate=100）
 * 并重算 avgLatency / score —— 这会让"unhealthy 不得被选中"的负对照恰好失效。
 * 因为整类只跑约 1 秒、tick 每 30 秒一次，症状是<b>偶发红</b>（实测复现过一次）。
 * 现在由 {@code application-test.yml} 的 {@code lilac.health-check.enabled: false} 关闭该任务；
 * 如果哪天有人把它打开，这个类会重新变成 flaky。
 */
@Slf4j
public class RoutingStrategyTest extends IntegrationTestSupport {

    private static final String AUTO = RoutingStrategyTypeEnum.AUTO.getValue();
    private static final String COST_FIRST = RoutingStrategyTypeEnum.COST_FIRST.getValue();
    private static final String LATENCY_FIRST = RoutingStrategyTypeEnum.LATENCY_FIRST.getValue();
    private static final String ROUND_ROBIN = RoutingStrategyTypeEnum.ROUND_ROBIN.getValue();
    private static final String FIXED = RoutingStrategyTypeEnum.FIXED.getValue();

    /** 参与选路的可用模型个数（4 个 active 且不是 unhealthy） */
    private static final int AVAILABLE = 4;

    /** 种子 modelKey 的统一前缀，物理清理时靠它圈定范围 */
    private static final String KEY_PREFIX = "rt-";

    /**
     * 本类所有种子共用的 {@code modelName}，物理清理的第二个条件。
     * 只靠 {@code LIKE 'rt-%'} 圈范围不够严谨（万一生产上真有 rt- 开头的 modelKey 就被误删了），
     * 加上这个标记就等于给测试数据盖了个章。
     */
    private static final String SEED_MARKER = "routing-test";

    /** 残留清理每个 JVM 只做一次，避免 13 个用例各扫一遍全表 */
    private static final AtomicBoolean STALE_PURGED = new AtomicBoolean(false);

    @Resource
    private RoutingStrategyFactory factory;
    @Resource
    private ModelMapper modelMapper;
    /** 只用于物理清理，不参与业务断言 */
    @Resource
    private DataSource dataSource;

    /** 本次运行的唯一后缀，用来绕开 uk_modelKey（见类注释） */
    private String runId;
    /** 每个用例一个独立的 modelType，兼作数据隔离与轮询计数器的分桶键 */
    private String modelType;

    /** 种子里各角色的句柄。断言只比 modelKey，不写死字面量（因为带了 runId） */
    private Model negInactive, negUnhealthy, fastPricey, cheap, mid, untested;

    // ------------------------------------------------------------------ 数据准备

    @BeforeEach
    void seed() {
        purgeStaleRows();

        runId = UUID.randomUUID().toString().substring(0, 8);
        modelType = "rt-" + runId;

        // 负对照：状态/健康过滤一旦失效，这两行会因为"每个排序键都最优"而立刻被选中
        negInactive = insert("neg-inactive", modelType, "inactive", "healthy", "1", "0", "0", 50, 999);
        negUnhealthy = insert("neg-unhealthy", modelType, "active", "unhealthy", "1", "0", "0", 50, 999);

        fastPricey = insert("fast-pricey", modelType, "active", "healthy", "10", "0.05", "0.05", 100, 5);
        cheap = insert("cheap", modelType, "active", "healthy", "20", "0.001", "0.001", 900, 1);
        mid = insert("mid", modelType, "active", "degraded", "30", "0.01", "0.01", 400, 3);
        untested = insert("untested", modelType, "active", "unknown", "60", "0.001", "0.002", 0, 2);
    }

    @AfterEach
    void cleanUp() {
        // 测试数据的生命周期 = 测试的生命周期。
        // modelMapper.deleteById() 走的是逻辑删除（UPDATE model SET isDelete=1），行永远留在表里，
        // 跑一次攒一批，得手工 DELETE。选路测试根本不验删除语义，所以直接物理删掉。
        physicallyDelete(KEY_PREFIX + runId + "-%");
    }

    /**
     * 兜底：上一次运行被 IDE 强停（@AfterEach 没来得及跑）时留下的垃圾。
     * 每个 JVM 只扫一次 —— 不加这层的话，那些行会一直堆在表里，
     * 虽然本类带 runId 不会撞唯一键，但表会越来越脏。
     */
    private void purgeStaleRows() {
        if (STALE_PURGED.compareAndSet(false, true)) {
            int n = physicallyDelete(KEY_PREFIX + "%");
            if (n > 0) {
                log.info("清掉上次运行残留的 {} 行测试模型", n);
            }
        }
    }

    // ------------------------------------------------------------------ 5 个策略的选路结果

    @Test
    @DisplayName("AUTO · 按 score ASC 选中得分最低的可用模型，fallback 按同一排序键继续")
    void auto_picksLowestScore() {
        RoutingStrategyInterface strategy = factory.getStrategy(AUTO);

        Model selected = strategy.selectModel(modelType, null);
        List<Model> fallbacks = strategy.getFallbackModels(modelType, null);
        logResult("AUTO", selected, fallbacks);

        assertThat(selected).as("测试数据集内应能选中模型").isNotNull();
        assertThat(selected.getModelKey())
                .as("score 最低的是 %s（10），负对照 score=1 但状态不合法", fastPricey.getModelKey())
                .isEqualTo(fastPricey.getModelKey());
        assertThat(selected.getModelKey())
                .as("选中了 inactive / unhealthy 的行 → status+healthStatus 过滤失效")
                .isNotIn(negInactive.getModelKey(), negUnhealthy.getModelKey());

        assertThat(fallbacks)
                .extracting(Model::getModelKey)
                .as("AUTO fallback 顺序 = score ASC 跳过主选：cheap(20), mid(30), untested(60)")
                .containsExactly(cheap.getModelKey(), mid.getModelKey(), untested.getModelKey());
    }

    @Test
    @DisplayName("COST_FIRST · 按 (inputPrice + outputPrice) ASC 选中单价和最低的模型")
    void costFirst_picksCheapest() {
        RoutingStrategyInterface strategy = factory.getStrategy(COST_FIRST);

        Model selected = strategy.selectModel(modelType, null);
        List<Model> fallbacks = strategy.getFallbackModels(modelType, null);
        logResult("COST_FIRST", selected, fallbacks);

        assertThat(selected).as("测试数据集内应能选中模型").isNotNull();
        assertThat(selected.getModelKey())
                .as("0.001+0.001=0.002 最便宜的是 %s（负对照是 0，若被选中说明过滤失效）", cheap.getModelKey())
                .isEqualTo(cheap.getModelKey());
        assertThat(selected.getModelKey())
                .as("选中了 inactive / unhealthy 的行 → 过滤失效")
                .isNotIn(negInactive.getModelKey(), negUnhealthy.getModelKey());

        assertThat(fallbacks)
                .extracting(Model::getModelKey)
                .as("COST fallback 顺序 = 单价和 ASC 跳过主选：untested(0.003), mid(0.02), fast-pricey(0.1)")
                .containsExactly(untested.getModelKey(), mid.getModelKey(), fastPricey.getModelKey());
    }

    @Test
    @DisplayName("LATENCY_FIRST · 按 avgLatency ASC 选中延迟最低的模型，avgLatency=0 的排到最后")
    void latencyFirst_picksLowestLatency() {
        RoutingStrategyInterface strategy = factory.getStrategy(LATENCY_FIRST);

        Model selected = strategy.selectModel(modelType, null);
        List<Model> fallbacks = strategy.getFallbackModels(modelType, null);
        logResult("LATENCY_FIRST", selected, fallbacks);

        assertThat(selected).as("测试数据集内应能选中模型").isNotNull();
        assertThat(selected.getModelKey())
                .as("100ms 最低的是 %s（负对照是 50ms，若被选中说明过滤失效）", fastPricey.getModelKey())
                .isEqualTo(fastPricey.getModelKey());

        // 主选与 AUTO 撞车（都是 fast-pricey），所以"两个策略"只有靠 fallback 顺序才能区分：
        // AUTO 的 fallback 是 [cheap, mid, untested]，LATENCY 的是 [mid, cheap, untested]。
        // 只断言主选的话，这两个用例里其实有一个是"没在测"的。
        List<String> fallbackKeys = fallbacks.stream().map(Model::getModelKey).toList();
        assertThat(fallbackKeys)
                .as("LATENCY fallback = 有效延迟 ASC 跳过主选：mid(400), cheap(900), untested(→999999)")
                .containsExactly(mid.getModelKey(), cheap.getModelKey(), untested.getModelKey());

        // avgLatency=0 的语义：CASE 把它抬到 999999，于是"还没测过延迟"的模型永远垫底。
        // 注意 healthStatus=unknown 的模型延迟默认就是 0 —— 新接入的模型必然最后才被选。
        assertThat(fallbackKeys).as("avgLatency=0 的模型必须垫底，而不是被当成「0ms 最快」")
                .endsWith(untested.getModelKey());
    }

    @Test
    @DisplayName("ROUND_ROBIN · 按 priority DESC, id ASC 循环，6 次覆盖 4 个模型并回到开头")
    void roundRobin_cyclesInPriorityThenIdOrder() {
        RoutingStrategyInterface strategy = factory.getStrategy(ROUND_ROBIN);

        List<String> expected = List.of(
                fastPricey.getModelKey(),   // priority 5
                mid.getModelKey(),          // priority 3
                untested.getModelKey(),     // priority 2
                cheap.getModelKey(),        // priority 1
                fastPricey.getModelKey(),   // 第 5 次回到开头 → 证明取模生效
                mid.getModelKey());         // 第 6 次继续接上

        List<String> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            Model selected = strategy.selectModel(modelType, null);
            assertThat(selected).as("第 %d 次轮询不应返回 null", i + 1).isNotNull();
            actual.add(selected.getModelKey());
        }
        log.info("[ROUND_ROBIN·{}] {} 次轮询 = {}", runId, expected.size(), brief(actual));

        assertThat(actual).as("轮询顺序应为 priority DESC 后 id ASC").isEqualTo(expected);
        assertThat(actual).as("负对照：inactive / unhealthy 行的 priority 是 999，出现了说明过滤失效")
                .doesNotContain(negInactive.getModelKey(), negUnhealthy.getModelKey());
    }

    @Test
    @DisplayName("ROUND_ROBIN · 优先级并列时按 id ASC（先入库的先被选中），且计数器按 modelType 分桶")
    void roundRobin_samePriority_breaksTieByIdAsc() {
        // 换一个 modelType：轮询计数器是按 modelType 分桶的，天然与其它用例的计数器状态隔离
        String tieModelType = modelType + "-tie";
        Model tieFirst = insert("tie-first", tieModelType, "active", "healthy", "50", "0.01", "0.01", 300, 7);
        Model tieSecond = insert("tie-second", tieModelType, "active", "healthy", "50", "0.01", "0.01", 300, 7);

        RoutingStrategyInterface strategy = factory.getStrategy(ROUND_ROBIN);
        Model r1 = strategy.selectModel(tieModelType, null);
        Model r2 = strategy.selectModel(tieModelType, null);
        Model r3 = strategy.selectModel(tieModelType, null);
        log.info("[ROUND_ROBIN·并列·{}] 3 次轮询 = {} → {} → {}（期望 {} → {} → {}）",
                runId, brief(r1), brief(r2), brief(r3),
                brief(tieFirst), brief(tieSecond), brief(tieFirst));

        assertThat(r1).as("第 1 次不应返回 null").isNotNull();
        assertThat(r1.getModelKey())
                .as("priority 并列 → 先插入的 id 更小，先被选中").isEqualTo(tieFirst.getModelKey());
        assertThat(r2).as("第 2 次不应返回 null").isNotNull();
        assertThat(r2.getModelKey()).isEqualTo(tieSecond.getModelKey());
        assertThat(r3).as("第 3 次不应返回 null").isNotNull();
        assertThat(r3.getModelKey())
                .as("两个模型 → 轮询周期为 2").isEqualTo(tieFirst.getModelKey());
    }

    @Test
    @DisplayName("FIXED · 按 modelKey 精确匹配，绕过所有排序")
    void fixed_returnsExactModel() {
        RoutingStrategyInterface strategy = factory.getStrategy(FIXED);

        Model selected = strategy.selectModel(modelType, mid.getModelKey());
        List<Model> fallbacks = strategy.getFallbackModels(modelType, mid.getModelKey());
        log.info("[FIXED·{}] 指定={} → 主选={} | fallback={}",
                runId, brief(mid), brief(selected), brief(fallbacks.stream().map(Model::getModelKey).toList()));

        assertThat(selected).as("指定的模型存在且可用，应精确命中").isNotNull();
        assertThat(selected.getId())
                .as("要的是那一行本身，而不是「最像」的一行：mid 的 score=30 排在第三，"
                        + "若策略没绕过排序会选到 fast-pricey")
                .isEqualTo(mid.getId());

        assertThat(fallbacks)
                .extracting(Model::getModelKey)
                .as("fallback = 候选全集按 priority DESC，再排掉被指定的 mid")
                .containsExactly(fastPricey.getModelKey(), untested.getModelKey(), cheap.getModelKey());
    }

    // ------------------------------------------------------------------ 不变量与负对照

    @Test
    @DisplayName("不变量 · fallback 列表绝不能包含已被选中的主选模型")
    void fallback_neverContainsSelectedModel() {
        for (String type : List.of(AUTO, COST_FIRST, LATENCY_FIRST)) {
            RoutingStrategyInterface strategy = factory.getStrategy(type);

            Model selected = strategy.selectModel(modelType, null);
            List<Model> fallbacks = strategy.getFallbackModels(modelType, null);
            logResult(type, selected, fallbacks);

            assertThat(selected).as("[%s] 应能选中模型", type).isNotNull();
            assertThat(fallbacks)
                    .as("[%s] 候选 %d 个、主选 1 个，fallback 应为 %d 个", type, AVAILABLE, AVAILABLE - 1)
                    .hasSize(AVAILABLE - 1);
            // 这三个策略的排序键都没有 tie-breaker（没带 id），并列时两次独立查询的顺序
            // 不保证一致，skip(1) 就可能跳掉"不是主选的那一个" → fallback 里出现主选。
            assertThat(fallbacks).extracting(Model::getModelKey)
                    .as("[%s] fallback 里出现了主选 %s —— selectModel 与 getFallbackModels "
                            + "两次查询的排序不一致", type, selected.getModelKey())
                    .doesNotContain(selected.getModelKey());
        }
    }

    @Test
    @DisplayName("负对照 · 5 个策略都不得选中 inactive / unhealthy 的模型")
    void allStrategies_neverPickInactiveOrUnhealthy() {
        List<String> banned = List.of(negInactive.getModelKey(), negUnhealthy.getModelKey());

        for (String type : List.of(AUTO, COST_FIRST, LATENCY_FIRST, ROUND_ROBIN)) {
            RoutingStrategyInterface strategy = factory.getStrategy(type);
            // 轮询有状态，多跑几轮把 4 个位置都覆盖到，才能说明"每个位置都过滤了"
            List<String> picks = new ArrayList<>();
            for (int round = 0; round < AVAILABLE; round++) {
                Model selected = strategy.selectModel(modelType, null);
                assertThat(selected).as("[%s] 第 %d 轮不应返回 null", type, round + 1).isNotNull();
                picks.add(selected.getModelKey());
            }
            // 把 4 个位置全打出来：过滤失效时能直接看出是哪一轮漏了负对照
            log.info("[{}·{}] 连续 {} 轮选路 = {}", type.toUpperCase(), runId, AVAILABLE, brief(picks));

            assertThat(picks).as("[%s] 有轮次选中了 inactive / unhealthy 的模型", type)
                    .doesNotContainAnyElementsOf(banned);
        }

        // FIXED 靠 modelKey 直查，同样受 status / healthStatus 过滤约束（详见下面两条边界用例）
        Model viaFixed = factory.getStrategy(FIXED).selectModel(modelType, mid.getModelKey());
        log.info("[FIXED·{}] 指定可用模型 {} → {}", runId, brief(mid), brief(viaFixed));
        assertThat(viaFixed).as("FIXED 指定可用模型时应命中").isNotNull();
        assertThat(viaFixed.getModelKey()).isNotIn(banned);
    }

    // ------------------------------------------------------------------ 边界

    @Test
    @DisplayName("FIXED · requestedModel 为空时返回 null（不猜、不兜底）")
    void fixed_returnsNullWhenModelKeyBlank() {
        RoutingStrategyInterface strategy = factory.getStrategy(FIXED);

        Model forNull = strategy.selectModel(modelType, null);
        Model forEmpty = strategy.selectModel(modelType, "");
        Model forBlank = strategy.selectModel(modelType, "   ");
        log.info("[FIXED·空key·{}] null→{} | \"\"→{} | \"   \"→{}",
                runId, brief(forNull), brief(forEmpty), brief(forBlank));

        assertThat(forNull).isNull();
        assertThat(forEmpty).isNull();
        assertThat(forBlank).isNull();
    }

    @Test
    @DisplayName("FIXED · 指定了已停用 / 不健康的模型时必须返回 null，而不是忽略状态照给")
    void fixed_returnsNullWhenModelInactive() {
        RoutingStrategyInterface strategy = factory.getStrategy(FIXED);

        Model viaInactive = strategy.selectModel(modelType, negInactive.getModelKey());
        Model viaUnhealthy = strategy.selectModel(modelType, negUnhealthy.getModelKey());
        log.info("[FIXED·非法目标·{}] 指定 {} → {} | 指定 {} → {}",
                runId, brief(negInactive), brief(viaInactive), brief(negUnhealthy), brief(viaUnhealthy));

        assertThat(viaInactive)
                .as("status=inactive 的模型即使 modelKey 对上了也不能给").isNull();
        assertThat(viaUnhealthy)
                .as("healthStatus=unhealthy 的模型即使 modelKey 对上了也不能给").isNull();
    }

    // ------------------------------------------------------------------ 已知缺陷（断言的是当前行为，不是期望语义）

    @Test
    @DisplayName("FIXED · 已知缺陷：requestedModel 为空时 getFallbackModels 会白丢优先级最高的模型")
    void fixed_fallbackWithBlankRequestedModel_documentsWart() {
        RoutingStrategyInterface strategy = factory.getStrategy(FIXED);

        Model selected = strategy.selectModel(modelType, null);
        List<String> fallbackKeys = strategy.getFallbackModels(modelType, null).stream()
                .map(Model::getModelKey).toList();
        log.info("[FIXED·缺陷·{}] 主选={} | fallback={} ← 候选共 {} 个，{} 被凭空丢掉",
                runId, brief(selected), brief(fallbackKeys), AVAILABLE, brief(fastPricey));

        assertThat(selected)
                .as("前置：requestedModel 为空时 selectModel 返回 null，也就是「没有第一个被选中」")
                .isNull();

        // ⚠️ 下面断言的是【当前实现的行为】，不是期望语义。
        // FixedRoutingStrategy.getFallbackModels 里那句 skip(1) 的前提是"selectModel 已经拿走了第一个"，
        // 但上一行刚刚证明这个前提不成立 → priority 最高的 fast-pricey 被凭空丢掉。
        // 该修的是实现：requestedModel 为空时应走"全量候选"分支，而不是 skip(1)。
        assertThat(fallbackKeys)
                .as("候选明明有 %d 个，却因为 skip(1) 只返回 %d 个", AVAILABLE, AVAILABLE - 1)
                .hasSize(AVAILABLE - 1)
                .doesNotContain(fastPricey.getModelKey());
    }

    @Test
    @DisplayName("ROUND_ROBIN · 已知耦合：getFallbackModels 必须先调 selectModel，否则会跳过第一个模型")
    void roundRobin_fallbackWithoutPriorSelect_documentsCoupling() {
        RoutingStrategyInterface strategy = factory.getStrategy(ROUND_ROBIN);

        // 没先 selectModel 就直接要 fallback：counterMap 里还没有这个 modelType 的计数器
        List<String> withoutPriorSelect = strategy.getFallbackModels(modelType, null).stream()
                .map(Model::getModelKey).toList();

        // 正确调用顺序：先 selectModel 再 getFallbackModels（ChatServiceImpl 里就是这个顺序）
        Model selected = strategy.selectModel(modelType, null);
        List<String> afterSelect = strategy.getFallbackModels(modelType, null).stream()
                .map(Model::getModelKey).toList();

        log.info("[ROUND_ROBIN·耦合·{}] 未先选 → {} | 先选({}) 后 → {}",
                runId, brief(withoutPriorSelect), brief(selected), brief(afterSelect));

        // ⚠️ 当前实现：currentIndex 在计数器不存在时返回 0，等价于"主选是下标 0 的模型"，
        // 于是 priority 最高的 fast-pricey 被 skip 掉 —— 可这一轮其实什么都没选。
        assertThat(withoutPriorSelect)
                .as("候选 %d 个却只返回 %d 个，且丢了 %s", AVAILABLE, AVAILABLE - 1, fastPricey.getModelKey())
                .hasSize(AVAILABLE - 1)
                .doesNotContain(fastPricey.getModelKey());

        assertThat(selected).isNotNull();
        assertThat(selected.getModelKey()).as("先 selectModel，主选就是 priority 最高的那个")
                .isEqualTo(fastPricey.getModelKey());

        assertThat(afterSelect).as("此时 fallback 从主选的下一个开始，同样不含主选")
                .hasSize(AVAILABLE - 1)
                .doesNotContain(fastPricey.getModelKey())
                .containsExactly(mid.getModelKey(), untested.getModelKey(), cheap.getModelKey());
    }

    // ------------------------------------------------------------------ 工厂

    @Test
    @DisplayName("FACTORY · 5 个策略类型全部可解析，未注册的类型回退到 auto")
    void factory_resolvesAllTypesAndFallsBackToAuto() {
        List<String> allTypes = List.of(AUTO, COST_FIRST, LATENCY_FIRST, ROUND_ROBIN, FIXED);

        List<String> resolved = allTypes.stream().map(type -> factory.getStrategy(type).getStrategyType()).toList();
        String unknownFallback = factory.getStrategy("no_such_strategy").getStrategyType();
        log.info("[FACTORY·{}] 请求={} → 解析={} | 未知类型 → {}",
                runId, allTypes, resolved, unknownFallback);

        assertThat(resolved)
                .as("5 个策略必须都被 @PostConstruct 注册进 Map，否则某个策略会静默降级成 AUTO")
                .containsExactlyElementsOf(allTypes);

        assertThat(unknownFallback)
                .as("未注册的策略类型应回退到 AUTO").isEqualTo(AUTO);
        assertThat(factory.getStrategy(null).getStrategyType())
                .as("null 也应回退到 AUTO，而不是 NPE").isEqualTo(AUTO);
        assertThat(factory.getStrategy("").getStrategyType()).isEqualTo(AUTO);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 每个用例打印且<b>只打印一行</b>「实际结果」。
     *
     * <p>刻意放在断言之前：断言挂了这一行也已经输出，能看到当时到底选了什么。
     *
     * @param fallbacks 传 null 表示本用例没算 fallback，会打成 {@code n/a}（与"算出来是空"区分开）
     */
    private void logResult(String tag, Model selected, List<Model> fallbacks) {
        List<String> keys = fallbacks == null ? null : fallbacks.stream().map(Model::getModelKey).toList();
        // 统一大写：调用方有的传枚举原值（auto）、有的传字面量（AUTO），不统一的话按 tag 过滤会漏行
        log.info("[{}·{}] 主选={} | fallback={}", tag.toUpperCase(), runId, brief(selected), brief(keys));
    }

    /** 日志用：去掉 {@code rt-<runId>-} 前缀，只留业务后缀，控制台上一眼认出是哪一行种子 */
    private String brief(String modelKey) {
        if (modelKey == null) {
            return "<null>";
        }
        return modelKey.replace(KEY_PREFIX + runId + "-", "");
    }

    private String brief(Model model) {
        return model == null ? "<null>" : brief(model.getModelKey());
    }

    /** null → {@code n/a}（没算），空列表 → {@code []}（算了但没有）。两者语义不同，不能混 */
    private String brief(List<String> modelKeys) {
        if (modelKeys == null) {
            return "n/a";
        }
        if (modelKeys.isEmpty()) {
            return "[]";
        }
        return modelKeys.stream().map(this::brief).collect(Collectors.joining(" → "));
    }

    /**
     * 物理删除测试种子 —— 本类唯一"绕开 ORM"的地方，但它是必要的。
     *
     * <p>{@code modelMapper.deleteById()} 是逻辑删除（{@code UPDATE model SET isDelete=1}），
     * 行会永久堆在表里，每跑一次测试攒一批，得手工 {@code DELETE}。
     *
     * <p>WHERE 写两个条件，双保险不误删真实模型：
     * <ol>
     *   <li>{@code modelName = 'routing-test'} —— 只有本类造的种子带这个标记</li>
     *   <li>{@code modelKey LIKE ?} —— 由调用方决定圈本次运行还是圈全部残留</li>
     * </ol>
     *
     * @return 实际删除行数；失败不抛（清理失败不应让一个本来绿了的用例变红），只告警
     */
    private int physicallyDelete(String modelKeyPattern) {
        String sql = "DELETE FROM `model` WHERE modelName = ? AND modelKey LIKE ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SEED_MARKER);
            ps.setString(2, modelKeyPattern);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("物理清理测试模型失败（pattern={}），本次残留需手工清理："
                    + "DELETE FROM `model` WHERE modelName = 'routing-test'", modelKeyPattern, e);
            return 0;
        }
    }

    /**
     * 用 Mapper 直连造数据 —— 不要用被测的 service 造数据（否则测的是自己）。
     *
     * @param keySuffix modelKey 的业务后缀，真正的 modelKey 会拼上本次运行的 runId
     */
    private Model insert(String keySuffix, String mType, String status, String health,
                         String score, String inPrice, String outPrice, int avgLatency, int priority) {
        Model model = Model.builder()
                // providerId 是 NOT NULL 且表上没有外键，测试不关心归属，给个占位值即可
                .providerId(1L)
                // 必须带 runId：uk_modelKey 是唯一索引，而逻辑删除的行仍占着唯一键
                .modelKey(KEY_PREFIX + runId + "-" + keySuffix)
                // 清理时靠这个标记圈定"是测试造的行"，见 SEED_MARKER
                .modelName(SEED_MARKER)
                .modelType(mType)
                .status(status)
                .healthStatus(health)
                .score(new BigDecimal(score))
                .inputPrice(new BigDecimal(inPrice))
                .outputPrice(new BigDecimal(outPrice))
                .avgLatency(avgLatency)
                .priority(priority)
                .build();

        // 必须 insertSelective：insert() 会把 null 写进 createTime / updateTime 这类
        // "NOT NULL DEFAULT CURRENT_TIMESTAMP" 列，显式 NULL 会覆盖默认值直接报错
        modelMapper.insertSelective(model);
        assertThat(model.getId()).as("种子 %s 应回填自增 id", model.getModelKey()).isNotNull();

        return model;
    }
}
