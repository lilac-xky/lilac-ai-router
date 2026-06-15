package com.lilac.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.dto.chat.ChatResponse;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.enums.RoutingStrategyTypeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ChatService;
import com.lilac.service.ModelInvokeService;
import com.lilac.service.ModelProviderService;
import com.lilac.service.RequestLogService;
import com.lilac.service.RoutingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

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

    @Resource
    private RoutingService routingService;
    @Resource
    private ModelInvokeService modelInvokeService;
    @Resource
    private ModelProviderService modelProviderService;
    @Resource
    private RequestLogService requestLogService;

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
    public Flux<String> chatStream(ChatRequest chatRequest, Long userId, Long apiKeyId) {
        long startTime = System.currentTimeMillis();
        String requestedModel = chatRequest.getModel();

        String strategyType = determineStrategyType(chatRequest.getRoutingStrategy(), requestedModel);
        Model selectedModel = routingService.selectModel(strategyType, MODEL_TYPE_CHAT, requestedModel);
        if (selectedModel == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "没有可用的模型");
        }
        List<Model> fallbackModels = routingService.getFallbackModels(strategyType, MODEL_TYPE_CHAT, requestedModel);

        // 主模型流式调用，失败时回退到首个备选模型
        return streamWithModel(selectedModel, chatRequest, userId, apiKeyId, startTime)
                .onErrorResume(e -> {
                    log.warn("模型 {} 流式调用失败，尝试 Fallback", selectedModel.getModelKey(), e);
                    if (fallbackModels != null && !fallbackModels.isEmpty()) {
                        return streamWithModel(fallbackModels.get(0), chatRequest, userId, apiKeyId, startTime);
                    }
                    return Flux.error(new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "流式调用模型失败: " + e.getMessage()));
                });
    }

    /**
     * 带 Fallback 的模型调用
     */
    private ChatResponse invokeWithFallback(Model primaryModel, List<Model> fallbackModels,
                                            ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        try {
            return callModel(primaryModel, chatRequest, userId, apiKeyId, startTime);
        } catch (Exception e) {
            log.warn("模型 {} 调用失败，尝试 Fallback", primaryModel.getModelKey(), e);
            if (fallbackModels != null && !fallbackModels.isEmpty()) {
                int retries = Math.min(fallbackModels.size(), MAX_FALLBACK_RETRIES);
                for (int i = 0; i < retries; i++) {
                    Model fallbackModel = fallbackModels.get(i);
                    try {
                        log.info("尝试 Fallback 模型: {}", fallbackModel.getModelKey());
                        return callModel(fallbackModel, chatRequest, userId, apiKeyId, startTime);
                    } catch (Exception fallbackException) {
                        log.warn("Fallback 模型 {} 调用失败", fallbackModel.getModelKey(), fallbackException);
                    }
                }
            }
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "调用模型失败: " + e.getMessage());
        }
    }

    /**
     * 调用单个模型（非流式），并记录请求日志
     */
    private ChatResponse callModel(Model model, ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        ModelProvider provider = getProvider(model);
        try {
            org.springframework.ai.chat.model.ChatResponse aiResponse = modelInvokeService.invoke(model, provider, chatRequest);
            ChatResponse response = convertResponse(aiResponse, model.getModelKey());

            long duration = System.currentTimeMillis() - startTime;
            ChatResponse.Usage usage = response.getUsage();
            requestLogService.logRequest(userId, apiKeyId, model.getModelKey(),
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                    (int) duration, "success", null);
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            requestLogService.logRequest(userId, apiKeyId, model.getModelKey(), 0, 0, 0,
                    (int) duration, "failed", e.getMessage());
            throw e;
        }
    }

    /**
     * 流式调用单个模型，返回 SSE 文本流，并在结束/出错时记录日志
     */
    private Flux<String> streamWithModel(Model model, ChatRequest chatRequest, Long userId, Long apiKeyId, long startTime) {
        ModelProvider provider = getProvider(model);

        // Token 计数器（流式通常只有最后一个 chunk 携带 usage）
        final int[] promptTokens = {0};
        final int[] completionTokens = {0};

        return modelInvokeService.invokeStreamChunk(model, provider, chatRequest)
                .flatMap(chunk -> {
                    if (chunk.getPromptTokens() != null && chunk.getPromptTokens() > 0) {
                        promptTokens[0] = chunk.getPromptTokens();
                    }
                    if (chunk.getCompletionTokens() != null && chunk.getCompletionTokens() > 0) {
                        completionTokens[0] = chunk.getCompletionTokens();
                    }
                    // 仅在有文本内容时下发，避免空 chunk 干扰 SSE
                    if (!chunk.hasText()) {
                        return Flux.empty();
                    }
                    // 将文本中的换行符转义为 \n，避免与 SSE 格式冲突
                    String escapedText = chunk.getText().replace("\n", "\\n");
                    return Flux.just(escapedText + "\n\n");
                })
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int totalTokens = promptTokens[0] + completionTokens[0];
                    requestLogService.logRequest(userId, apiKeyId, model.getModelKey(),
                            promptTokens[0], completionTokens[0], totalTokens,
                            (int) duration, "success", null);
                })
                .doOnError(error -> {
                    log.error("模型 {} 流式调用失败", model.getModelKey(), error);
                    long duration = System.currentTimeMillis() - startTime;
                    requestLogService.logRequest(userId, apiKeyId, model.getModelKey(), 0, 0, 0,
                            (int) duration, "failed", error.getMessage());
                });
    }

    /**
     * 根据模型获取其所属提供者
     */
    private ModelProvider getProvider(Model model) {
        ModelProvider provider = modelProviderService.getById(model.getProviderId());
        if (provider == null) {
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "模型 " + model.getModelKey() + " 对应的提供者不存在");
        }
        return provider;
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
