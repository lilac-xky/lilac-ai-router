package com.lilac.controller;

import com.lilac.anonation.AuthCheck;
import com.lilac.constant.UserConstant;
import com.lilac.domain.result.Result;
import com.lilac.service.BlacklistService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 黑名单控制器
 */
@RestController
@RequestMapping("/admin/blacklist")
public class BlacklistController {

    @Resource
    private BlacklistService blacklistService;

    /**
     * 获取黑名单列表
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.USER)
    public Result<Set<String>> getBlacklist() {
        Set<String> blacklist = blacklistService.getAllBlacklist();
        return Result.success(blacklist);
    }

    /**
     * 添加 IP 到黑名单
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> addToBlacklist(@RequestBody BlacklistRequest request) {
        blacklistService.addToBlacklist(request.getIp(), request.getReason());
        return Result.success(true);
    }

    /**
     * 从黑名单移除 IP
     */
    @PostMapping("/remove")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> removeFromBlacklist(@RequestBody BlacklistRequest request) {
        blacklistService.removeFromBlacklist(request.getIp());
        return Result.success(true);
    }

    /**
     * 检查 IP 是否在黑名单中
     */
    @GetMapping("/check")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> checkBlacklist(@RequestParam String ip) {
        boolean blocked = blacklistService.isBlocked(ip);
        return Result.success(blocked);
    }

    /**
     * 获取黑名单数量
     */
    @GetMapping("/count")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Long> getBlacklistCount() {
        long count = blacklistService.getBlacklistCount();
        return Result.success(count);
    }

    /**
     * 黑名单请求对象
     */
    @Data
    public static class BlacklistRequest {
        /**
         * IP 地址
         */
        private String ip;
        /**
         * 封禁原因
         */
        private String reason;
    }
}