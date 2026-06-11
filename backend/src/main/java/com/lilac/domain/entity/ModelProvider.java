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
@Table("model_provider")
public class ModelProvider {

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 提供者名称（如：qwen/zhipu/deepseek）
     */
    @Column(value = "providerName")
    private String providerName;

    /**
     * 显示名称（如：通义千问/智谱AI/DeepSeek）
     */
    @Column(value = "displayName")
    private String displayName;

    /**
     * API基础URL
     */
    @Column(value = "baseUrl")
    private String baseUrl;

    /**
     * API密钥
     */
    @Column(value = "apiKey")
    private String apiKey;

    /**
     * 状态：active/inactive/maintenance
     */
    @Column(value = "status")
    private String status;

    /**
     * 健康状态：healthy/unhealthy/degraded/unknown
     */
    @Column(value = "healthStatus")
    private String healthStatus;

    /**
     * 平均延迟（毫秒）
     */
    @Column(value = "avgLatency")
    private Integer avgLatency;

    /**
     * 成功率（百分比）
     */
    @Column(value = "successRate")
    private BigDecimal successRate;

    /**
     * 优先级（越大越优先）
     */
    @Column(value = "priority")
    private Integer priority;

    /**
     * 额外配置（JSON格式）
     */
    @Column(value = "config")
    private String config;

    /**
     * 创建时间
     */
    @Column(value = "createTime")
    private Date createTime;

    /**
     * 更新时间
     */
    @Column(value = "updateTime")
    private Date updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}