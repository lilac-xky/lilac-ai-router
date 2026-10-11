package com.lilac.adapter;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import io.netty.channel.ChannelOption;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DashScope 适配器
 */
@Slf4j
@Component
public class DashscopeAdapter implements ModelAdapter {

    /**
     * 支持的提供者名称
     */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("qwen", "dashscope", "tongyi", "aliyun");

    /**
     * 重试模板：默认的 RetryUtils.DEFAULT_RETRY_TEMPLATE 是 10 次指数退避（封顶 180s），
     * 上游不可达时单请求实测 22 分钟，必须显式换成短重试
     */
    @Resource
    private RetryTemplate chatRetryTemplate;

    /**
     * 调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 聊天请求
     * @return 聊天响应
     */
    @Override
    public ChatResponse invoke(Model model, ModelProvider provider, ChatRequest chatRequest) {
        try {
            DashScopeChatModel chatModel = createChatModel(provider, model, chatRequest);
            Prompt prompt = buildPrompt(chatRequest);
            return chatModel.call(prompt);
        } catch (Exception e) {
            log.error("DashScope 适配器调用模型 {} 失败", model.getModelKey(), e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "调用模型失败: " + e.getMessage());
        }
    }

    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 请求
     * @return Prompt
     */
    @Override
    public Flux<ChatResponse> invokeStream(Model model, ModelProvider provider, ChatRequest chatRequest) {
        try {
            DashScopeChatModel chatModel = createChatModel(provider, model, chatRequest);
            Prompt prompt = buildPrompt(chatRequest);
            return chatModel.stream(prompt);
        } catch (Exception e) {
            log.error("DashScope 适配器流式调用模型 {} 失败", model.getModelKey(), e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "流式调用模型失败: " + e.getMessage());
        }
    }

    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 请求
     * @return StreamChunk
     */
    @Override
    public Flux<StreamChunk> invokeStreamChunk(Model model, ModelProvider provider, ChatRequest chatRequest) {
        return invokeStream(model, provider, chatRequest)
                .map(this::convertToStreamChunk)
                .filter(chunk -> !chunk.isEmpty());
    }

    /**
     * 将 DashScope（通义千问）响应转换为统一的 StreamChunk 格式
     * 通义千问深度思考内容存储在 metadata 的 "reasoningContent" 字段
     */
    private StreamChunk convertToStreamChunk(ChatResponse response) {
        StreamChunk.StreamChunkBuilder builder = StreamChunk.builder();

        // 提取 Token 信息
        if (!ObjectUtils.isEmpty(response.getMetadata()) && response.getMetadata().getUsage() != null) {
            Usage dashScopeAiUsage = response.getMetadata().getUsage();
            Integer promptTokens = dashScopeAiUsage.getPromptTokens();
            Integer completionTokens = dashScopeAiUsage.getCompletionTokens();
            if (promptTokens != null && promptTokens > 0) {
                builder.promptTokens(promptTokens);
            }
            if (completionTokens != null && completionTokens > 0) {
                builder.completionTokens(completionTokens);
            }
        }

        // 提取内容
        if (!ObjectUtils.isEmpty(response.getResult()) && !ObjectUtils.isEmpty(response.getResult().getOutput())) {
            // 提取深度思考内容 - 通义千问使用 reasoningContent 字段
            AssistantMessage assistantMessage = response.getResult().getOutput();
            Map<String, Object> metadata = assistantMessage.getMetadata();
            if (!ObjectUtils.isEmpty(metadata)) {
                Object reasoningContent = metadata.get("reasoningContent");
                if (reasoningContent != null && !reasoningContent.toString().isEmpty()) {
                    builder.reasoningContent(reasoningContent.toString());
                }
            }
            // 提取普通文本内容
            String text = assistantMessage.getText();
            if (text != null && !text.isEmpty()) {
                builder.text(text);
            }
        }

        StreamChunk chunk = builder.build();
        // 如果没有任何内容，标记为空
        if (!chunk.hasText() && !chunk.hasReasoningContent() && chunk.getPromptTokens() == null && chunk.getCompletionTokens() == null) {
            return StreamChunk.empty();
        }
        return chunk;
    }

    /**
     * 是否支持的提供者
     *
     * @param providerName 提供者名称
     * @return true/false
     */
    @Override
    public boolean supports(String providerName) {
        return providerName != null && SUPPORTED_PROVIDERS.contains(providerName.toLowerCase());
    }

    /**
     * 创建 DashScope ChatModel
     */
    private DashScopeChatModel createChatModel(ModelProvider provider, Model model, ChatRequest chatRequest) {
        // 非流式走 restClient、流式走 webClient，两边都要设超时，否则另一半仍会跑满 TCP 超时
        ClientHttpRequestFactory syncFactory = ClientHttpRequestFactoryBuilder.simple()
                .build(ClientHttpRequestFactorySettings.defaults().withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
        HttpClient reactorClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(60));

        // 去掉 baseUrl 的兼容模式后缀与版本号
        String nativeBaseUrl = toNativeBaseUrl(provider.getBaseUrl());
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(nativeBaseUrl)
                .apiKey(provider.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(syncFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(reactorClient)))
                .build();
        DashScopeChatOptions options = buildOptions(model, chatRequest);
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .retryTemplate(chatRetryTemplate)
                .build();
    }

    /**
     * 取 baseUrl 的 scheme://host，丢掉兼容模式后缀与版本号。
     * 不用字符串 replace：/compatible-mode 后面可能还跟着 /v1，只替换一段会残留
     */
    private String toNativeBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                log.warn("provider baseUrl 缺少 scheme/host，按原值使用：{}", baseUrl);
                return baseUrl;
            }
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException e) {
            log.warn("provider baseUrl 无法解析，按原值使用：{}", baseUrl);
            return baseUrl;
        }
    }

    /**
     * 构建 DashScope 选项
     */
    private DashScopeChatOptions buildOptions(Model model, ChatRequest chatRequest) {
        DashScopeChatOptions options = new DashScopeChatOptions();
        options.setModel(model.getModelKey());

        if (chatRequest.getTemperature() != null) {
            options.setTemperature(chatRequest.getTemperature());
        }
        if (chatRequest.getMaxTokens() != null) {
            options.setMaxTokens(chatRequest.getMaxTokens());
        }

        // 通义千问深度思考支持
        if (chatRequest.getEnableReasoning() != null && chatRequest.getEnableReasoning()
                && model.getSupportReasoning() != null && model.getSupportReasoning() == 1) {
            options.setEnableThinking(true);
        }
        return options;
    }

    /**
     * 构建 Prompt
     */
    private Prompt buildPrompt(ChatRequest chatRequest) {
        List<Message> messages = chatRequest.getMessages().stream()
                .map(this::convertMessage)
                .collect(Collectors.toList());
        return new Prompt(messages);
    }

    /**
     * 转换消息类型
     */
    private Message convertMessage(ChatMessage msg) {
        String role = msg.getRole();
        if ("system".equals(role)) {
            return new SystemMessage(msg.getContent());
        } else if ("assistant".equals(role)) {
            return new AssistantMessage(msg.getContent());
        } else {
            return new UserMessage(msg.getContent());
        }
    }
}