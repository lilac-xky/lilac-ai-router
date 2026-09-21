package com.lilac.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lilac.domain.dto.billing.CallReservation;
import com.lilac.domain.dto.image.ImageGenerationRequest;
import com.lilac.domain.dto.image.ImageGenerationResponse;
import com.lilac.domain.entity.ImageGenerationRecord;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.ImageGenerationRecordMapper;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.service.*;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片生成服务实现类
 */
@Service
@Slf4j
public class ImageGenerationServiceImpl extends ServiceImpl<ImageGenerationRecordMapper, ImageGenerationRecord> implements ImageGenerationService {

    /**
     * 每张图消耗的 Token 数（平台侧配额计价口径，与模型真实用量无关）
     */
    private static final int TOKENS_PER_IMAGE = 1000;

    @Resource
    private ModelService modelService;
    @Resource
    private ModelProviderService modelProviderService;
    @Resource
    private QuotaService quotaService;
    @Resource
    private BalanceService balanceService;
    @Resource
    private BillingService billingService;
    @Resource
    private CallSettlementService callSettlementService;
    @Resource
    private UserService userService;
    @Resource
    private AIMetricsCollector aiMetricsCollector;
    @Resource
    private ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "qwen-image-plus";
    private static final String DEFAULT_SIZE = "1024*1024";
    private static final int DEFAULT_N = 1;

    /**
     * 生成图片
     *
     * @param request 图片生成请求
     * @param userId 用户ID
     * @param apiKeyId API Key ID
     * @param clientIp 客户端IP
     * @return 图片生成响应
     */
    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request, Long userId, Long apiKeyId, String clientIp) {
        long startTime = System.currentTimeMillis();

        // 参数校验
        if (StrUtil.isBlank(request.getPrompt())) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "提示词不能为空");
        }
        // 检查用户状态
        if (userId != null && userService.isUserDisabled(userId)) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "账号已被禁用，无法使用服务");
        }
        // 设置默认值
        String modelKey = StrUtil.isNotBlank(request.getModel()) ? request.getModel() : DEFAULT_MODEL;
        String size = StrUtil.isNotBlank(request.getSize()) ? request.getSize() : DEFAULT_SIZE;
        int n = 1;  // 固定生成1张

        // 查询模型信息
        Model model = modelService.getByModelKey(modelKey);
        if (model == null || !"image".equals(model.getModelType())) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "模型不存在或不是绘图模型");
        }

        // 查询提供者信息
        ModelProvider provider = modelProviderService.getById(model.getProviderId());
        if (provider == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "模型提供者不存在");
        }

        // 账单来源：区分 API 与网页，对账时按来源汇总要靠它
        String channel = apiKeyId != null ? "API图片生成" : "网页图片生成";

        // 预估费用（绘图模型按张计费）
        BigDecimal estimatedCost = model.getInputPrice() != null
                ? model.getInputPrice().multiply(new BigDecimal(n)) : BigDecimal.ZERO;

        // 前置快照校验：只为尽早给用户一句人话，不是闸门
        if (userId != null) {
            if (!quotaService.checkQuota(userId)) {
                throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "Token配额已用尽，请联系管理员增加配额");
            }
            if (!balanceService.checkBalance(userId, estimatedCost)) {
                throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR,
                        "账户余额不足，生成" + n + "张图片预计需要¥" + estimatedCost + "，请先充值");
            }
        }

        // 真正的闸门：调用前把配额与余额原子占住（同一事务），占不到就不会打到上游
        CallReservation reservation = new CallReservation(
                userId, modelKey, n * TOKENS_PER_IMAGE, estimatedCost, channel);
        callSettlementService.reserve(reservation);

        ImageGenerationResponse response;
        try {
            response = callImageGenerationModel(model, provider, request, size, n);
        } catch (Exception e) {
            // 本方法没有事务，预扣是立即提交的，不写退款就是实打实的白扣
            refundReservation(reservation);
            recordFailure(model, modelKey, request, size, userId, apiKeyId, clientIp, startTime, e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "图片生成失败: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;
        List<ImageGenerationResponse.ImageData> images =
                response.getData() != null ? response.getData() : List.of();
        int actualImageCount = images.size();

        // 按实际返回的张数计价：上游少给图必须退钱，不能按请求的 n 收费
        BigDecimal actualCost = model.getInputPrice() != null
                ? model.getInputPrice().multiply(new BigDecimal(actualImageCount)) : BigDecimal.ZERO;

        // 记录生成成功
        for (ImageGenerationResponse.ImageData imageData : images) {
            ImageGenerationRecord record = ImageGenerationRecord.builder()
                    .userId(userId)
                    .apiKeyId(apiKeyId)
                    .modelId(model.getId())
                    .modelKey(modelKey)
                    .prompt(request.getPrompt())
                    .revisedPrompt(imageData.getRevisedPrompt())
                    .imageUrl(imageData.getUrl())
                    .imageData(imageData.getB64Json())
                    .size(size)
                    .quality(request.getQuality())
                    .status("success")
                    .cost(model.getInputPrice() != null ? model.getInputPrice() : BigDecimal.ZERO)
                    .duration((int) duration)
                    .clientIp(clientIp)
                    .createTime(LocalDateTime.now())
                    .build();
            save(record);
        }

        log.info("图片生成成功：用户 {}, 模型 {}, 请求 {} 张 / 实返 {} 张, 耗时 {}ms",
                userId, modelKey, n, actualImageCount, duration);

        // 结算：少给图则退回差额
        settleReservation(reservation, actualImageCount * TOKENS_PER_IMAGE, actualCost, actualImageCount, n);

        return response;
    }

    /**
     * 结算（成功路径）。吞异常是刻意的：图已经交付，把请求改成失败挽回不了上游成本。
     */
    private void settleReservation(CallReservation reservation, int actualTokens, BigDecimal actualCost,
                                   int actualImageCount, int requestedCount) {
        if (actualImageCount < requestedCount) {
            log.warn("上游少返回图片：请求 {} 张，实际 {} 张，已按实际张数结算并退回差额",
                    requestedCount, actualImageCount);
        }
        try {
            callSettlementService.settle(reservation, actualTokens, actualCost);
        } catch (Exception e) {
            // 结算整体回滚了：预留守住、差额没追回来，也即欠费。指标只在这里打
            BigDecimal deficit = actualCost.subtract(
                    reservation.cost() != null ? reservation.cost() : BigDecimal.ZERO);
            if (deficit.compareTo(BigDecimal.ZERO) > 0) {
                aiMetricsCollector.recordSettlementDeficit(reservation.modelKey(), reservation.userId(), deficit);
            }
            log.error("图片生成结算失败，用户 {}：预留 {} Token / ¥{}，实际 {} 张 / {} Token / ¥{}",
                    reservation.userId(), reservation.tokens(), reservation.cost(),
                    actualImageCount, actualTokens, actualCost, e);
        }
    }

    /**
     * 退回预留（失败路径）。退款失败只升级日志，不再把「生成失败」变成「退款也炸了」
     */
    private void refundReservation(CallReservation reservation) {
        try {
            callSettlementService.refund(reservation);
        } catch (Exception e) {
            log.error("图片生成失败后预留退款失败，用户 {} 的额度/余额可能被多占，需人工核对：{} Token / ¥{}",
                    reservation.userId(), reservation.tokens(), reservation.cost(), e);
        }
    }

    /**
     * 记录生成失败（{@code status = "failed"}）。
     * 方法上没有事务，这条记录能真正落库（以前被方法级事务一起回滚，表里从来没有 failed 记录）
     */
    private void recordFailure(Model model, String modelKey, ImageGenerationRequest request, String size,
                               Long userId, Long apiKeyId, String clientIp, long startTime, Exception cause) {
        long duration = System.currentTimeMillis() - startTime;
        // 错误信息可能很长（含上游整段响应），截断避免写库失败
        String errorMessage = cause.getMessage();
        if (errorMessage != null && errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }
        try {
            ImageGenerationRecord record = ImageGenerationRecord.builder()
                    .userId(userId)
                    .apiKeyId(apiKeyId)
                    .modelId(model.getId())
                    .modelKey(modelKey)
                    .prompt(request.getPrompt())
                    .size(size)
                    .quality(request.getQuality())
                    .status("failed")
                    .cost(BigDecimal.ZERO)
                    .duration((int) duration)
                    .errorMessage(errorMessage)
                    .clientIp(clientIp)
                    .createTime(LocalDateTime.now())
                    .build();
            save(record);
        } catch (Exception saveError) {
            // 记失败日志这件事本身出错，不该掩盖真正的失败原因
            log.error("写入图片生成失败记录时出错", saveError);
        }
        log.error("图片生成失败：用户 {}, 模型 {}, 错误：{}", userId, modelKey, errorMessage, cause);
    }

    /**
     * 调用通义万相生成图片（异步模式 + 轮询）
     */
    private ImageGenerationResponse callImageGenerationModel(Model model, ModelProvider provider,
                                                             ImageGenerationRequest request, String size, int n) {
        try {
            // 构建请求JSON
            Map<String, Object> body = new HashMap<>();
            body.put("model", model.getModelKey());
            body.put("input", Map.of("prompt", request.getPrompt()));
            body.put("parameters", Map.of("size", size, "n", n));
            String requestBody = objectMapper.writeValueAsString(body);

            // 调用通义万相API（异步模式）
            String apiUrl = provider.getBaseUrl().replace("/compatible-mode", "")
                    + "/api/v1/services/aigc/text2image/image-synthesis";

            HttpResponse httpResponse = HttpRequest.post(apiUrl)
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")  // 异步模式
                    .body(requestBody)
                    .timeout(30000)
                    .execute();

            String responseBody = httpResponse.body();
            log.info("通义万相创建任务响应：{}", responseBody);

            // 解析响应，获取任务ID
            JsonNode rootNode = objectMapper.readTree(responseBody);

            if (!rootNode.has("output") || !rootNode.get("output").has("task_id")) {
                String errorMsg = rootNode.has("message") ? rootNode.get("message").asText() : "未返回任务ID";
                throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "创建图片生成任务失败：" + errorMsg);
            }

            String taskId = rootNode.get("output").get("task_id").asText();
            log.info("图片生成任务ID：{}", taskId);
            // 轮询任务状态
            String taskApiUrl = provider.getBaseUrl().replace("/compatible-mode", "")
                    + "/api/v1/tasks/" + taskId;

            int maxRetries = 60;  // 最多轮询60次
            int retryCount = 0;

            while (retryCount < maxRetries) {
                Thread.sleep(2000);  // 等待2秒

                HttpResponse taskResponse = HttpRequest.get(taskApiUrl)
                        .header("Authorization", "Bearer " + provider.getApiKey())
                        .timeout(10000)
                        .execute();

                String taskResponseBody = taskResponse.body();
                JsonNode taskNode = objectMapper.readTree(taskResponseBody);

                if (!taskNode.has("output") || !taskNode.get("output").has("task_status")) {
                    retryCount++;
                    continue;
                }

                String taskStatus = taskNode.get("output").get("task_status").asText();
                log.info("任务状态：{} (轮询次数: {})", taskStatus, retryCount + 1);

                if ("SUCCEEDED".equals(taskStatus)) {
                    // 任务成功，提取图片URL
                    List<ImageGenerationResponse.ImageData> imageDataList = new ArrayList<>();

                    if (taskNode.has("output") && taskNode.get("output").has("results")) {
                        JsonNode results = taskNode.get("output").get("results");
                        for (JsonNode result : results) {
                            if (result.has("url")) {
                                ImageGenerationResponse.ImageData imageData = ImageGenerationResponse.ImageData.builder()
                                        .url(result.get("url").asText())
                                        .revisedPrompt(request.getPrompt())
                                        .build();
                                imageDataList.add(imageData);
                            }
                        }
                    }

                    if (imageDataList.isEmpty()) {
                        throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "任务成功但未返回图片");
                    }

                    return ImageGenerationResponse.builder()
                            .created(System.currentTimeMillis() / 1000)
                            .data(imageDataList)
                            .build();

                } else if ("FAILED".equals(taskStatus)) {
                    String errorMsg = taskNode.has("output") && taskNode.get("output").has("message")
                            ? taskNode.get("output").get("message").asText() : "任务失败";
                    throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "图片生成失败：" + errorMsg);
                }
                // PENDING, RUNNING 状态继续轮询
                retryCount++;
            }
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "图片生成超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "图片生成被中断");
        } catch (Exception e) {
            log.error("调用通义万相API失败", e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "调用图片生成API失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户图片生成记录
     *
     * @param userId   用户ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 图片生成记录列表
     */
    @Override
    public Page<ImageGenerationRecord> listUserRecords(Long userId, int pageNum, int pageSize) {
        QueryWrapper queryWrapper = QueryWrapper.create().where("userId = ?", userId).orderBy("createTime", false);
        return page(Page.of(pageNum, pageSize), queryWrapper);
    }
}