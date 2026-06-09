package com.lilac.service;

import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.dto.chat.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 聊天服务
 */
public interface ChatService {

    /**
     * 非流式聊天
     *
     * @param chatRequest 聊天请求参数
     * @param userId 用户ID
     * @param apiKeyId API密钥ID
     * @return 响应结果
     */
    ChatResponse chat(ChatRequest chatRequest, Long userId, Long apiKeyId);

    /**
     * 流式聊天
     *
     * @param chatRequest 聊天请求参数
     * @param userId 用户ID
     * @param apiKeyId API密钥ID
     * @return 响应结果
     */
    Flux<String> chatStream(ChatRequest chatRequest, Long userId, Long apiKeyId);
}
