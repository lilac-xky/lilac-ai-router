package com.lilac.service.impl;

import com.lilac.domain.entity.RequestLog;
import com.lilac.mapper.RequestLogMapper;
import com.lilac.service.ApiKeyService;
import com.lilac.service.RequestLogService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求日志服务实现类
 */
@Service
public class RequestLogServiceImpl implements RequestLogService {

    @Resource
    private RequestLogMapper requestLogMapper;
    @Resource
    private ApiKeyService apiKeyService;


    /**
     * 记录请求日志
     *
     * @param userId 用户 ID
     * @param apiKeyId API Key ID
     * @param modelName 模型名称
     * @param promptTokens 提示 tokens
     * @param completionTokens 完成 tokens
     * @param totalTokens 总 tokens
     * @param duration 耗时
     * @param status 状态
     * @param errorMessage 错误信息
     */
    @Override
    @Async
    public void logRequest(Long userId, Long apiKeyId, String modelName, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                           Integer duration, String status, String errorMessage) {
        // 创建请求日志
        RequestLog log = RequestLog.builder()
                .userId(userId)
                .apiKeyId(apiKeyId)
                .modelName(modelName)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .duration(duration)
                .status(status)
                .errorMessage(errorMessage)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        // 保存日志
        requestLogMapper.insert(log);
        // 更新 API Key 的使用统计（仅成功的请求）
        if ("success".equals(status) && apiKeyId != null && totalTokens != null && totalTokens > 0) {
            apiKeyService.updateUsageStats(apiKeyId, totalTokens);
        }
    }

    /**
     * 获取用户的请求日志
     *
     * @param userId 用户 ID
     * @param limit 限制数量
     * @return 用户的请求日志列表
     */
    @Override
    public List<RequestLog> listUserLogs(Long userId, Integer limit) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .orderBy("createTime", false)
                .limit(limit != null ? limit : 100);
        return requestLogMapper.selectListByQuery(queryWrapper);
    }

    /**
     * 获取用户的总 tokens
     *
     * @param userId 用户 ID
     * @return 用户的总 tokens
     */
    @Override
    public Long countUserTokens(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .select("SUM(totalTokens)")
                .eq("userId", userId)
                .eq("status", "success");
        Long total = requestLogMapper.selectObjectByQueryAs(queryWrapper, Long.class);
        return total != null ? total : 0L;
    }
}