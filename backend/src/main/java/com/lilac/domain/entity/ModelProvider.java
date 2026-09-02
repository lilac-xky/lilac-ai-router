package com.lilac.domain.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型提供者
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "model_provider", camelToUnderline = false)
public class ModelProvider {

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 提供者名称（如：qwen/zhipu/deepseek）
     */
    private String providerName;

    /**
     * 显示名称（如：通义千问/智谱AI/DeepSeek）
     */
    private String displayName;

    /**
     * API基础URL
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 状态：active/inactive/maintenance
     */
    private String status;

    /**
     * 健康状态：healthy/unhealthy/degraded/unknown
     */
    private String healthStatus;

    /**
     * 平均延迟（毫秒）
     */
    private Integer avgLatency;

    /**
     * 成功率（百分比）
     */
    private BigDecimal successRate;

    /**
     * 优先级（越大越优先）
     */
    private Integer priority;

    /**
     * 额外配置（JSON格式）
     */
    private String config;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @Column(isLogicDelete = true)
    private Integer isDelete;

}