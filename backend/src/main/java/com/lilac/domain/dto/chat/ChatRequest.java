package com.lilac.domain.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 聊天请求
 */
@Data
public class ChatRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型名称（如：qwen-plus）
     */
    private String model;

    /**
     * 消息列表
     */
    private List<ChatMessage> messages;

    /**
     * 是否流式返回
     */
    private Boolean stream = false;

    /**
     * 温度参数（0-1）
     */
    private Double temperature;

    /**
     * 最大生成Token数
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * 是否启用深度思考
     */
    @JsonProperty("enable_reasoning")
    private Boolean enableReasoning;

    /**
     * 路由策略类型（auto/cost_first/latency_first/round_robin/fixed），为空时由服务端按是否指定模型自动决定
     */
    @JsonProperty("routing_strategy")
    private String routingStrategy;
}