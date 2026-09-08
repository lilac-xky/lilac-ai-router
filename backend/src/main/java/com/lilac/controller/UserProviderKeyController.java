package com.lilac.controller;

import com.lilac.annotation.AuthCheck;
import com.lilac.common.DeleteRequest;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.byok.UserProviderKeyAddRequest;
import com.lilac.domain.dto.byok.UserProviderKeyUpdateRequest;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.UserProviderKeyVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.UserProviderKeyService;
import com.lilac.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户提供者密钥控制类
 */
@Slf4j
@RestController
@RequestMapping("/byok")
public class UserProviderKeyController {

    @Resource
    private UserProviderKeyService userProviderKeyService;
    @Resource
    private UserService userService;

    /**
     * 添加用户提供者密钥
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Boolean> addUserProviderKey(@RequestBody UserProviderKeyAddRequest request, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        boolean result = userProviderKeyService.addUserProviderKey(request, loginUser.getId());
        return Result.success(result);
    }

    /**
     * 更新用户提供者密钥
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Boolean> updateUserProviderKey(@RequestBody UserProviderKeyUpdateRequest request, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        boolean result = userProviderKeyService.updateUserProviderKey(request, loginUser.getId());
        return Result.success(result);
    }

    /**
     * 删除用户提供者密钥
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Boolean> deleteUserProviderKey(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(httpRequest);
        boolean result = userProviderKeyService.deleteUserProviderKey(request.getId(), loginUser.getId());
        return Result.success(result);
    }

    /**
     * 获取我的提供者密钥列表
     */
    @GetMapping("/my/list")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<List<UserProviderKeyVO>> listMyProviderKeys(HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        List<UserProviderKeyVO> list = userProviderKeyService.listUserProviderKeys(loginUser.getId());
        return Result.success(list);
    }
}