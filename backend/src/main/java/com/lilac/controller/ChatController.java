package com.lilac.controller;

import com.lilac.anonation.RateLimit;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.ApiKey;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ApiKeyService;
import com.lilac.service.ChatService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 聊天接口
 */
@RestController
@RequestMapping("/v1/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatService chatService;
    @Resource
    private ApiKeyService apiKeyService;

    /**
     * 聊天接口
     *
     * @param request 请求体
     * @param authorization 请求头
     * @param httpRequest 请求
     * @return 响应
     */
    @PostMapping(value = "/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @RateLimit(type = RateLimit.LimitType.API_KEY, limit = 60)
    public Object chatCompletions(@RequestBody ChatRequest request, @RequestHeader(value = "Authorization", required = false) String authorization,
                                  HttpServletRequest httpRequest) {
        // 验证 API Key
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "缺少或无效的 Authorization Header");
        }
        String apiKeyValue = authorization.substring(7);
        ApiKey apiKey = apiKeyService.getByKeyValue(apiKeyValue);
        if (apiKey == null) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "API Key 无效或已失效");
        }

        // 参数校验
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "messages 不能为空");
        }

        // 设置默认模型
        if (request.getModel() == null || request.getModel().isEmpty()) {
            request.setModel("qwen-plus");
        }

        // 判断是否为流式请求
        Boolean stream = request.getStream();
        if (stream != null && stream) {
            // 流式响应
            return chatService.chatStream(request, apiKey.getUserId(), apiKey.getId());
        } else {
            // 非流式响应
            return chatService.chat(request, apiKey.getUserId(), apiKey.getId());
        }
    }
}