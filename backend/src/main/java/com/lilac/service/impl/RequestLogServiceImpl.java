package com.lilac.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lilac.domain.entity.RequestLog;
import com.lilac.mapper.RequestLogMapper;
import com.lilac.service.ApiKeyService;
import com.lilac.service.BillingService;
import com.lilac.domain.dto.log.RequestLogQueryRequest;
import com.lilac.service.RequestLogService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求日志服务实现类
 */
@Service
public class RequestLogServiceImpl implements RequestLogService {

    @Resource
    private RequestLogMapper requestLogMapper;
    @Resource
    private ApiKeyService apiKeyService;
    @Resource
    private BillingService billingService;


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
        logRequest(userId, apiKeyId, null, modelName, promptTokens, completionTokens, totalTokens, duration, status, errorMessage);
    }

    /**
     * 记录请求日志（带模型ID，自动计算并记录费用）
     */
    @Override
    @Async
    public void logRequest(Long userId, Long apiKeyId, Long modelId, String modelName, Integer promptTokens, Integer completionTokens,
                           Integer totalTokens, Integer duration, String status, String errorMessage) {
        // 计算本次请求费用（仅成功且有 Token 消耗时）
        BigDecimal cost = BigDecimal.ZERO;
        if ("success".equals(status) && modelId != null && totalTokens != null && totalTokens > 0) {
            cost = billingService.calculateCost(modelId,
                    promptTokens != null ? promptTokens : 0,
                    completionTokens != null ? completionTokens : 0);
        }
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
                .cost(cost)
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

    /**
     * 获取用户的请求数量
     *
     * @param userId 用户 ID
     * @return 用户的请求数量
     */
    @Override
    public Long countUserRequests(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId);
        return requestLogMapper.selectCountByQuery(queryWrapper);
    }

    /**
     * 获取用户的成功请求数量
     *
     * @param userId 用户 ID
     * @return 用户的成功请求数量
     */
    @Override
    public Long countUserSuccessRequests(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .eq("status", "success");
        return requestLogMapper.selectCountByQuery(queryWrapper);
    }

    /**
     * 获取用户的请求统计信息
     *
     * @param userId 用户 ID
     * @return 用户的请求统计信息
     */
    @Override
    public List<Map<String, Object>> getUserDailyStats(Long userId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            LocalDateTime dayStart = LocalDateTime.of(currentDate, LocalTime.MIN);
            LocalDateTime dayEnd = LocalDateTime.of(currentDate, LocalTime.MAX);
            List<RequestLog> logs = requestLogMapper.selectListByQuery(
                    QueryWrapper.create()
                            .where("userId = " + userId)
                            .and("createTime >= '" + dayStart + "'")
                            .and("createTime <= '" + dayEnd + "'")
            );
            long totalTokens = 0;
            long requestCount = 0;
            long successCount = 0;
            BigDecimal totalCost = BigDecimal.ZERO;
            for (RequestLog log : logs) {
                requestCount++;
                if ("success".equals(log.getStatus())) {
                    successCount++;
                    if (log.getTotalTokens() != null) {
                        totalTokens += log.getTotalTokens();
                    }
                    if (log.getCost() != null) {
                        totalCost = totalCost.add(log.getCost());
                    }
                }
            }
            Map<String, Object> dayStats = new HashMap<>();
            dayStats.put("date", currentDate.toString());
            dayStats.put("totalTokens", totalTokens);
            dayStats.put("requestCount", requestCount);
            dayStats.put("successCount", successCount);
            dayStats.put("totalCost", totalCost);
            result.add(dayStats);
            currentDate = currentDate.plusDays(1);
        }
        return result;
    }

    /**
     * 分页查询请求日志
     *
     * @param queryRequest 查询参数
     * @return 分页结果
     */
    @Override
    public Page<RequestLog> pageByQuery(RequestLogQueryRequest queryRequest) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 用户ID条件
        if (queryRequest.getUserId() != null) {
            queryWrapper.eq("userId", queryRequest.getUserId());
        }
        // 模型标识
        if (StrUtil.isNotBlank(queryRequest.getRequestModel())) {
            queryWrapper.like("requestModel", queryRequest.getRequestModel());
        }
        // 请求类型
        if (StrUtil.isNotBlank(queryRequest.getRequestType())) {
            queryWrapper.eq("requestType", queryRequest.getRequestType());
        }
        // 调用来源
        if (StrUtil.isNotBlank(queryRequest.getSource())) {
            queryWrapper.eq("source", queryRequest.getSource());
        }
        // 状态
        if (StrUtil.isNotBlank(queryRequest.getStatus())) {
            queryWrapper.eq("status", queryRequest.getStatus());
        }
        // 日期范围
        if (StrUtil.isNotBlank(queryRequest.getStartDate())) {
            LocalDateTime startTime = LocalDateTime.of(LocalDate.parse(queryRequest.getStartDate()), LocalTime.MIN);
            queryWrapper.ge("createTime", startTime);
        }
        if (StrUtil.isNotBlank(queryRequest.getEndDate())) {
            LocalDateTime endTime = LocalDateTime.of(LocalDate.parse(queryRequest.getEndDate()), LocalTime.MAX);
            queryWrapper.le("createTime", endTime);
        }
        // 按创建时间倒序
        queryWrapper.orderBy("createTime", false);
        long pageNum = queryRequest.getPageNum();
        long pageSize = queryRequest.getPageSize();
        return requestLogMapper.paginate(Page.of(pageNum, pageSize), queryWrapper);
    }

    /**
     * 根据ID查询日志
     *
     * @param id 日志ID
     * @return 日志
     */
    @Override
    public RequestLog getById(Long id) {
        if (id == null) {
            return null;
        }
        return requestLogMapper.selectOneById(id);
    }
}