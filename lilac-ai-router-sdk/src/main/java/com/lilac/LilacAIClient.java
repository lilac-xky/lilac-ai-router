package com.lilac;

import com.lilac.callback.StreamCallback;
import com.lilac.config.ClientConfig;
import com.lilac.http.HttpClient;
import com.lilac.model.ChatRequest;
import com.lilac.model.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * LilacAI客户端
 */
@Slf4j
public class LilacAIClient {

    private final ClientConfig config;
    private final HttpClient httpClient;

    // 私有构造函数，只能通过 Builder 创建
    private LilacAIClient(ClientConfig config) {
        this.config = config;
        this.config.validate();  // 验证配置
        this.httpClient = new HttpClient(config);
    }

    // 返回 Builder 实例
    public static ClientConfigBuilder builder() {
        return new ClientConfigBuilder();
    }

    /**
     * 同步聊天（简单消息）
     *
     * @param message 消息内容
     * @return 聊天响应
     */
    public ChatResponse chat(String message) {
        return httpClient.chat(message);
    }

    /**
     * 同步聊天（指定模型）
     *
     * @param model   模型名称
     * @param message 消息内容
     * @return 聊天响应
     */
    public ChatResponse chat(String model, String message) {
        return httpClient.chat(model, message);
    }

    /**
     * 同步聊天（完整请求）
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    public ChatResponse chat(ChatRequest request) {
        return httpClient.chat(request);
    }

    /**
     * 流式聊天（简单消息）
     *
     * @param message  消息内容
     * @param callback 流式回调
     */
    public void chatStream(String message, StreamCallback callback) {
        httpClient.chatStream(ChatRequest.simple(message), callback);
    }

    /**
     * 流式聊天（完整请求）
     *
     * @param request  聊天请求
     * @param callback 流式回调
     */
    public void chatStream(ChatRequest request, StreamCallback callback) {
        httpClient.chatStream(request, callback);
    }

    /**
     * 关闭客户端，释放资源
     */
    public void close() {
        httpClient.close();
    }

    // 内部建造者类
    public static class ClientConfigBuilder {
        private String apiKey;
        private String baseUrl = "http://localhost:9090/api";  // 默认值
        private Integer connectTimeout = 10000;
        private Integer readTimeout = 30000;
        private Integer maxRetries = 3;
        private Integer retryDelay = 1000;

        public ClientConfigBuilder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;  // 返回自身，支持链式调用
        }

        public ClientConfigBuilder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public ClientConfigBuilder connectTimeout(Integer connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public ClientConfigBuilder readTimeout(Integer readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public ClientConfigBuilder maxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public ClientConfigBuilder retryDelay(Integer retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public LilacAIClient build() {
            // 创建配置对象
            ClientConfig config = ClientConfig.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .connectTimeout(connectTimeout)
                    .readTimeout(readTimeout)
                    .maxRetries(maxRetries)
                    .retryDelay(retryDelay)
                    .build();

            // 创建客户端
            return new LilacAIClient(config);
        }
    }
    
}
