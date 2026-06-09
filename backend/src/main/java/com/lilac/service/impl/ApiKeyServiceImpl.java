package com.lilac.service.impl;

import cn.hutool.core.util.IdUtil;
import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.ApiKeyMapper;
import com.lilac.service.ApiKeyService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API Key 服务实现类
 */
@Service
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKey> implements ApiKeyService {

    /**
     * 创建 API Key
     *
     * @param keyName API Key 名称
     * @param loginUser 登录用户
     * @return API Key 对象
     */
    @Override
    public ApiKey createApiKey(String keyName, User loginUser) {
        // 生成 API Key（sk- 前缀 + 32位随机字符）
        String keyValue = "sk-" + IdUtil.simpleUUID();

        // 创建 API Key 对象
        ApiKey apiKey = ApiKey.builder()
                .userId(loginUser.getId())
                .keyValue(keyValue)
                .keyName(keyName)
                .status("active")
                .totalTokens(0L)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        // 保存到数据库
        this.save(apiKey);
        return apiKey;
    }

    /**
     * 列出用户 API Key
     *
     * @param userId 用户 ID
     * @return API Key 列表
     */
    @Override
    public List<ApiKey> listUserApiKeys(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .eq("isDelete", 0)
                .orderBy("createTime", false);
        return this.list(queryWrapper);
    }

    /**
     * 撤销 API Key
     *
     * @param id API Key ID
     * @param userId 用户 ID
     * @return 是否成功
     */
    @Override
    public boolean revokeApiKey(Long id, Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("userId", userId);

        ApiKey apiKey = this.getOne(queryWrapper);
        if (apiKey == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "API Key 不存在");
        }

        // 更新状态为 revoked
        apiKey.setStatus("revoked");
        apiKey.setUpdateTime(LocalDateTime.now());
        return this.updateById(apiKey);
    }

    /**
     * 根据 API Key 值获取 API Key
     *
     * @param keyValue API Key 值
     * @return API Key 对象
     */
    @Override
    public ApiKey getByKeyValue(String keyValue) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("keyValue", keyValue)
                .eq("status", "active")
                .eq("isDelete", 0);
        return this.getOne(queryWrapper);
    }

    /**
     * 更新 API Key 使用统计
     *
     * @param apiKeyId API Key ID
     * @param tokens 使用的 tokens
     */
    @Override
    public void updateUsageStats(Long apiKeyId, Integer tokens) {
        ApiKey apiKey = this.getById(apiKeyId);
        if (apiKey != null) {
            apiKey.setTotalTokens(apiKey.getTotalTokens() + tokens);
            apiKey.setLastUsedTime(LocalDateTime.now());
            apiKey.setUpdateTime(LocalDateTime.now());
            this.updateById(apiKey);
        }
    }
}