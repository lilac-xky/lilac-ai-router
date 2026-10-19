package com.lilac.adapter;

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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OpenAI 模型适配器
 */
@Slf4j
@Component
public class OpenAIAdapter implements ModelAdapter {

    /**
     * 支持的提供者名称
     */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("openai", "gpt");

    /**
     * 重试模板：默认的 RetryUtils.DEFAULT_RETRY_TEMPLATE 是 10 次指数退避（封顶 180s），
     * 上游不可达时单请求实测 22 分钟，必须显式换成短重试
     */
    @Resource
    private RetryTemplate chatRetryTemplate;

    /**
     * ChatModel 缓存：{@code OpenAiChatModel} 内部持有 RestClient / WebClient / Netty 事件循环，
     * 每请求重建等于每请求新建一套连接池。压测实测这是 100→200 RPS 掉到秒级的直接原因。
     * key = baseUrl | sha256(apiKey) | modelKey
     */
    private final ConcurrentHashMap<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

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
            OpenAiChatModel chatModel = createChatModel(provider, model);
            Prompt prompt = buildPrompt(chatRequest, model.getModelKey());
            return chatModel.call(prompt);
        } catch (Exception e) {
            log.error("OpenAI 适配器调用模型 {} 失败", model.getModelKey(), e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "调用模型失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 聊天请求
     * @return 聊天响应
     */
    @Override
    public Flux<ChatResponse> invokeStream(Model model, ModelProvider provider, ChatRequest chatRequest) {
        try {
            OpenAiChatModel chatModel = createChatModel(provider, model);
            Prompt prompt = buildPrompt(chatRequest, model.getModelKey());
            return chatModel.stream(prompt);
        } catch (Exception e) {
            log.error("OpenAI 适配器流式调用模型 {} 失败", model.getModelKey(), e);
            throw new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "流式调用模型失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式调用模型（返回统一格式的响应块）
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 聊天请求
     * @return 聊天响应块
     */
    @Override
    public Flux<StreamChunk> invokeStreamChunk(Model model, ModelProvider provider, ChatRequest chatRequest) {
        return invokeStream(model, provider, chatRequest)
                .map(this::convertToStreamChunk)
                .filter(chunk -> !chunk.isEmpty());
    }

    /**
     * 将 OpenAI 响应转换为统一的 StreamChunk 格式
     * OpenAI 标准格式不支持深度思考
     */
    private StreamChunk convertToStreamChunk(ChatResponse response) {
        StreamChunk.StreamChunkBuilder builder = StreamChunk.builder();

        // 提取 Token 信息
        if (!ObjectUtils.isEmpty(response.getMetadata()) && !ObjectUtils.isEmpty(response.getMetadata().getUsage())) {
            Integer promptTokens = response.getMetadata().getUsage().getPromptTokens();
            Integer completionTokens = response.getMetadata().getUsage().getCompletionTokens();
            if (promptTokens != null && promptTokens > 0) {
                builder.promptTokens(promptTokens);
            }
            if (completionTokens != null && completionTokens > 0) {
                builder.completionTokens(completionTokens);
            }
        }

        // 提取文本内容
        if (!ObjectUtils.isEmpty(response.getResult()) && !ObjectUtils.isEmpty(response.getResult().getOutput())) {
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                builder.text(text);
            }
        }

        StreamChunk chunk = builder.build();
        // 如果没有任何内容，标记为空
        if (!chunk.hasText() && !chunk.hasReasoningContent()
                && chunk.getPromptTokens() == null && chunk.getCompletionTokens() == null) {
            return StreamChunk.empty();
        }
        return chunk;
    }

    /**
     * 判断是否支持该提供者
     *
     * @param providerName 提供者名称
     * @return 是否支持
     */
    @Override
    public boolean supports(String providerName) {
        return providerName != null && SUPPORTED_PROVIDERS.contains(providerName.toLowerCase());
    }

    /**
     * 创建 OpenAI ChatModel（带缓存）
     *
     * <p>原先每个请求都新建 RestClient + WebClient + Netty 事件循环，连接池完全复用不了。
     * 压测实测：100 RPS 时 P50 27ms，到 200 RPS 直接涨到 2.6s，拐点在 120 RPS 左右。
     *
     * <p>缓存 key 必须同时包含 baseUrl / apiKey / modelKey：
     * 用户 BYOK 时各自一把 Key，只用 modelKey 做 key 会让不同用户串用凭证。
     */
    private OpenAiChatModel createChatModel(ModelProvider provider, Model model) {
        String cacheKey = String.join("|",
                provider.getBaseUrl() == null ? "" : provider.getBaseUrl(),
                // 只取 apiKey 的哈希参与 key，避免把明文 Key 作为 map key 长期驻留堆内存
                provider.getApiKey() == null ? "" : sha256(provider.getApiKey()),
                model.getModelKey() == null ? "" : model.getModelKey());
        return chatModelCache.computeIfAbsent(cacheKey, k -> buildChatModel(provider, model));
    }

    /**
     * 缓存 key 里不放明文凭据：map 的生命周期是整个进程，明文会一直留在堆里
     */
    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；退化成 hashCode 也比用明文做 key 安全
            return Integer.toHexString(raw.hashCode());
        }
    }

    private OpenAiChatModel buildChatModel(ModelProvider provider, Model model) {
        // 非流式走 restClient、流式走 webClient，两边都要设超时，否则另一半仍会跑满 TCP 超时
        ClientHttpRequestFactory syncFactory = ClientHttpRequestFactoryBuilder.simple()
                .build(ClientHttpRequestFactorySettings.defaults().withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
        HttpClient reactorClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(60));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(syncFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(reactorClient)))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model.getModelKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(chatRetryTemplate)
                .build();
    }

    /**
     * 构建 Prompt
     */
    protected Prompt buildPrompt(ChatRequest chatRequest, String modelKey) {
        List<Message> messages = chatRequest.getMessages().stream()
                .map(this::convertMessage)
                .collect(Collectors.toList());
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(modelKey)
                .streamUsage(true);
        if (chatRequest.getTemperature() != null) {
            optionsBuilder.temperature(chatRequest.getTemperature());
        }
        if (chatRequest.getMaxTokens() != null) {
            optionsBuilder.maxTokens(chatRequest.getMaxTokens());
        }
        return new Prompt(messages, optionsBuilder.build());
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