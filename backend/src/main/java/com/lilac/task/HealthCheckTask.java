package com.lilac.task;

import com.lilac.service.HealthCheckService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 健康检查定时任务
 *
 * <p>开关：{@code lilac.health-check.enabled}，<b>默认开启</b>（{@code matchIfMissing = true}），
 * 因此生产与开发行为不变。集成测试里显式置 false（见 {@code src/test/resources/application-test.yml}），
 * 原因有两个：
 * <ol>
 *   <li>{@link HealthCheckService#checkAllProviders()} 会顺带执行模型指标同步，
 *       其查询是 {@code WHERE status = 'active' AND isDelete = 0}，<b>没有 modelType 过滤</b>，
 *       会把测试插入的种子模型一起改写（healthStatus / avgLatency / score），
 *       导致依赖这些字段的用例时红时绿；</li>
 *   <li>该任务会对所有 provider 发真实请求，测试期间会持续产生 token 费用。</li>
 * </ol>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "lilac.health-check.enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckTask {
    
    @Resource
    private HealthCheckService healthCheckService;
    
    /**
     * 每 30 秒执行一次健康检查
     */
    @Scheduled(fixedRate = 30000)
    public void executeHealthCheck() {
        log.info("=== 开始执行健康检查 ===");
        healthCheckService.checkAllProviders();
        log.info("=== 健康检查完成 ===");
    }
}