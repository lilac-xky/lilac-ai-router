package com.lilac.adapter;

import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 模型适配器接口
 */
public interface ModelAdapter {

    /**
     * 调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 聊天请求
     * @return 聊天响应
     */
    ChatResponse invoke(Model model, ModelProvider provider, ChatRequest chatRequest);

    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 请求
     * @return Prompt
     */
    Flux<ChatResponse> invokeStream(Model model, ModelProvider provider, ChatRequest chatRequest);
    
    /**
     * 流式调用模型
     *
     * @param model 模型
     * @param provider 提供者
     * @param chatRequest 聊天请求
     * @return 聊天响应
     */
    Flux<StreamChunk> invokeStreamChunk(Model model, ModelProvider provider, ChatRequest chatRequest);

    /**
     * 是否支持的提供者
     *
     * @param providerName 提供者名称
     * @return true/false
     */
    boolean supports(String providerName);
}