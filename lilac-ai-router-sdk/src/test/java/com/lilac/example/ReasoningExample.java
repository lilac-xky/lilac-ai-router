package com.lilac.example;

import com.lilac.LilacAIClient;
import com.lilac.callback.StreamCallback;
import com.lilac.model.ChatChunk;
import com.lilac.model.ChatMessage;
import com.lilac.model.ChatRequest;

import java.util.Arrays;

public class ReasoningExample {

    public static void main(String[] args) throws InterruptedException {
        LilacAIClient client = LilacAIClient.builder()
                .apiKey("sk-xxxx")
                .baseUrl("http://localhost:9090/api")
                .build();

        try {
            System.out.println("=== 深度思考模式示例 ===\n");

            // 构建启用深度思考的请求
            ChatRequest request = ChatRequest.builder()
                    .model("qwen-plus")  // 支持深度思考的模型
                    .messages(Arrays.asList(
                            ChatMessage.user("请详细解释量子纠缠现象，并给出实际应用")
                    ))
                    .enableReasoning(true)   // 启用深度思考
                    .temperature(0.7)
                    .build();

            client.chatStream(request, new StreamCallback() {
                @Override
                public void onMessage(ChatChunk chunk) {
                    // 分别处理思考内容和普通内容
                    if (chunk.getReasoningContent() != null) {
                        System.out.println("\n💭 [思考] " + chunk.getReasoningContent());
                    }

                    if (chunk.getContent() != null) {
                        System.out.print(chunk.getContent());
                    }
                }

                @Override
                public void onComplete() {
                    System.out.println("\n\n✅ 完成");
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("\n❌ 错误: " + error.getMessage());
                    error.printStackTrace();
                }
            });

            // 等待流式响应完成
            Thread.sleep(30000);

        } finally {
            client.close();
        }
    }
}