package com.lilac.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.lilac.adapter.ModelAdapter;
import com.lilac.adapter.ModelAdapterFactory;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.domain.entity.RequestLog;
import com.lilac.enums.HealthStatusEnum;
import com.lilac.enums.ModelStatusEnum;
import com.lilac.enums.ProbeFailureKind;
import com.lilac.enums.ProviderStatusEnum;
import com.lilac.mapper.RequestLogMapper;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.scoring.CompositeScorer;
import com.lilac.scoring.ScoringBounds;
import com.lilac.scoring.ScoringContext;
import com.lilac.scoring.ScoringMath;
import com.lilac.service.HealthCheckService;
import com.lilac.service.ModelProviderService;
import com.lilac.service.ModelService;
import com.lilac.task.ProbeResult;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 健康检查服务实现
 */
@Service
@Slf4j
public class HealthCheckServiceImpl implements HealthCheckService {

    @Resource
    private ModelProviderService modelProviderService;
    @Resource
    private ModelService modelService;
    @Resource
    private RequestLogMapper requestLogMapper;
    @Resource
    private ModelAdapterFactory adapterFactory;
    @Resource
    private AIMetricsCollector aiMetricsCollector;
    @Resource
    private ProbeWindowStore probeWindowStore;
    @Resource
    private CompositeScorer compositeScorer;

    // 统计请求日志的时间范围（小时）
    private static final int STATS_HOURS = 24;
    // 裸 GET 探测的超时时间（秒）
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    // probe 开关：关掉后退回裸 GET（只判可达性），便于二分定位与省费用
    @Value("${lilac.health-check.real-probe.enabled:true}")
    private boolean realProbeEnabled = true;
    // probe 的整体超时（秒）。比裸 GET 的 5s 宽：要留出模型生成首 token 的时间
    @Value("${lilac.health-check.real-probe.timeout-seconds:10}")
    private int realProbeTimeoutSeconds = 10;
    // 探针 prompt 的输出上限，取最小值以压低费用（低成本探针）
    @Value("${lilac.health-check.real-probe.max-tokens:4}")
    private int realProbeMaxTokens = 4;

    // 探针 prompt 内容。只要一个 token 级别的回复即可证明链路通
    private static final String PROBE_PROMPT = "hi";
    // 探针使用的模型类型：只有 chat 模型才能验证「真的能对话」
    private static final String PROBE_MODEL_TYPE = "chat";

    // 综合得分各维度权重已迁到 lilac.scoring.CompositeScorer 的配置项（lilac.routing.scoring.weights.*）

    /**
     * 样本量下限：24h 窗口内样本少于这个数时，不据成功率改写健康状态。
     */
    @Value("${lilac.health-check.min-samples:5}")
    private int minSamples = 5;

    /**
     * 指标 EMA 平滑系数：新样本占的权重。1.0 = 不平滑（退回改造前行为），0.3 = 新样本占三成。
     * 调小更稳但更迟钝；调大更灵敏但更容易被单次波动带偏。
     */
    @Value("${lilac.health-check.ema-alpha:0.3}")
    private double emaAlpha = 0.3;

    /**
     * 检查所有提供者的健康状态
     */
    @Override
    public void checkAllProviders() {
        // 获取所有提供者
        List<ModelProvider> providers = modelProviderService.list();
        if (CollUtil.isEmpty(providers)) {
            log.warn("没有找到任何模型提供者");
            return;
        }
        log.info("开始健康检查，共 {} 个提供者", providers.size());
        // 并行检查所有启用的提供者
        providers.parallelStream()
                .filter(provider -> ProviderStatusEnum.ACTIVE.getValue().equals(provider.getStatus()))
                .forEach(this::checkProviderHealth);
        // 从请求日志同步模型指标
        syncModelMetricsFromRequestLog();
        log.info("健康检查完成");
    }

    /**
     * 检查指定提供者的健康状态
     *
     * @param provider 提供者
     * @return 是否健康
     */
    @Override
    public boolean checkProviderHealth(ModelProvider provider) {
        if (provider == null) {
            return false;
        }
        try {
            // probe：发一次最小对话请求，能同时验证鉴权、模型名与 chat 链路
            ProbeResult result = realProbeEnabled ? realProbe(provider) : null;
            if (result == null) {
                // 没有可探的 chat 模型（或真 probe 被关掉）：退回裸 GET，只能判可达性
                result = fallbackReachabilityProbe(provider);
            }
            // 写入滑动窗口（Redis ZSET），失败会降级成「无样本」而不是让整轮挂掉
            probeWindowStore.record(provider.getId(), result);
            // 窗口成功率：只读一次，避免同一轮里两次 Redis 往返
            BigDecimal successRate = probeWindowStore.successRate(provider.getId());
            // 确定健康状态（探针样本太少时不动状态）
            String healthStatus = resolveHealthStatus(provider.getId(), successRate, probeWindowStore.sampleSize(provider.getId()));
            // 失败也要落库：只更新模型表会让 provider 表一直停在 healthy，
            // 两处状态不一致，排查时按 provider 表看就会得出「一切正常」的错误结论
            modelProviderService.updateHealthStatus(provider.getId(), healthStatus,
                    result.isSuccess() ? result.ttftMillis() : provider.getAvgLatency(), successRate);
            log.debug("提供者 {} 健康检查完成: 成功={}, 失败类型={}, TTFT={}ms, 总耗时={}ms",
                    provider.getDisplayName(), result.isSuccess(), result.failureKind(),
                    result.ttftMillis(), result.totalMillis());
            return result.isSuccess();
        } catch (Exception e) {
            // 探测本身抛异常也算这一轮失败；归类后仍走统一判定，避免把限流之类误判成不可达
            ProbeFailureKind kind = ProbeFailureKind.classify(e);
            probeWindowStore.record(provider.getId(), ProbeResult.failure(kind, 0));
            log.error("提供者 {} 健康检查异常（归类={}）", provider.getDisplayName(), kind, e);
            return false;
        }
    }

    /**
     * 真 probe：挑该 provider 下一个启用的 chat 模型，发一次最小对话请求并测 TTFT。
     *
     * @return 探测结果；该 provider 没有任何 chat 模型时返回 null（调用方回退裸 GET）
     */
    private ProbeResult realProbe(ModelProvider provider) {
        Model model = pickProbeModel(provider.getId());
        if (model == null) {
            return null;
        }
        long start = System.currentTimeMillis();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        try {
            ModelAdapter adapter = adapterFactory.getAdapter(provider.getProviderName());
            ChatRequest request = buildProbeRequest(model.getModelKey());
            // 取首个非空 chunk 的时间点即 TTFT：这正是用户感知的「等了多久才出字」
            Integer ttft = adapter.invokeStreamChunk(model, provider, request)
                    .filter(chunk -> chunk.hasText() || chunk.hasReasoningContent() || chunk.getCompletionTokens() != null)
                    .next()
                    .map(chunk -> (int) (System.currentTimeMillis() - start))
                    .timeout(Duration.ofSeconds(realProbeTimeoutSeconds))
                    .onErrorResume(e -> {
                        streamError.set(e);
                        return Mono.empty();
                    })
                    .block();
            int total = (int) (System.currentTimeMillis() - start);
            Throwable error = streamError.get();
            if (error != null) {
                ProbeFailureKind kind = ProbeFailureKind.classify(error);
                aiMetricsCollector.recordProbeFailure(provider.getProviderName(), model.getModelKey(), kind.name());
                log.warn("提供者 {} 真探针失败（模型 {}，归类={}，耗时 {}ms）：{}",
                        provider.getDisplayName(), model.getModelKey(), kind, total, error.getMessage());
                return ProbeResult.failure(kind, total);
            }
            if (ttft == null) {
                // 流正常结束却一个可用 chunk 都没有：拿到了 200 但没有可用内容
                log.warn("提供者 {} 探针未拿到任何响应块（模型 {}），总耗时 {}ms",
                        provider.getDisplayName(), model.getModelKey(), total);
                return ProbeResult.failure(ProbeFailureKind.UNKNOWN, total);
            }
            aiMetricsCollector.recordProbeSuccess(provider.getProviderName(), model.getModelKey(), ttft);
            return ProbeResult.success(ttft, total);
        } catch (Exception e) {
            int total = (int) (System.currentTimeMillis() - start);
            ProbeFailureKind kind = ProbeFailureKind.classify(e);
            aiMetricsCollector.recordProbeFailure(provider.getProviderName(), model.getModelKey(), kind.name());
            log.warn("提供者 {} 真探针失败（模型 {}，归类={}，耗时 {}ms）：{}",
                    provider.getDisplayName(), model.getModelKey(), kind, total, e.getMessage());
            return ProbeResult.failure(kind, total);
        }
    }

    /**
     * 裸 GET 兜底：能拿到任意 HTTP 响应即视为可达。
     * 只用于「该 provider 没有 chat 模型可探」的场景，不区分「可达但全错」
     */
    private ProbeResult fallbackReachabilityProbe(ModelProvider provider) {
        long start = System.currentTimeMillis();
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return ProbeResult.failure(ProbeFailureKind.UNKNOWN, 0);
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getBaseUrl()))
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int total = (int) (System.currentTimeMillis() - start);
            // 任意 HTTP 状态码都说明服务可达
            return response.statusCode() > 0
                    ? ProbeResult.success(total, total)
                    : ProbeResult.failure(ProbeFailureKind.UNKNOWN, total);
        } catch (Exception e) {
            log.warn("提供者 {} 健康探测失败: {}: {}", provider.getDisplayName(), e.getClass().getSimpleName(), e.getMessage());
            return ProbeResult.failure(ProbeFailureKind.classify(e), (int) (System.currentTimeMillis() - start));
        }
    }

    /**
     * 挑一个用于探测的模型：优先健康状态可用的，避免专挑一个已知坏的来代表整个 provider
     */
    private Model pickProbeModel(Long providerId) {
        if (providerId == null) {
            return null;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("providerId", providerId)
                .eq("status", ModelStatusEnum.ACTIVE.getValue())
                .eq("modelType", PROBE_MODEL_TYPE);
        List<Model> models = modelService.list(queryWrapper);
        if (CollUtil.isEmpty(models)) {
            return null;
        }
        return models.stream()
                .filter(m -> !HealthStatusEnum.UNHEALTHY.getValue().equals(m.getHealthStatus()))
                .findFirst()
                .orElse(models.get(0));
    }

    private ChatRequest buildProbeRequest(String modelKey) {
        ChatRequest request = new ChatRequest();
        request.setModel(modelKey);
        request.setStream(true);
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent(PROBE_PROMPT);
        request.setMessages(List.of(message));
        // 锁死输出长度：探针只为证明链路通，不需要真的生成内容（低成本探针）
        request.setMaxTokens(realProbeMaxTokens);
        return request;
    }

    /**
     * 从请求日志中同步模型指标
     */
    @Override
    public void syncModelMetricsFromRequestLog() {
        log.info("开始从请求日志同步模型指标");
        // 获取所有启用的模型
        List<Model> activeModels = modelService.getActiveModels();
        if (CollUtil.isEmpty(activeModels)) {
            log.warn("没有找到任何启用的模型");
            return;
        }

        // 计算统计时间范围
        LocalDateTime startTime = LocalDateTime.now().minusHours(STATS_HOURS);
        // 从请求日志聚合各模型的统计数据
        List<ModelStats> modelStatsList = queryModelStatsFromDb(startTime, activeModels);
        // 转换为 Map 便于查找
        Map<Long, ModelStats> modelStatsMap = modelStatsList.stream()
                .collect(Collectors.toMap(s -> s.modelId, s -> s, (a, b) -> a));
        // 对指标进行平滑处理
        smoothMetricsInPlace(activeModels, modelStatsMap);
        // 计算归一化参数（用于综合得分计算）
        NormalizationParams normParams = calculateNormalizationParams(activeModels, modelStatsMap);

        // 更新每个模型的指标
        for (Model model : activeModels) {
            ModelStats stats = modelStatsMap.get(model.getId());
            if (stats == null || stats.sampleCount < minSamples) {
                String reason = stats == null ? "24h 内无请求日志"
                        : "24h 内仅 " + stats.sampleCount + " 条样本（低于下限 " + minSamples + "）";
                String noDataStatus = resolveHealthStatus(model.getProviderId(), null, 0);
                if (!noDataStatus.equals(model.getHealthStatus())) {
                    log.info("模型 {} 健康状态 {} → {}（{}，保留原指标 延迟={}ms 成功率={}%）",
                            model.getModelKey(), model.getHealthStatus(), noDataStatus, reason,
                            model.getAvgLatency(), model.getSuccessRate());
                }
                // 只改健康状态，用读到的原值回写指标，等价于「只更新一列」
                modelService.updateModelMetrics(model.getId(), noDataStatus, model.getAvgLatency(), model.getSuccessRate(), model.getScore());
                continue;
            }
            // 计算综合得分
            BigDecimal score = calculateScore(model, stats, normParams);
            // 根据成功率确定健康状态
            String healthStatus = resolveHealthStatus(model.getProviderId(), stats.rawSuccessRate, stats.sampleCount);
            // 更新模型指标
            modelService.updateModelMetrics(model.getId(), healthStatus, stats.avgLatency, stats.successRate, score);
            log.debug("模型 {} 指标更新: 延迟={}ms（本轮原始 {}）, 成功率={}%（本轮原始 {}）, 样本={}, 得分={}",
                    model.getModelKey(), stats.avgLatency, stats.rawAvgLatency,
                    stats.successRate, stats.rawSuccessRate, stats.sampleCount, score);
        }
        // 同步更新提供者的汇总指标（基于模型统计数据）
        syncProviderMetricsFromModelStats(activeModels, modelStatsMap);
        log.info("模型指标同步完成");
    }

    /**
     * 从请求日志聚合各模型的统计数据
     */
    private List<ModelStats> queryModelStatsFromDb(LocalDateTime startTime, List<Model> activeModels) {
        // modelKey -> modelId 映射
        Map<String, Long> keyToId = activeModels.stream()
                .filter(m -> m.getModelKey() != null)
                .collect(Collectors.toMap(Model::getModelKey, Model::getId, (a, b) -> a));

        QueryWrapper queryWrapper = QueryWrapper.create().ge("createTime", startTime);
        List<RequestLog> logs = requestLogMapper.selectListByQuery(queryWrapper);
        if (CollUtil.isEmpty(logs)) {
            return new ArrayList<>();
        }

        // 按模型名称分组聚合
        Map<String, List<RequestLog>> byModel = logs.stream()
                .filter(l -> l.getModelName() != null)
                .collect(Collectors.groupingBy(RequestLog::getModelName));

        List<ModelStats> result = new ArrayList<>();
        for (Map.Entry<String, List<RequestLog>> entry : byModel.entrySet()) {
            Long modelId = keyToId.get(entry.getKey());
            if (modelId == null) {
                continue;
            }
            List<RequestLog> list = entry.getValue();
            long total = list.size();
            long success = list.stream().filter(l -> "success".equals(l.getStatus())).count();

            ModelStats stats = new ModelStats();
            stats.modelId = modelId;
            // 中位数而非均值：一个 1352s 的极值能把 16 个样本的均值拉高 60 倍（§3.4）
            stats.avgLatency = LatencyAggregator.medianLatency(list);
            stats.successRate = BigDecimal.valueOf(success * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
            // 原始值同时留一份给健康判定：判死不能吃平滑值，否则「刚坏掉」会被拖慢好几轮
            stats.rawAvgLatency = stats.avgLatency;
            stats.rawSuccessRate = stats.successRate;
            stats.sampleCount = (int) total;
            result.add(stats);
        }
        return result;
    }

    /**
     * 计算归一化参数（成本、延迟、优先级的最大最小值）
     */
    private NormalizationParams calculateNormalizationParams(List<Model> models, Map<Long, ModelStats> statsMap) {
        NormalizationParams params = new NormalizationParams();
        if (CollUtil.isEmpty(models)) {
            params.minCost = BigDecimal.ZERO;
            params.maxCost = BigDecimal.ZERO;
            return params;
        }

        BigDecimal minCost = null;
        BigDecimal maxCost = null;
        int minLatency = Integer.MAX_VALUE;
        int maxLatency = Integer.MIN_VALUE;
        int minPriority = Integer.MAX_VALUE;
        int maxPriority = Integer.MIN_VALUE;

        for (Model model : models) {
            BigDecimal cost = ScoringMath.nullToZero(model.getInputPrice()).add(ScoringMath.nullToZero(model.getOutputPrice()));
            if (minCost == null || cost.compareTo(minCost) < 0) {
                minCost = cost;
            }
            if (maxCost == null || cost.compareTo(maxCost) > 0) {
                maxCost = cost;
            }

            ModelStats stats = statsMap.getOrDefault(model.getId(), new ModelStats());
            int latency = stats.avgLatency > 0 ? stats.avgLatency : 5000;
            minLatency = Math.min(minLatency, latency);
            maxLatency = Math.max(maxLatency, latency);

            int priority = model.getPriority() != null ? model.getPriority() : 0;
            minPriority = Math.min(minPriority, priority);
            maxPriority = Math.max(maxPriority, priority);
        }

        params.minCost = minCost;
        params.maxCost = maxCost;
        params.minLatency = minLatency;
        params.maxLatency = maxLatency;
        params.minPriority = minPriority;
        params.maxPriority = maxPriority;
        return params;
    }

    /**
     * 基于模型统计数据同步提供者的汇总指标
     */
    private void syncProviderMetricsFromModelStats(List<Model> models, Map<Long, ModelStats> statsMap) {
        // providerId -> 该提供者下各模型的统计
        Map<Long, List<ModelStats>> byProvider = new HashMap<>();
        for (Model model : models) {
            ModelStats stats = statsMap.get(model.getId());
            if (stats == null || model.getProviderId() == null || stats.sampleCount < minSamples) {
                continue;
            }
            byProvider.computeIfAbsent(model.getProviderId(), k -> new ArrayList<>()).add(stats);
        }

        for (Map.Entry<Long, List<ModelStats>> entry : byProvider.entrySet()) {
            List<ModelStats> list = entry.getValue();
            int avgLatency = (int) Math.round(list.stream().mapToInt(s -> s.avgLatency).average().orElse(0));
            BigDecimal avgSuccessRate = list.stream()
                    .map(s -> s.successRate)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP);
            // 与模型侧同源：探测失败优先按分类判定，不能被 request_log 的成功率盖过去。
            // 否则一个 Key 失效的 provider 会因为在窗口内「没有失败日志」而算出 100% → 被判 healthy
            String healthStatus = resolveProviderHealthStatus(entry.getKey(), avgSuccessRate);
            modelProviderService.updateHealthStatus(entry.getKey(), healthStatus, avgLatency, avgSuccessRate);
        }
    }

    /**
     * provider 汇总健康状态：探测结论优先（与模型侧同源），探测给不出意见时才看聚合成功率。
     */
    private String resolveProviderHealthStatus(Long providerId, BigDecimal avgSuccessRate) {
        ProbeResult probe = probeWindowStore.latest(providerId);
        if (probe != null && !probe.isSuccess()) {
            return probe.failureKind().shouldExcludeFromRouting()
                    ? HealthStatusEnum.UNHEALTHY.getValue()
                    : HealthStatusEnum.DEGRADED.getValue();
        }
        return determineHealthStatus(avgSuccessRate);
    }

    /**
     * 根据成功率确定健康状态（80 以上健康、50 以上降级、否则不健康）
     */
    private String determineHealthStatus(BigDecimal successRate) {
        double rate = successRate.doubleValue();
        if (rate >= 80.0) {
            return HealthStatusEnum.HEALTHY.getValue();
        } else if (rate >= 50.0) {
            return HealthStatusEnum.DEGRADED.getValue();
        } else {
            return HealthStatusEnum.UNHEALTHY.getValue();
        }
    }

    /**
     * 模型健康状态：探测是硬门槛，只有探测通过时 request_log 成功率才决定 healthy 还是 degraded。
     *
     * @param successRate 24h 窗口成功率；窗口内没有日志时传 null
     * @param sampleCount 该成功率的样本数；小于 {@link #minSamples} 时不据它判状态
     */
    String resolveHealthStatus(Long providerId, BigDecimal successRate, int sampleCount) {
        ProbeResult probe = probeWindowStore.latest(providerId);
        if (probe != null && !probe.isSuccess()) {
            return probe.failureKind().shouldExcludeFromRouting()
                    ? HealthStatusEnum.UNHEALTHY.getValue()
                    : HealthStatusEnum.DEGRADED.getValue();
        }
        if (probe == null || successRate == null) {
            // 「没测过」既不能当满分，也不能据此把模型排除出候选池
            return HealthStatusEnum.UNKNOWN.getValue();
        }
        if (sampleCount < minSamples) {
            return HealthStatusEnum.UNKNOWN.getValue();
        }
        // 可达且成功率 >= 50% 才是 healthy，否则是 degraded
        return successRate.doubleValue() >= 50.0 ? determineHealthStatus(successRate) : HealthStatusEnum.DEGRADED.getValue();
    }

    /**
     * 对窗口统计做 EMA 平滑（就地把平滑值写回 stats，原始值留在 rawXxx 字段）。
     */
    private void smoothMetricsInPlace(List<Model> activeModels, Map<Long, ModelStats> modelStatsMap) {
        for (Model model : activeModels) {
            ModelStats stats = modelStatsMap.get(model.getId());
            if (stats == null || stats.sampleCount < minSamples) {
                continue;
            }
            int previousLatency = model.getAvgLatency() != null ? model.getAvgLatency() : 0;
            boolean coldStart = previousLatency <= 0;

            stats.avgLatency = EmaSmoother.smoothLatency(previousLatency, stats.rawAvgLatency, emaAlpha);
            stats.successRate = EmaSmoother.smoothSuccessRate(
                    model.getSuccessRate(), stats.rawSuccessRate, emaAlpha, coldStart);

            log.debug("模型 {} EMA 平滑：延迟 {} → {}（本轮 {}），成功率 {} → {}（本轮 {}），α={}",
                    model.getModelKey(), previousLatency, stats.avgLatency, stats.rawAvgLatency,
                    model.getSuccessRate(), stats.successRate, stats.rawSuccessRate, emaAlpha);
        }
    }

    /**
     * 计算模型的综合得分（越低越好）。
     * 维度与权重已抽到 {@link com.lilac.scoring.CompositeScorer}，权重可配。
     */
    private BigDecimal calculateScore(Model model, ModelStats stats, NormalizationParams params) {
        ScoringBounds bounds = new ScoringBounds(
                params.minCost, params.maxCost,
                params.minLatency, params.maxLatency,
                params.minPriority, params.maxPriority);
        ScoringContext context = new ScoringContext(bounds, stats.avgLatency, stats.successRate);
        return compositeScorer.score(model, context);
    }

    /**
     * 单个模型的统计数据
     */
    static class ModelStats {
        Long modelId;
        /** 平滑后的延迟，用于算 score（排序） */
        int avgLatency = 0;
        /** 平滑后的成功率，用于算 score（排序） */
        BigDecimal successRate;
        /** 本轮窗口的原始延迟中位数，用于健康判定（判死不能平滑，否则会拖慢故障发现） */
        int rawAvgLatency = 0;
        /** 本轮窗口的原始成功率，用于健康判定 */
        BigDecimal rawSuccessRate;
        /** 该成功率的样本数，用于样本量下限判断 */
        int sampleCount = 0;
    }

    /**
     * 综合得分计算所需的归一化参数
     */
    static class NormalizationParams {
        BigDecimal minCost;
        BigDecimal maxCost;
        int minLatency;
        int maxLatency;
        int minPriority;
        int maxPriority;
    }
}
