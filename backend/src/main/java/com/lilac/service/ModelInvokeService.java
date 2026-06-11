package com.lilac.service;

import com.lilac.adapter.StreamChunk;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 模型调用服务
 */
public interface ModelInvokeService {

    /**
     * 调用模型
     *
     * @param model 模型
     * @param provider 模型提供者
     * @param chatRequest 请求
     * @return 响应
     */
    ChatResponse invoke(Model model, ModelProvider provider, ChatRequest chatRequest);

    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 模型提供者
     * @param chatRequest 请求
     * @return 响应
     */
    Flux<ChatResponse> invokeStream(Model model, ModelProvider provider, ChatRequest chatRequest);

    /**
     * 流式调用模型，返回统一格式的响应块
     *
     * @param model 模型
     * @param provider 模型提供者
     * @param chatRequest 请求
     * @return 响应块
     */
    Flux<StreamChunk> invokeStreamChunk(Model model, ModelProvider provider, ChatRequest chatRequest);
}
