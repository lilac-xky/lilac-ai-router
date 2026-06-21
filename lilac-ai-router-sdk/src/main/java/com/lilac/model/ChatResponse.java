package com.lilac.model;

import lombok.Data;

import java.util.List;

/**
 * Chat响应
 */
@Data
public class ChatResponse {
    // 响应 ID（如 "chatcmpl-xxx"）
    private String id;
    // 对象类型（固定为 "chat.completion"）
    private String object;
    // 时间戳（秒）
    private Long created;
    // 使用的模型
    private String model;
    // 选项列表
    private List<Choice> choices;
    // Token 使用情况
    private Usage usage;

    @Data
    public static class Choice {
        // 选项索引，默认 0
        private Integer index;
        // AI 消息
        private ChatMessage message;
        // 结束原因（"stop" / "length" / "content_filter"）
        private String finishReason;
    }

    @Data
    public static class Usage {
        // 输入 Token
        private Integer promptTokens;
        // 输出 Token
        private Integer completionTokens;
        // 总 Token
        private Integer totalTokens;
    }

    // 便捷方法：直接获取回复内容
    public String getContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        ChatMessage message = choices.get(0).getMessage();
        return message != null ? message.getContent() : "";
    }
}