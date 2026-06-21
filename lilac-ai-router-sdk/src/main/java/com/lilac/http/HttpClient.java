package com.lilac.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lilac.callback.StreamCallback;
import com.lilac.config.ClientConfig;
import com.lilac.exception.AuthException;
import com.lilac.exception.LilacAIException;
import com.lilac.exception.RateLimitException;
import com.lilac.model.ChatChunk;
import com.lilac.model.ChatRequest;
import com.lilac.model.ChatResponse;
import com.lilac.model.StreamResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * HttpClient
 */
@Slf4j
public class HttpClient {
    
    private final ClientConfig config;
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    public HttpClient(ClientConfig config) {
        this.config = config;
        this.gson = new GsonBuilder().create();
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 发送 POST 请求
     *
     * @param url     请求地址
     * @param jsonBody 请求体
     * @return 响应结果
     */
    private Request buildRequest(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /**
     * 处理响应结果
     *
     * @param response 响应对象
     * @return 响应结果
     * @throws IOException 输入输出异常
     */
    private ChatResponse handleResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            handleErrorResponse(response);
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new LilacAIException("Response body is null");
        }
        String responseBody = body.string();
        return gson.fromJson(responseBody, ChatResponse.class);
    }

    /**
     * 处理错误响应结果
     *
     * @param response 响应对象
     * @throws IOException 输入输出异常
     */
    private void handleErrorResponse(Response response) throws IOException {
        int code = response.code();
        String message = "Request failed with code: " + code;
        ResponseBody body = response.body();
        if (body != null) {
            message = body.string();
        }
        switch (code) {
            case 401:
                throw new AuthException(message);
            case 429:
                throw new RateLimitException(message);
            default:
                throw new LilacAIException(code, message);
        }
    }

    /**
     * 创建简单聊天请求
     *
     * @param message 消息内容
     * @return 聊天请求
     */
    public ChatResponse chat(String message) {
        return chat(ChatRequest.simple(message));
    }

    /**
     * 创建带有指定模型参数的聊天请求
     *
     * @param model   模型名称
     * @param message 消息内容
     * @return 聊天请求
     */
    public ChatResponse chat(String model, String message) {
        return chat(ChatRequest.withModel(model, message));
    }

    /**
     * 发送聊天请求
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    public ChatResponse chat(ChatRequest request) {
        log.debug("Sending chat request: {}", request);
        // 构建请求
        String url = config.getBaseUrl() + "/v1/chat/completions";
        String jsonBody = gson.toJson(request);
        Request httpRequest = buildRequest(url, jsonBody);
        // 发送请求（带重试）
        ChatResponse response = sendWithRetry(httpRequest);
        log.debug("Received chat response: {}", response);
        return response;
    }

    /**
     * 发送聊天请求（带重试）
     *
     * @param httpRequest HTTP请求
     * @return 聊天响应
     */
    private ChatResponse sendWithRetry(Request httpRequest) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount <= config.getMaxRetries()) {
            try (Response response = okHttpClient.newCall(httpRequest).execute()) {
                return handleResponse(response);
            } catch (RateLimitException | AuthException e) {
                // 认证和限流异常不重试
                throw e;
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                if (retryCount <= config.getMaxRetries()) {
                    int delay = config.getRetryDelay() * retryCount;  // 线性退避
                    log.warn("Request failed, retrying in {}ms... ({}/{})", delay, retryCount, config.getMaxRetries(), e);

                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LilacAIException("Request interrupted", ie);
                    }
                }
            }
        }
        throw new LilacAIException("Request failed after " + config.getMaxRetries() + " retries", lastException);
    }

    /**
     * 聊天流式请求（简单消息）
     *
     * @param message  消息内容
     * @param callback 回调函数
     */
    public void chatStream(String message, StreamCallback callback) {
        chatStream(ChatRequest.simple(message), callback);
    }

    /**
     * 聊天流式请求
     *
     * @param request   聊天请求
     * @param callback  回调函数
     */
    public void chatStream(ChatRequest request, StreamCallback callback) {
        // 设置流式模式
        request.setStream(true);
        // 构建请求
        String url = config.getBaseUrl() + "/v1/chat/completions";
        String jsonBody = gson.toJson(request);
        Request httpRequest = buildRequest(url, jsonBody);

        try {
            // 发送请求
            Response response = okHttpClient.newCall(httpRequest).execute();
            // 检查响应状态
            if (!response.isSuccessful()) {
                handleErrorResponse(response);
            }
            // 获取响应流（重要：使用 byteStream，不要用 string()）
            ResponseBody body = response.body();
            if (body == null) {
                throw new LilacAIException("Response body is null");
            }
            InputStream inputStream = body.byteStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            // 逐行读取 SSE 数据
            String line;
            while ((line = reader.readLine()) != null) {
                // 跳过空行
                if (line.trim().isEmpty()) {
                    continue;
                }
                // 解析 SSE 数据行
                if (line.startsWith("data:")) {
                    // 去掉 "data: " 前缀
                    String data = line.substring(5);
                    if (data.trim().isEmpty()) {
                        continue;
                    }

                    try {
                        // 解析 JSON 为 StreamResponse
                        StreamResponse streamResponse = gson.fromJson(data, StreamResponse.class);
                        // 提取内容并转换为 ChatChunk
                        if (streamResponse.getChoices() != null && !streamResponse.getChoices().isEmpty()) {
                            StreamResponse.StreamChoice choice = streamResponse.getChoices().get(0);
                            // 检查是否结束（finishReason 为 "stop"）
                            if ("stop".equals(choice.getFinishReason())) {
                                // 7. 流读取完成
                                callback.onComplete();
                                break;
                            }
                            if (choice.getDelta() != null) {
                                StreamResponse.Delta delta = choice.getDelta();
                                // 如果既没有内容也没有思考内容，跳过
                                if (delta.getContent() == null && delta.getReasoningContent() == null) {
                                    continue;
                                }
                                ChatChunk chunk = ChatChunk.builder()
                                        .content(delta.getContent())
                                        .reasoningContent(delta.getReasoningContent())
                                        .model(streamResponse.getModel())
                                        .done(false)
                                        .build();
                                callback.onMessage(chunk);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse stream response: {}", data, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Stream chat failed", e);
            callback.onError(e);
        }
    }

    /**
     * 关闭客户端，释放连接池与线程资源
     */
    public void close() {
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
        if (okHttpClient.cache() != null) {
            try {
                okHttpClient.cache().close();
            } catch (IOException e) {
                log.warn("Failed to close OkHttp cache", e);
            }
        }
    }
}