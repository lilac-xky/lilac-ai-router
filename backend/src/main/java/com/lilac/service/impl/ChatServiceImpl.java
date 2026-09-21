package com.lilac.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.domain.dto.billing.CallReservation;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.dto.chat.ChatResponse;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.enums.RoutingStrategyTypeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.exception.CallRejectedException;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.model.StreamResponse;
import com.lilac.service.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天服务实现类
 * 根据路由策略选择模型，通过适配器调用模型，并在失败时回退到备选模型
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    /**
     * 模型类型：聊天
     */
    private static final String MODEL_TYPE_CHAT = "chat";

    /**
     * Fallback 最大重试次数
     */
    private static final int MAX_FALLBACK_RETRIES = 2;

    /**
     * 请求未指定 max_tokens 时为输出预留的 Token 数。太小兜不住实际输出，太大容易误拒
     */
    private static final int DEFAULT_RESERVED_COMPLETION_TOKENS = 1024;

    /**
     * 预估输入 Token 的字符折算比。中文约 1 token/字、英文约 1 token/4 字符，取 2 折中
     */
    private static final int CHARS_PER_TOKEN = 2;

    /**
     * 每条消息的固定开销（角色标记、分隔符），按字符折算
     */
    private static final int MESSAGE_OVERHEAD_CHARS = 16;

    @Resource
    private RoutingService routingService;
    @Resource
    private ModelInvokeService modelInvokeService;
    @Resource
    private ModelProviderService modelProviderService;
    @Resource
    private RequestLogService requestLogService;
    @Resource
    private UserService userService;
    @Resource
    private QuotaService quotaService;
    @Resource
    private BillingService billingService;
    @Resource
    private BalanceService balanceService;
    @Resource
    private CallSettlementService callSettlementService;
    @Resource
    private UserProviderKeyService userProviderKeyService;
    @Resource
    private AIMetricsCollector aiMetricsCollector;

    /**
     * 非流式聊天
     *
     * @param chatRequest 请求参数
     * @param userId      用户ID
     * @param apiKeyId    API密钥ID
     * @return 响应结果
     */
    @Override
    public ChatResponse chat(ChatRequest chatRequest, Long userId, Long apiKeyId) {
        long startTime = System.currentTimeMillis();
        String requestedModel = chatRequest.getModel();

        // 检查用户状态
        if (userId != null && userService.isUserDisabled(userId)) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "账号已被禁用，无法使用服务");
        }
        // 确定路由策略：优先使用请求中指定的策略，否则根据是否指定模型决定
        String strategyType = determineStrategyType(chatRequest.getRoutingStrategy(), requestedModel);
        // 选择主模型
        Model selectedModel = routingService.selectModel(strategyType, MODEL_TYPE_CHAT, requestedModel);
        if (selectedModel == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "没有可用的模型");
        }
        // 获取 Fallback 模型列表
        List<Model> fallbackModels = routingService.getFallbackModels(strategyType, MODEL_TYPE_CHAT, requestedModel);
        // 带 Fallback 的调用
        return invokeWithFallback(selectedModel, fallbackModels, chatRequest, userId, apiKeyId, startTime);
    }

    /**
     * 流式聊天
     *
     * @param chatRequest 聊天请求参数
     * @param userId      用户ID
     * @param apiKeyId    API密钥ID
     * @return 响应结果
     */
    @Override
    public Flux<StreamResponse> chatStream(ChatRequest chatRequest, Long userId, Long apiKeyId) {
        long startTime = System.currentTimeMillis();
        // 检查用户状态
        if (userId != null && userService.isUserDisabled(userId)) {
            return Flux.error(new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "账号已被禁用，无法使用服务"));
        }
        String requestedModel = chatRequest.getModel();
        String strategyType = determineStrategyType(chatRequest.getRoutingStrategy(), requestedModel);
        Model selectedModel = routingService.selectModel(strategyType, MODEL_TYPE_CHAT, requestedModel);
        if (selectedModel == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "没有可用的模型");
        }
        List<Model> fallbackModels = routingService.getFallbackModels(strategyType, MODEL_TYPE_CHAT, requestedModel);

        // 主模型流式调用，失败时回退到首个备选模型
        Flux<StreamResponse> stream = streamWithModel(selectedModel, chatRequest, userId, apiKeyId, startTime);
        if (fallbackModels != null && !fallbackModels.isEmpty()) {
            Model fallbackModel = fallbackModels.get(0);
            stream = stream.onErrorResume(e -> {
                if (e instanceof CallRejectedException) {
                    // 账户资源不足，换备选模型同样会被拒，不降级
                    return Flux.error(e);
                }
                log.warn("模型 {} 流式调用失败，回退到备选模型 {}", selectedModel.getModelKey(), fallbackModel.getModelKey(), e);
                return streamWithModel(fallbackModel, chatRequest, userId, apiKeyId, startTime);
            });
        }
        return stream;
    }

    /**
     * 带 Fallback 的模型调用
     */
    private ChatResponse invokeWithFallback(Model primaryModel, List<Model> fallbackModels,
                                            ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        try {
            return callModel(primaryModel, chatRequest, userId, apiKeyId, startTime);
        } catch (CallRejectedException e) {
            // 账户资源不足，不降级
            throw e;
        } catch (Exception e) {
            log.warn("模型 {} 调用失败，尝试 Fallback", primaryModel.getModelKey(), e);
            if (fallbackModels != null && !fallbackModels.isEmpty()) {
                int retries = Math.min(fallbackModels.size(), MAX_FALLBACK_RETRIES);
                for (int i = 0; i < retries; i++) {
                    Model fallbackModel = fallbackModels.get(i);
                    try {
                        log.info("尝试 Fallback 模型: {}", fallbackModel.getModelKey());
                        return callModel(fallbackModel, chatRequest, userId, apiKeyId, startTime);
                    } catch (CallRejectedException rejected) {
                        // 备选模型同样被闸门拒绝，再试没意义
                        throw rejected;
                    } catch (Exception fallbackException) {
                        log.warn("Fallback 模型 {} 调用失败", fallbackModel.getModelKey(), fallbackException);
                    }
                }
            }
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "调用模型失败: " + e.getMessage());
        }
    }

    /**
     * 调用单个模型（非流式），并记录请求日志。
     */
    private ChatResponse callModel(Model model, ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        ProviderContext providerContext = resolveProvider(model, userId);
        CallReservation reservation = buildReservation(model, chatRequest, userId, apiKeyId, providerContext.byok());

        // 校验用户配额与余额
        checkUserQuotaAndBalance(userId, providerContext.byok(), reservation);
        callSettlementService.reserve(reservation);

        boolean modelInvoked = false;
        try {
            org.springframework.ai.chat.model.ChatResponse aiResponse = modelInvokeService.invoke(model, providerContext.provider(), chatRequest);
            // 上游已计费，之后任何失败都不能再退预留
            modelInvoked = true;
            ChatResponse response = convertResponse(aiResponse, model.getModelKey());

            long duration = System.currentTimeMillis() - startTime;
            ChatResponse.Usage usage = response.getUsage();
            int totalTokens = usage.getTotalTokens();

            // 记录请求日志
            requestLogService.logRequest(userId, apiKeyId, model.getId(), model.getModelKey(),
                    usage.getPromptTokens(), usage.getCompletionTokens(), totalTokens,
                    (int) duration, "success", null);

            // 收集监控指标
            aiMetricsCollector.recordRequest(model.getModelKey(), userId, apiKeyId != null ? apiKeyId.toString() : null);
            aiMetricsCollector.recordTokens(model.getModelKey(), totalTokens);
            aiMetricsCollector.recordResponseTime(model.getModelKey(), duration);

            // 结算：对齐真实用量。失败只记欠费，不把已交付的服务改成失败
            settleSafely(reservation, model, usage.getPromptTokens(), usage.getCompletionTokens(), totalTokens);
            return response;
        } catch (Exception e) {
            if (!modelInvoked) {
                // 上游一次都没调到，预留全额退回
                refundReservation(reservation, "模型调用失败");
            } else {
                log.error("模型已调用但后续处理失败，预留 {} Token / ¥{} 不再退回（上游成本已发生），用户 {}",
                        reservation.tokens(), reservation.cost(), userId, e);
            }

            long duration = System.currentTimeMillis() - startTime;
            requestLogService.logRequest(userId, apiKeyId, model.getId(), model.getModelKey(), 0, 0, 0,
                    (int) duration, "failed", e.getMessage());

            // 收集错误指标
            aiMetricsCollector.recordError(model.getModelKey(), "MODEL_ERROR");
            throw e;
        }
    }

    /**
     * 流式调用单个模型，返回统一的结构化响应流，并在结束/出错时记录日志
     */
    private Flux<StreamResponse> streamWithModel(Model model, ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        return Flux.defer(() -> {
            ProviderContext providerContext = resolveProvider(model, userId);
            CallReservation reservation = buildReservation(model, chatRequest, userId, apiKeyId, providerContext.byok());

            // 前置校验（友好提示，不是闸门）；占不到配额/余额会抛 CallRejectedException
            checkUserQuotaAndBalance(userId, providerContext.byok(), reservation);
            callSettlementService.reserve(reservation);
            // doOnComplete / doOnError / doOnCancel 都可能触发，用 CAS 保证只结一次或只退一次
            final AtomicBoolean reservationClosed = new AtomicBoolean(false);

            // 本次流的唯一标识与创建时间（所有 chunk 共用）
            final String traceId = IdUtil.simpleUUID();
            final long created = System.currentTimeMillis() / 1000;
            // 首个块标识，用于在 delta 中携带 role
            final boolean[] isFirstChunk = {true};
            // Token 计数器（流式通常只有最后一个 chunk 携带 usage）
            final int[] promptTokens = {0};
            final int[] completionTokens = {0};
            return modelInvokeService.invokeStreamChunk(model, providerContext.provider(), chatRequest)
                .flatMap(chunk -> {
                    if (chunk.getPromptTokens() != null && chunk.getPromptTokens() > 0) {
                        promptTokens[0] = chunk.getPromptTokens();
                    }
                    if (chunk.getCompletionTokens() != null && chunk.getCompletionTokens() > 0) {
                        completionTokens[0] = chunk.getCompletionTokens();
                    }
                    // 既没有文本也没有思考内容，跳过空 chunk
                    if (!chunk.hasText() && !chunk.hasReasoningContent()) {
                        return Flux.empty();
                    }

                    // 构建 Delta
                    StreamResponse.Delta.DeltaBuilder deltaBuilder = StreamResponse.Delta.builder();
                    // 第一个块包含 role
                    if (isFirstChunk[0]) {
                        deltaBuilder.role("assistant");
                        isFirstChunk[0] = false;
                    }
                    // 处理普通文本内容
                    if (chunk.hasText()) {
                        deltaBuilder.content(chunk.getText());
                    }
                    // 处理深度思考内容（deepseek-reasoner 专属）
                    if (chunk.hasReasoningContent()) {
                        deltaBuilder.reasoningContent(chunk.getReasoningContent());
                    }
                    StreamResponse.StreamChoice choice = StreamResponse.StreamChoice.builder()
                            .index(0)
                            .delta(deltaBuilder.build())
                            .finishReason(null)  // 未结束时为 null
                            .build();
                    return Flux.just(StreamResponse.builder()
                            .id(traceId)
                            .object("chat.completion.chunk")
                            .created(created)
                            .model(model.getModelKey())
                            .choices(List.of(choice))
                            .build());
                })
                // 流结束时追加一个带 finishReason: "stop" 的结束标识
                .concatWith(Flux.defer(() -> {
                    StreamResponse.StreamChoice finishChoice = StreamResponse.StreamChoice.builder()
                            .index(0)
                            .delta(StreamResponse.Delta.builder().build())
                            .finishReason("stop")
                            .build();
                    return Flux.just(StreamResponse.builder()
                            .id(traceId)
                            .object("chat.completion.chunk")
                            .created(created)
                            .model(model.getModelKey())
                            .choices(List.of(finishChoice))
                            .build());
                }))
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int totalTokens = promptTokens[0] + completionTokens[0];

                    requestLogService.logRequest(userId, apiKeyId, model.getId(), model.getModelKey(),
                            promptTokens[0], completionTokens[0], totalTokens,
                            (int) duration, "success", null);

                    // 收集监控指标
                    aiMetricsCollector.recordRequest(model.getModelKey(), userId, apiKeyId != null ? apiKeyId.toString() : null);
                    aiMetricsCollector.recordTokens(model.getModelKey(), totalTokens);
                    aiMetricsCollector.recordResponseTime(model.getModelKey(), duration);

                    // 结算。响应已通过 SSE 返回，不能再抛错（会触发 HttpMessageNotWritableException）
                    if (reservationClosed.compareAndSet(false, true)) {
                        settleSafely(reservation, model, promptTokens[0], completionTokens[0], totalTokens);
                    }
                })
                .doOnError(error -> {
                    log.error("模型 {} 流式调用失败", model.getModelKey(), error);
                    long duration = System.currentTimeMillis() - startTime;
                    requestLogService.logRequest(userId, apiKeyId, model.getId(), model.getModelKey(), 0, 0, 0,
                            (int) duration, "failed", error.getMessage());

                    // 收集流式错误指标
                    aiMetricsCollector.recordError(model.getModelKey(), "STREAM_ERROR");

                    // 流失败 → 预留全额退回
                    if (reservationClosed.compareAndSet(false, true)) {
                        refundReservation(reservation, "流式调用失败");
                    }
                })
                // 客户端主动断开：上游可能已产出部分内容，但服务没交付完，选择退回预留
                .doOnCancel(() -> {
                    if (reservationClosed.compareAndSet(false, true)) {
                        refundReservation(reservation, "客户端取消流式请求");
                    }
                });
        });
    }

    /**
     * 构造预留凭据（预估 Token 与预估费用）。匿名与 BYOK 调用返回空凭据。预估输出优先取请求里的 {@code max_tokens}，没给则用默认预留量。
     */
    private CallReservation buildReservation(Model model, ChatRequest chatRequest, Long userId, Long apiKeyId, boolean byok) {
        if (userId == null || byok) {
            return CallReservation.none();
        }
        int estimatedPromptTokens = estimatePromptTokens(chatRequest);
        int estimatedCompletionTokens = resolveEstimatedCompletionTokens(chatRequest);
        int estimatedTokens = estimatedPromptTokens + estimatedCompletionTokens;
        BigDecimal estimatedCost = billingService.calculateCost(model, estimatedPromptTokens, estimatedCompletionTokens);
        // 区分 API 调用与网页调用，方便对账按来源汇总
        String channel = apiKeyId != null ? "API调用消费" : "网页调用消费";
        return new CallReservation(userId, model.getModelKey(), estimatedTokens, estimatedCost, channel);
    }

    /**
     * 预估输入 Token 数：字符数 / {@link #CHARS_PER_TOKEN} 粗算。不引 tokenizer，估算只要「宁可略高」，多退少补会兜回来。
     */
    private int estimatePromptTokens(ChatRequest chatRequest) {
        List<ChatMessage> messages = chatRequest.getMessages();
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (message.getRole() != null) {
                chars += message.getRole().length();
            }
            if (message.getContent() != null) {
                chars += message.getContent().length();
            }
            chars += MESSAGE_OVERHEAD_CHARS;
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    /**
     * 预估输出 Token 数：优先用请求里的 {@code max_tokens}，没给则用默认预留量。
     */
    private int resolveEstimatedCompletionTokens(ChatRequest chatRequest) {
        Integer maxTokens = chatRequest.getMaxTokens();
        if (maxTokens != null && maxTokens > 0) {
            return maxTokens;
        }
        return DEFAULT_RESERVED_COMPLETION_TOKENS;
    }

    /**
     * 结算（成功路径）。吞异常是刻意的：服务已经交付，把请求改成失败挽回不了上游成本，
     * 只会让用户「花了钱还收到报错」。留下欠费日志与指标即可。
     */
    private void settleSafely(CallReservation reservation, Model model, int promptTokens, int completionTokens, int totalTokens) {
        if (!reservation.isActive()) {
            return;
        }
        BigDecimal actualCost = billingService.calculateCost(model, promptTokens, completionTokens);
        try {
            callSettlementService.settle(reservation, totalTokens, actualCost);
        } catch (Exception e) {
            // 结算失败，记录欠费
            BigDecimal deficit = actualCost.subtract(reservation.cost() != null ? reservation.cost() : BigDecimal.ZERO);
            if (deficit.compareTo(BigDecimal.ZERO) > 0) {
                aiMetricsCollector.recordSettlementDeficit(model.getModelKey(), reservation.userId(), deficit);
            }
            log.error("结算失败，用户 {} 本次调用产生欠费：预留 {} Token / ¥{}，实际 {} Token / ¥{}",
                    reservation.userId(), reservation.tokens(), reservation.cost(), totalTokens, actualCost, e);
        }
    }

    /**
     * 退回预留（失败路径）。退款失败只升级日志，不再把「调用失败」变成「退款也炸了」
     */
    private void refundReservation(CallReservation reservation, String reason) {
        if (!reservation.isActive()) {
            return;
        }
        try {
            callSettlementService.refund(reservation);
        } catch (Exception e) {
            log.error("预留退款失败（{}），用户 {} 的额度/余额可能被多占，需人工核对：{} Token / ¥{}",
                    reason, reservation.userId(), reservation.tokens(), reservation.cost(), e);
        }
    }

    /**
     * 获取提供者配置，并在用户配置有效密钥时以其密钥发起调用。
     */
    private ProviderContext resolveProvider(Model model, Long userId) {
        ModelProvider provider = modelProviderService.getById(model.getProviderId());
        if (provider == null) {
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "模型 " + model.getModelKey() + " 对应的提供者不存在");
        }

        if (userId == null) {
            return new ProviderContext(provider, false);
        }

        String userApiKey = userProviderKeyService.getUserProviderApiKey(userId, model.getProviderId());
        if (StrUtil.isBlank(userApiKey)) {
            return new ProviderContext(provider, false);
        }

        ModelProvider byokProvider = ModelProvider.builder()
                .id(provider.getId())
                .providerName(provider.getProviderName())
                .displayName(provider.getDisplayName())
                .baseUrl(provider.getBaseUrl())
                .apiKey(userApiKey)
                .status(provider.getStatus())
                .healthStatus(provider.getHealthStatus())
                .avgLatency(provider.getAvgLatency())
                .successRate(provider.getSuccessRate())
                .priority(provider.getPriority())
                .config(provider.getConfig())
                .createTime(provider.getCreateTime())
                .updateTime(provider.getUpdateTime())
                .build();
        log.info("用户 {} 使用 BYOK 模式调用模型 {}", userId, model.getModelKey());
        return new ProviderContext(byokProvider, true);
    }

    /**
     * 校验用户配额与余额
     */
    private void checkUserQuotaAndBalance(Long userId, boolean byok, CallReservation reservation) {
        if (userId == null || byok) {
            return;
        }
        if (!quotaService.checkQuota(userId)) {
            throw new CallRejectedException(HttpsCodeEnum.OPERATION_ERROR, "Token配额已用尽，请联系管理员增加配额");
        }
        // 只在本次确实要花钱时才校验余额，免费模型不该被余额为 0 的账号拦掉
        BigDecimal needCost = reservation.cost();
        if (needCost != null && needCost.compareTo(BigDecimal.ZERO) > 0 && balanceService.getUserBalance(userId).compareTo(needCost) < 0) {
            throw new CallRejectedException(HttpsCodeEnum.UNAUTHORIZED, "余额不足：本次调用预计需要 ¥" + needCost + "，请先充值");
        }
    }

    private record ProviderContext(ModelProvider provider, boolean byok) {
    }

    /**
     * 确定路由策略类型
     * 优先使用请求显式指定的策略；未指定时，指定了具体模型则用固定策略，否则使用自动路由
     */
    private String determineStrategyType(String requestedStrategy, String requestedModel) {
        if (StrUtil.isNotBlank(requestedStrategy)) {
            return requestedStrategy;
        }
        if (StrUtil.isNotBlank(requestedModel)) {
            return RoutingStrategyTypeEnum.FIXED.getValue();
        }
        return RoutingStrategyTypeEnum.AUTO.getValue();
    }

    /**
     * 将 Spring AI 的响应转换为统一的响应结果
     *
     * @param aiResponse AI 响应结果
     * @param modelName  模型名称
     * @return 转换后的响应结果
     */
    private ChatResponse convertResponse(org.springframework.ai.chat.model.ChatResponse aiResponse, String modelName) {
        String content = aiResponse.getResult().getOutput().getText();

        ChatResponse.Usage usage = ChatResponse.Usage.builder()
                .promptTokens(aiResponse.getMetadata().getUsage().getPromptTokens() != null ?
                        aiResponse.getMetadata().getUsage().getPromptTokens() : 0)
                .completionTokens(aiResponse.getMetadata().getUsage().getCompletionTokens() != null ?
                        aiResponse.getMetadata().getUsage().getCompletionTokens() : 0)
                .totalTokens(aiResponse.getMetadata().getUsage().getTotalTokens() != null ?
                        aiResponse.getMetadata().getUsage().getTotalTokens() : 0)
                .build();

        ChatResponse.Choice choice = ChatResponse.Choice.builder()
                .index(0)
                .message(new ChatMessage("assistant", content))
                .finishReason(aiResponse.getResult().getMetadata().getFinishReason())
                .build();

        return ChatResponse.builder()
                .id(IdUtil.simpleUUID())
                .object("chat.completion")
                .created(System.currentTimeMillis() / 1000)
                .model(modelName)
                .choices(List.of(choice))
                .usage(usage)
                .build();
    }
}
