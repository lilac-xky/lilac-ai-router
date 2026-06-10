package com.lilac.controller;

import cn.hutool.core.bean.BeanUtil;
import com.lilac.anonation.AuthCheck;
import com.lilac.common.DeleteRequest;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.api.ApiKeyCreateRequest;
import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.ApiKeyVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ApiKeyService;
import com.lilac.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * API Key controller
 */
@RestController
@RequestMapping("/api/key")
@Slf4j
public class ApiKeyController {

    @Resource
    private ApiKeyService apiKeyService;
    @Resource
    private UserService userService;

    /**
     * 创建 API Key
     *
     * @param request 请求
     * @return 创建的 API Key
     */
    @PostMapping("/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<ApiKeyVO> createApiKey(@RequestBody ApiKeyCreateRequest request, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        ApiKey apiKey = apiKeyService.createApiKey(request.getKeyName(), loginUser);
        // 转换为 VO（完整显示 Key 值）
        ApiKeyVO apiKeyVO = BeanUtil.copyProperties(apiKey, ApiKeyVO.class);
        return Result.success(apiKeyVO);
    }

    /**
     * 获取我的 API Key 列表
     *
     * @param pageNum  页码
     * @param pageSize 页大小
     * @param request  请求
     * @return 我的 API Key 列表
     */
    @GetMapping("/list/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Page<ApiKeyVO>> listMyApiKeys(@RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "10") long pageSize,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);

        // 分页查询
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", loginUser.getId())
                .eq("isDelete", 0)
                .orderBy("createTime", false);
        Page<ApiKey> apiKeyPage = apiKeyService.page(Page.of(pageNum, pageSize), queryWrapper);

        // 转换为 VO（列表中部分隐藏 Key 值）
        Page<ApiKeyVO> apiKeyVOPage = new Page<>(pageNum, pageSize, apiKeyPage.getTotalRow());
        List<ApiKeyVO> apiKeyVOList = apiKeyPage.getRecords().stream()
                .map(apiKey -> {
                    ApiKeyVO vo = BeanUtil.copyProperties(apiKey, ApiKeyVO.class);
                    // 隐藏部分 Key 值（只显示前8位和后4位）
                    if (vo.getKeyValue() != null && vo.getKeyValue().length() > 12) {
                        String key = vo.getKeyValue();
                        vo.setKeyValue(key.substring(0, 8) + "****" + key.substring(key.length() - 4));
                    }
                    return vo;
                })
                .collect(Collectors.toList());

        apiKeyVOPage.setRecords(apiKeyVOList);
        return Result.success(apiKeyVOPage);
    }


    /**
     * 获取 Token 消耗数
     * 传 apiKeyId 时返回该 API Key 的消耗数；不传时返回当前用户所有 API Key 的累计消耗数
     *
     * @param apiKeyId API Key ID（可选）
     * @param request  请求
     * @return Token 消耗数
     */
    @GetMapping("/token/stats")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Long> getMyTokenStats(@RequestParam(required = false) Long apiKeyId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 指定了 API Key：返回该 Key 的消耗数（校验归属）
        if (apiKeyId != null) {
            ApiKey apiKey = apiKeyService.getById(apiKeyId);
            if (apiKey == null || !apiKey.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "API Key 不存在");
            }
            return Result.success(apiKey.getTotalTokens() == null ? 0L : apiKey.getTotalTokens());
        }
        // 未指定：返回所有 API Key 的累计消耗数
        List<ApiKey> apiKeys = apiKeyService.listUserApiKeys(loginUser.getId());
        long totalTokens = apiKeys.stream()
                .mapToLong(apiKey -> apiKey.getTotalTokens() == null ? 0L : apiKey.getTotalTokens())
                .sum();
        return Result.success(totalTokens);
    }

    /**
     * 撤销 API Key
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 是否撤销成功
     */
    @PostMapping("/revoke")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Boolean> revokeApiKey(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = apiKeyService.revokeApiKey(deleteRequest.getId(), loginUser.getId());
        return Result.success(result);
    }
}