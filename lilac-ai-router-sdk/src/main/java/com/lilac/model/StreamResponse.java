package com.lilac.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 流式响应
 */
@Data
@Builder
public class StreamResponse {
    /**
     * 本次对话的唯一标识
     */
    private String id;

    /**
     * 固定值 "chat.completion.chunk"
     */
    private String object;

    /**
     * 时间戳（秒）
     */
    private Long created;

    /**
     * 使用的模型
     */
    private String model;

    /**
     * 选项列表
     */
    private List<StreamChoice> choices;

    @Data
    @Builder
    public static class StreamChoice {
        /**
         * 选项索引，默认 0
         */
        private Integer index;

        /**
         * 增量内容
         */
        private Delta delta;

        /**
         * 结束原因（"stop" 表示完成）
         */
        private String finishReason;
    }

    @Data
    @Builder
    public static class Delta {
        /**
         * 角色（首个块包含，值为 "assistant"）
         */
        private String role;

        /**
         * 答案内容（增量）
         */
        private String content;

        /**
         * 思考过程（增量，deepseek-reasoner 专属）
         */
        private String reasoningContent;
    }
}