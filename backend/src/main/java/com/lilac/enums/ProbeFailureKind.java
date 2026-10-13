package com.lilac.enums;

import ai.z.openapi.service.model.ZAiHttpException;
import org.springframework.ai.openai.api.common.OpenAiApiClientErrorException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探针失败的分类结果。
 */
public enum ProbeFailureKind {

    // 认证/权限失败（401/403）：Key 失效，重试与等待都不会自愈
    AUTH,
    // 请求本身不合法（400/404/422）：模型名错、路径错、参数错 —— 配置问题，不会自愈
    BAD_REQUEST,
    // 限流（429）：上游是好的，只是暂时让慢一点，不该判它死
    RATE_LIMITED,
    // 上游 5xx：服务端故障，可能自愈
    SERVER_ERROR,
    // 连接层失败：不可达 / 超时 / DNS 失败
    UNREACHABLE,
    // 没拿到可用信号（例如空响应体），无法归类
    UNKNOWN;

    /**
     * 是否应当把这个探针失败归类为「应当排除出候选池」。
     */
    public boolean shouldExcludeFromRouting() {
        return this == AUTH || this == BAD_REQUEST;
    }

    /**
     * 归类一个探针异常。
     */
    public static ProbeFailureKind classify(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ZAiHttpException zai) {
                // 智谱 SDK 不走 Spring AI，只有它真的带状态码字段
                return fromStatusCode(zai.statusCode);
            }
            if (t instanceof WebClientResponseException web) {
                // 非流式链路（WebClient）的对应类型
                return fromStatusCode(web.getStatusCode().value());
            }
            if (t instanceof RestClientResponseException rest) {
                // 非流式链路（RestClient）的对应类型
                return fromStatusCode(rest.getStatusCode().value());
            }
            if (t instanceof OpenAiApiClientErrorException) {
                // OpenAI 的 4xx 是独立类型，不继承 NonTransientAiException；具体码在消息里
                int code = leadingStatusCode(t.getMessage());
                return code > 0 ? fromStatusCode(code) : BAD_REQUEST;
            }
            if (t instanceof NonTransientAiException) {
                int code = leadingStatusCode(t.getMessage());
                return code > 0 ? fromStatusCode(code) : BAD_REQUEST;
            }
            if (t instanceof TransientAiException) {
                int code = leadingStatusCode(t.getMessage());
                // 没有状态码的 TransientAiException 按服务端故障看待：它本来就是「可重试」那一类
                return code > 0 ? fromStatusCode(code) : SERVER_ERROR;
            }
            if (t instanceof ResourceAccessException) {
                return UNREACHABLE;
            }
            if (t instanceof TimeoutException) {
                // Reactor 的 .timeout() 抛的是这个：上游在超时内一个字都没吐，等价于不可达
                return UNREACHABLE;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return UNKNOWN;
    }

    private static ProbeFailureKind fromStatusCode(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return AUTH;
        }
        if (statusCode == 429) {
            return RATE_LIMITED;
        }
        if (statusCode >= 500) {
            return SERVER_ERROR;
        }
        if (statusCode >= 400) {
            return BAD_REQUEST;
        }
        return UNKNOWN;
    }

    /** 消息形如 "401 Unauthorized - {...}"，取开头那个状态码 */
    private static final Pattern LEADING_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");

    private static int leadingStatusCode(String message) {
        if (message == null) {
            return -1;
        }
        Matcher matcher = LEADING_STATUS.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
