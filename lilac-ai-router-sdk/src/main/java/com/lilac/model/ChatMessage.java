package com.lilac.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 聊天消息
 */
@Data
@AllArgsConstructor
public class ChatMessage {
    
    private String role;     // system / user / assistant
    private String content;  // 消息内容
    
    // 便捷工厂方法
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
    
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
    
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}