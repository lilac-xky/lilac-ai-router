package com.lilac.service;

import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * API Key 服务
 */
public interface ApiKeyService extends IService<ApiKey> {

    /**
     * 创建 API Key
     *
     * @param keyName API Key 名称
     * @param loginUser 登录用户
     * @return 创建的 API Key
     */
    ApiKey createApiKey(String keyName, User loginUser);

    /**
     * 获取用户的 API Key
     *
     * @param userId 用户 ID
     * @return 用户的 API Key 列表
     */
    List<ApiKey> listUserApiKeys(Long userId);

    /**
     * 撤销 API Key
     *
     * @param id API Key ID
     * @param userId 用户 ID
     * @return 是否撤销成功
     */
    boolean revokeApiKey(Long id, Long userId);

    /**
     * 根据 API Key 值获取 API Key
     *
     * @param keyValue API Key 值
     * @return API Key
     */
    ApiKey getByKeyValue(String keyValue);

    /**
     * 更新 API Key 的使用统计
     *
     * @param apiKeyId API Key ID
     * @param tokens 使用的 tokens
     */
    void updateUsageStats(Long apiKeyId, Integer tokens);
}