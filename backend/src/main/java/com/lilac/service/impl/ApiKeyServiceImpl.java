package com.lilac.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.ApiKeyMapper;
import com.lilac.service.ApiKeyService;
import com.lilac.utils.ApiKeyHasher;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API Key 服务实现类
 */
@Service
@Slf4j
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKey> implements ApiKeyService {

    @Resource
    private ApiKeyMapper apiKeyMapper;

    /**
     * 创建 API Key
     *
     * @param keyName API Key 名称
     * @param loginUser 登录用户
     * @return API Key 对象
     */
    @Override
    public ApiKey createApiKey(String keyName, User loginUser) {
        if (StrUtil.isBlank(keyName)) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "API Key 名称不能为空");
        }
        // 生成 API Key（sk- 前缀 + 32位随机字符）
        String keyValue = "sk-" + IdUtil.simpleUUID();

        // 创建 API Key 对象
        ApiKey apiKey = ApiKey.builder()
                .userId(loginUser.getId())
                .keyValue(keyValue)
                .keyHash(ApiKeyHasher.sha256Hex(keyValue))
                .keyPrefix(ApiKeyHasher.prefix(keyValue))
                .keySuffix(ApiKeyHasher.suffix(keyValue))
                .keyName(keyName.trim())
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
     * 轮换 API Key：原地换一把新 Key，名称与用量统计保留。
     *
     * <p>这是「哈希存储」的配套能力 —— 明文不可逆，用户丢了 Key 就只能换一把，
     * 不能查回原来那把。主流网关（OpenAI / AWS / GitHub）都是这个模型。
     *
     * <p>刻意<b>原地更新而不是新建一行</b>：新建会让「同一个用途不小心攒出十几把 Key」，
     * 而用户的本意是「原来那把作废、换一把能用的」。用量统计（totalTokens）
     * 也留在同一行上，便于继续观察这个用途的消耗。
     */
    @Override
    public ApiKey rotateApiKey(Long id, Long userId) {
        ApiKey existing = this.getOne(QueryWrapper.create()
                .eq("id", id)
                .eq("userId", userId)
                .eq("isDelete", 0));
        if (existing == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "API Key 不存在");
        }

        String newKeyValue = "sk-" + IdUtil.simpleUUID();
        // 只更新哈希/前缀/后缀与状态：旧明文立刻失效（哈希变了，按旧 Key 查不到）
        ApiKey update = UpdateEntity.of(ApiKey.class, id);
        update.setKeyHash(ApiKeyHasher.sha256Hex(newKeyValue));
        update.setKeyPrefix(ApiKeyHasher.prefix(newKeyValue));
        update.setKeySuffix(ApiKeyHasher.suffix(newKeyValue));
        update.setStatus("active");
        update.setUpdateTime(LocalDateTime.now());
        this.updateById(update);

        // 返回带明文的实体（实体上标了 @Column(ignore=true)，不会写回库），供调用方展示一次
        existing.setKeyValue(newKeyValue);
        existing.setKeyHash(update.getKeyHash());
        existing.setKeyPrefix(update.getKeyPrefix());
        existing.setKeySuffix(update.getKeySuffix());
        existing.setStatus("active");
        log.info("API Key 已轮换：id={} userId={} 新前缀={}", id, userId, update.getKeyPrefix());
        return existing;
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
        if (StrUtil.isBlank(keyValue)) {
            return null;
        }
        // 按哈希查：库里已不存明文，这是唯一的鉴权入口
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("keyHash", ApiKeyHasher.sha256Hex(keyValue))
                .eq("status", "active")
                .eq("isDelete", 0);
        return this.getOne(queryWrapper);
    }

    /**
     * 更新 API Key 使用统计
     *
     * @param apiKeyId API Key ID
     * @param tokens   使用的 Token 数
     */
    @Override
    public void updateUsageStats(Long apiKeyId, Integer tokens) {
        if (apiKeyId == null || tokens == null || tokens <= 0) {
            return;
        }
        try {
            int affected = apiKeyMapper.increaseTokensAtomically(apiKeyId, tokens);
            if (affected == 0) {
                // 记录 API Key 用量未生效：id={} 不存在或已删除，本次 {} Token 未入账
                log.warn("记录 API Key 用量未生效：id={} 不存在或已删除，本次 {} Token 未入账", apiKeyId, tokens);
            }
        } catch (Exception e) {
            // 记录 API Key 用量失败，需人工核对
            log.error("记录 API Key 用量失败，需人工核对：id={} tokens={}", apiKeyId, tokens, e);
        }
    }
}