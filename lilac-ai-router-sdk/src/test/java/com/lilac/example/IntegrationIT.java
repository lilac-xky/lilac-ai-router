package com.lilac.example;

import com.lilac.LilacAIClient;
import com.lilac.model.ChatResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

public class IntegrationIT {

    private static final String BASE_URL = "http://localhost:9090/api";
    private static final String HOST = "localhost";
    private static final int PORT = 9090;

    /**
     * 前置检查：本地 9090 是否跑着 lilac-ai-router 服务。
     * 没跑就让测试自动跳过，而不是失败——否则 mvn install / verify 会在没起服务时直接挂掉。
     */
    private static boolean routerIsUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void testSimple() {
        Assumptions.assumeTrue(routerIsUp(),
                "跳过：本地 " + PORT + " 端口没有运行 lilac-ai-router，或未配置可用 API Key");

        // 创建客户端
        LilacAIClient client = LilacAIClient.builder()
                .apiKey("sk-xx")  // 替换为你的 API Key
                .baseUrl(BASE_URL)
                .build();

        try {
            // 同步调用
            ChatResponse response = client.chat("你好，请介绍一下自己");
            System.out.println("响应: " + response.getContent());

            // Token 使用统计
            System.out.println("\nToken 统计:");
            System.out.println("输入: " + response.getUsage().getPromptTokens());
            System.out.println("输出: " + response.getUsage().getCompletionTokens());
            System.out.println("总计: " + response.getUsage().getTotalTokens());

        } finally {
            // 关闭客户端
            client.close();
        }
    }
}
