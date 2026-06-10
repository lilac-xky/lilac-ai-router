package com.lilac.controller;

import com.lilac.anonation.AuthCheck;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.dto.chat.ChatResponse;
import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ApiKeyService;
import com.lilac.service.ChatService;
import com.lilac.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 内部接口
 */
@RestController
@RequestMapping("/internal/chat")
@Slf4j
public class InternalChatController {

    @Resource
    private ChatService chatService;
    @Resource
    private ApiKeyService apiKeyService;
    @Resource
    private UserService userService;

    /**
     * 内部接口
     *
     * @param request 请求体
     * @param apiKeyId API Key ID
     * @param httpRequest 请求
     * @return 响应
     */
    @PostMapping(value = "/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Object chatCompletions(@RequestBody ChatRequest request, @RequestParam Long apiKeyId, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);

        // 验证 API Key 归属
        ApiKey apiKey = apiKeyService.getById(apiKeyId);
        if (apiKey == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "API Key 不存在");
        }
        if (!apiKey.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(HttpsCodeEnum.UNAUTHORIZED, "无权使用该 API Key");
        }
        if (!"active".equals(apiKey.getStatus())) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "API Key 已失效");
        }

        // 参数校验
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "messages 不能为空");
        }

        // 设置默认模型（如果未指定）
        if (request.getModel() == null || request.getModel().isEmpty()) {
            request.setModel("qwen-plus");
        }

        // 判断是否为流式请求
        Boolean stream = request.getStream();
        if (stream != null && stream) {
            // 流式响应
            return chatService.chatStream(request, loginUser.getId(), apiKey.getId());
        } else {
            // 非流式响应
            ChatResponse response = chatService.chat(request, loginUser.getId(), apiKey.getId());
            return Result.success(response);
        }
    }
}