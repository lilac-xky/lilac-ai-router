package com.lilac.service;

import com.lilac.domain.entity.Model;

import java.math.BigDecimal;

/**
 * 账单服务接口
 */
public interface BillingService {
    /**
     * 计算本次请求的费用
     *
     * @param model 模型
     * @param promptTokens 提示词的Token数量
     * @param completionTokens 结果的Token数量
     * @return 费用
     */
    BigDecimal calculateCost(Model model, int promptTokens, int completionTokens);
    
    /**
     * 根据模型ID计算费用
     *
     * @param modelId 模型ID
     * @return 价格
     */
    BigDecimal calculateCost(Long modelId, int promptTokens, int completionTokens);

    /**
     * 获取用户总费用
     *
     * @param userId 用户ID
     * @return 费用
     */
    BigDecimal getUserTotalCost(Long userId);
    
    /**
     * 获取用户今日费用
     *
     * @param userId 用户ID
     * @return 费用
     */
    BigDecimal getUserTodayCost(Long userId);
}