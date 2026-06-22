package com.lilac.service.impl;

import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.RequestLog;
import com.lilac.mapper.ModelMapper;
import com.lilac.mapper.RequestLogMapper;
import com.lilac.service.BillingService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 账单服务实现类
 */
@Service
@Slf4j
public class BillingServiceImpl implements BillingService {
    
    @Resource
    private ModelMapper modelMapper;
    @Resource
    private RequestLogMapper requestLogMapper;
    
    /**
     * 每千Token的价格基数
     */
    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000");

    /**
     * 计算本次请求的费用
     *
     * @param model 模型
     * @param promptTokens 提示词的Token数量
     * @param completionTokens 结果的Token数量
     * @return 费用
     */
    @Override
    public BigDecimal calculateCost(Model model, int promptTokens, int completionTokens) {
        if (model == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal inputPrice = model.getInputPrice();
        BigDecimal outputPrice = model.getOutputPrice();
        if (inputPrice == null) {
            inputPrice = BigDecimal.ZERO;
        }
        if (outputPrice == null) {
            outputPrice = BigDecimal.ZERO;
        }
        // 费用 = (输入Token数 * 输入价格 + 输出Token数 * 输出价格) / 1000
        BigDecimal inputCost = inputPrice.multiply(new BigDecimal(promptTokens)).divide(TOKENS_PER_UNIT, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputPrice.multiply(new BigDecimal(completionTokens)).divide(TOKENS_PER_UNIT, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /**
     * 获取用户总费用
     *
     * @param userId 用户ID
     * @return 费用
     */
    @Override
    public BigDecimal getUserTotalCost(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        List<RequestLog> logs = requestLogMapper.selectListByQuery(
                QueryWrapper.create()
                        .where("userId = " + userId)
                        .and("status = 'success'")
        );
        BigDecimal totalCost = BigDecimal.ZERO;
        for (RequestLog log : logs) {
            if (log.getCost() != null) {
                totalCost = totalCost.add(log.getCost());
            }
        }
        return totalCost;
    }

    /**
     * 根据模型ID计算费用
     *
     * @param modelId 模型ID
     * @return 价格
     */
    @Override
    public BigDecimal calculateCost(Long modelId, int promptTokens, int completionTokens) {
        if (modelId == null) {
            return BigDecimal.ZERO;
        }
        Model model = modelMapper.selectOneById(modelId);
        return calculateCost(model, promptTokens, completionTokens);
    }

    /**
     * 获取用户今日费用
     *
     * @param userId 用户ID
     * @return 费用
     */
    @Override
    public BigDecimal getUserTodayCost(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        List<RequestLog> logs = requestLogMapper.selectListByQuery(
                QueryWrapper.create()
                        .where("userId = " + userId)
                        .and("status = 'success'")
                        .and("createTime >= '" + todayStart + "'")
                        .and("createTime <= '" + todayEnd + "'")
        );
        BigDecimal totalCost = BigDecimal.ZERO;
        for (RequestLog log : logs) {
            if (log.getCost() != null) {
                totalCost = totalCost.add(log.getCost());
            }
        }
        return totalCost;
    }
}