package com.lilac.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.domain.entity.RequestLog;
import com.lilac.enums.HealthStatusEnum;
import com.lilac.enums.ProviderStatusEnum;
import com.lilac.mapper.RequestLogMapper;
import com.lilac.service.HealthCheckService;
import com.lilac.service.ModelProviderService;
import com.lilac.service.ModelService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.ConcurrentHashMap;
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

    // 存储最近的健康检查结果（滑动窗口）
    private final ConcurrentHashMap<Long, List<Boolean>> healthHistoryMap = new ConcurrentHashMap<>();
    // 健康检查历史记录最大数量
    private static final int MAX_HISTORY_SIZE = 100;
    // 统计请求日志的时间范围（小时）
    private static final int STATS_HOURS = 24;
    // 健康探测的超时时间（秒）
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    // 综合得分各维度权重（合计为 1.0）
    private static final double COST_WEIGHT = 0.3;
    private static final double LATENCY_WEIGHT = 0.3;
    private static final double SUCCESS_RATE_WEIGHT = 0.3;
    private static final double PRIORITY_WEIGHT = 0.1;

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
            // 测量延迟
            long startTime = System.currentTimeMillis();
            boolean isHealthy = sendHealthCheckRequest(provider);
            long endTime = System.currentTimeMillis();
            int latency = (int) (endTime - startTime);
            // 记录健康检查结果
            recordHealthCheckResult(provider.getId(), isHealthy);
            // 计算成功率
            BigDecimal successRate = calculateSuccessRate(provider.getId());
            // 确定健康状态
            String healthStatus = determineHealthStatus(successRate);
            // 更新提供者的健康信息
            modelProviderService.updateHealthStatus(provider.getId(), healthStatus, latency, successRate);
            log.debug("提供者 {} 健康检查完成: 健康={}, 延迟={}ms, 成功率={}%", provider.getDisplayName(), isHealthy, latency, successRate);
            return isHealthy;
        } catch (Exception e) {
            log.error("提供者 {} 健康检查失败", provider.getDisplayName(), e);
            // 记录失败
            recordHealthCheckResult(provider.getId(), false);
            BigDecimal successRate = calculateSuccessRate(provider.getId());
            // 更新为不健康状态
            modelProviderService.updateHealthStatus(provider.getId(), HealthStatusEnum.UNHEALTHY.getValue(), 0, successRate);
            return false;
        }
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
        // 计算归一化参数（用于综合得分计算）
        NormalizationParams normParams = calculateNormalizationParams(activeModels, modelStatsMap);

        // 更新每个模型的指标
        for (Model model : activeModels) {
            ModelStats stats = modelStatsMap.getOrDefault(model.getId(), new ModelStats());
            // 计算综合得分
            BigDecimal score = calculateScore(model, stats, normParams);
            // 根据成功率确定健康状态
            String healthStatus = determineHealthStatusBySuccessRate(stats.successRate);
            // 更新模型指标
            modelService.updateModelMetrics(model.getId(), healthStatus, stats.avgLatency, stats.successRate, score);
            log.debug("模型 {} 指标更新: 延迟={}ms, 成功率={}%, 得分={}", model.getModelKey(), stats.avgLatency, stats.successRate, score);
        }
        // 同步更新提供者的汇总指标（基于模型统计数据）
        syncProviderMetricsFromModelStats(activeModels, modelStatsMap);
        log.info("模型指标同步完成");
    }

    /**
     * 发送健康探测请求：能拿到任意 HTTP 响应即视为可达
     */
    private boolean sendHealthCheckRequest(ModelProvider provider) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return false;
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
            // 任意 HTTP 状态码都说明服务可达
            return response.statusCode() > 0;
        } catch (Exception e) {
            log.warn("提供者 {} 健康探测失败: {}", provider.getDisplayName(), e.getMessage());
            return false;
        }
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
            double avgLatency = list.stream()
                    .filter(l -> l.getDuration() != null)
                    .mapToInt(RequestLog::getDuration)
                    .average()
                    .orElse(0);

            ModelStats stats = new ModelStats();
            stats.modelId = modelId;
            stats.avgLatency = (int) Math.round(avgLatency);
            stats.successRate = total == 0
                    ? BigDecimal.valueOf(100.00)
                    : BigDecimal.valueOf(success * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
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
            BigDecimal cost = nullToZero(model.getInputPrice()).add(nullToZero(model.getOutputPrice()));
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
            if (stats == null || model.getProviderId() == null) {
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
            String healthStatus = determineHealthStatus(avgSuccessRate);
            modelProviderService.updateHealthStatus(entry.getKey(), healthStatus, avgLatency, avgSuccessRate);
        }
    }

    /**
     * 记录健康检查结果
     */
    private void recordHealthCheckResult(Long providerId, boolean success) {
        List<Boolean> history = healthHistoryMap.computeIfAbsent(
                providerId,
                k -> new ArrayList<>()
        );
        history.add(success);
        // 只保留最近 100 次
        if (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }
    }

    /**
     * 计算成功率
     */
    private BigDecimal calculateSuccessRate(Long providerId) {
        List<Boolean> history = healthHistoryMap.get(providerId);
        if (history == null || history.isEmpty()) {
            return BigDecimal.valueOf(100.00);
        }
        long successCount = history.stream()
                .filter(result -> result)
                .count();
        double rate = (successCount * 100.0) / history.size();
        return BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 根据成功率确定健康状态
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
     * 根据成功率确定健康状态（与提供者口径一致）
     */
    private String determineHealthStatusBySuccessRate(BigDecimal successRate) {
        return determineHealthStatus(successRate);
    }

    /**
     * 计算模型的综合得分（越低越好）
     */
    private BigDecimal calculateScore(Model model, ModelStats stats, NormalizationParams params) {
        // 1. 成本得分（归一化到 0-1）
        BigDecimal modelCost = nullToZero(model.getInputPrice()).add(nullToZero(model.getOutputPrice()));
        double costScore = normalize(modelCost, params.minCost, params.maxCost);
        // 2. 延迟得分（归一化到 0-1）
        int latency = stats.avgLatency > 0 ? stats.avgLatency : 5000; // 默认5秒
        double latencyScore = normalize(latency, params.minLatency, params.maxLatency);
        // 3. 成功率得分（归一化到 0-1，成功率越高得分越低）
        double successRateScore = 1.0 - (stats.successRate.doubleValue() / 100.0);
        // 4. 优先级得分（归一化到 0-1，优先级越高得分越低）
        int priority = model.getPriority() != null ? model.getPriority() : 0;
        double priorityScore = 1.0 - normalize(priority, params.minPriority, params.maxPriority);
        // 综合得分
        double score = costScore * COST_WEIGHT +
                latencyScore * LATENCY_WEIGHT +
                successRateScore * SUCCESS_RATE_WEIGHT +
                priorityScore * PRIORITY_WEIGHT;
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 归一化整型数值到 0-1 范围
     */
    private double normalize(int value, int min, int max) {
        int range = max - min;
        if (range == 0) {
            return 0.0;
        }
        return (double) (value - min) / range;
    }

    /**
     * 归一化 BigDecimal 数值到 0-1 范围
     */
    private double normalize(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null || min == null || max == null) {
            return 0.0;
        }
        double range = max.subtract(min).doubleValue();
        if (range == 0) {
            return 0.0;
        }
        return value.subtract(min).doubleValue() / range;
    }

    /**
     * null 安全转换：null 视为 0
     */
    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * 单个模型的统计数据
     */
    static class ModelStats {
        Long modelId;
        int avgLatency = 0;
        BigDecimal successRate = BigDecimal.valueOf(100.00);
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
