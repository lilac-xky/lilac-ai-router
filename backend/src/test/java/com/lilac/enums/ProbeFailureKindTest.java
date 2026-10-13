package com.lilac.enums;

import ai.z.openapi.service.model.ZAiHttpException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.common.OpenAiApiClientErrorException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针失败分类：真健康检查的全部价值都压在「能不能把这几类错误分开」上。
 *
 * <p>纯单测，不起 Spring 上下文。异常消息用的是**实测到的真实形状**
 * （见 {@code ChatClientRetryConfigTest} 与 {@code backend/logs} 里的日志）：
 * {@code RetryUtils} 用 {@code String.format("%s - %s", statusCode, body)} 拼消息，
 * 所以状态码一定在消息开头。
 *
 * <h3>为什么必须锁住「限流不算故障」</h3>
 * 如果 429 也被判成故障，那么正常限流的厂商会被踢出候选池 —— 而限流恰恰说明它活着。
 * 反过来，如果 401 被判成「健康」，又会退回坑 4「配置错误被兜底逻辑伪装成成功」。
 * 两个方向都会让这个功能变成负资产，所以两个方向都要有断言。
 */
class ProbeFailureKindTest {

    @Test
    @DisplayName("401 / 403 —— Key 失效，必须判死（等多久都不会自愈）")
    void authFailures_excluded() {
        assertThat(ProbeFailureKind.classify(new NonTransientAiException("401 Unauthorized - {\"error\":\"invalid api key\"}")))
                .isEqualTo(ProbeFailureKind.AUTH);
        assertThat(ProbeFailureKind.classify(new NonTransientAiException("403 AllocationQuota.FreeTierOnly")))
                .isEqualTo(ProbeFailureKind.AUTH);

        assertThat(ProbeFailureKind.AUTH.shouldExcludeFromRouting())
                .as("Key 失效的厂商留在候选池里，只会让每个请求都白跑一次上游")
                .isTrue();
    }

    @Test
    @DisplayName("400 / 404 —— 模型名或路径错，必须判死")
    void badRequests_excluded() {
        assertThat(ProbeFailureKind.classify(new NonTransientAiException("400 Bad Request - model not found")))
                .isEqualTo(ProbeFailureKind.BAD_REQUEST);
        assertThat(ProbeFailureKind.classify(new NonTransientAiException("404 Not Found - ")))
                .isEqualTo(ProbeFailureKind.BAD_REQUEST);

        assertThat(ProbeFailureKind.BAD_REQUEST.shouldExcludeFromRouting())
                .as("模型名写错属于坑 4 那类配置错误，必须暴露出来而不是被当成健康")
                .isTrue();
    }

    @Test
    @DisplayName("429 —— 限流不算故障，不能踢出候选池")
    void rateLimited_keptInRouting() {
        assertThat(ProbeFailureKind.classify(new TransientAiException("429 Too Many Requests")))
                .isEqualTo(ProbeFailureKind.RATE_LIMITED);

        assertThat(ProbeFailureKind.RATE_LIMITED.shouldExcludeFromRouting())
                .as("限流说明上游活着，判死会让正常厂商在高峰期被踢出去")
                .isFalse();
    }

    @Test
    @DisplayName("5xx —— 服务端故障可能自愈，交给熔断器收敛")
    void serverErrors_keptButDegraded() {
        assertThat(ProbeFailureKind.classify(new TransientAiException("503 Service Unavailable - ")))
                .isEqualTo(ProbeFailureKind.SERVER_ERROR);
        assertThat(ProbeFailureKind.SERVER_ERROR.shouldExcludeFromRouting()).isFalse();
    }

    @Test
    @DisplayName("连接层失败 —— 不可达")
    void connectionFailure_unreachable() {
        assertThat(ProbeFailureKind.classify(new ResourceAccessException("I/O error on POST request for \"http://127.0.0.1:9/chat/completions\": Connection refused")))
                .isEqualTo(ProbeFailureKind.UNREACHABLE);
    }

    @Test
    @DisplayName("异常被包了一层也要认出来（任务 J 那个坑：包装吃掉类型）")
    void wrappedException_stillClassified() {
        // 适配器统一包成 BusinessException，真实原因在 cause 链上
        Throwable wrapped = new RuntimeException("调用模型失败: boom",
                new RuntimeException("wrapper",
                        new NonTransientAiException("401 Unauthorized - bad key")));

        assertThat(ProbeFailureKind.classify(wrapped))
                .as("只看最外层类型的话，4xx 与超时会退化成同一个「未知」")
                .isEqualTo(ProbeFailureKind.AUTH);
    }

    @Test
    @DisplayName("智谱 SDK 直接读状态码字段（它不走 Spring AI，是唯一带码的）")
    void zhipuException_usesStatusCodeField() {
        assertThat(ProbeFailureKind.classify(new ZAiHttpException("401", "unauthorized", null, 401)))
                .isEqualTo(ProbeFailureKind.AUTH);
        assertThat(ProbeFailureKind.classify(new ZAiHttpException("429", "rate limited", null, 429)))
                .isEqualTo(ProbeFailureKind.RATE_LIMITED);
        assertThat(ProbeFailureKind.classify(new ZAiHttpException("500", "server error", null, 500)))
                .isEqualTo(ProbeFailureKind.SERVER_ERROR);
    }

    @Test
    @DisplayName("OpenAI 的 4xx 是独立类型，不继承 NonTransientAiException，也不能漏")
    void openAiClientError_classified() {
        assertThat(ProbeFailureKind.classify(new OpenAiApiClientErrorException("400 - invalid model")))
                .isEqualTo(ProbeFailureKind.BAD_REQUEST);
    }

    @Test
    @DisplayName("认不出来的异常归 UNKNOWN，不武断判死")
    void unknown_staysUnknown() {
        assertThat(ProbeFailureKind.classify(new IllegalStateException("something odd")))
                .isEqualTo(ProbeFailureKind.UNKNOWN);
        assertThat(ProbeFailureKind.UNKNOWN.shouldExcludeFromRouting())
                .as("认不出来就不该判死，否则一个未知异常会让厂商静默消失")
                .isFalse();
    }

    @Test
    @DisplayName("Reactor 超时（TimeoutException）算不可达，不能退化成 UNKNOWN")
    void reactorTimeout_unreachable() {
        // 上游在超时窗口内一个字都没吐 —— 与连接被拒同样是「不可达」，不该留在候选池里白跑
        assertThat(ProbeFailureKind.classify(new TimeoutException(
                "Did not observe any item or terminal signal within 10000ms")))
                .isEqualTo(ProbeFailureKind.UNREACHABLE);
    }

    @Test
    @DisplayName("超时被包了一层也要认出来")
    void wrappedTimeout_unreachable() {
        Throwable wrapped = new RuntimeException("timeout",
                new TimeoutException("Did not observe any item"));

        assertThat(ProbeFailureKind.classify(wrapped)).isEqualTo(ProbeFailureKind.UNREACHABLE);
    }

    @Test
    @DisplayName("WebClient 4xx（流式链路）必须按状态码归类 —— 实测漏掉它时 401 变成了 UNKNOWN")
    void webClientResponseException_classifiedByStatusCode() {
        // 实测证据：注入无效 Key 后，真探针确实抓到了 401（耗时 204ms），
        // 但因为分类器没处理 WebClientResponseException，被归成 UNKNOWN → deepseek 仍是 healthy。
        // 盲区只修了一半，这个用例就是那次实测的回归。
        org.springframework.web.reactive.function.client.WebClientResponseException unauthorized =
                org.springframework.web.reactive.function.client.WebClientResponseException.create(
                        401, "Unauthorized", org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], java.nio.charset.StandardCharsets.UTF_8, null);

        assertThat(ProbeFailureKind.classify(unauthorized))
                .as("流式探针走的正是 WebClient，漏掉这个分支等于盲区没修")
                .isEqualTo(ProbeFailureKind.AUTH);
        assertThat(ProbeFailureKind.AUTH.shouldExcludeFromRouting()).isTrue();
    }
}
