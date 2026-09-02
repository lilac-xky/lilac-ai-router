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
 * 模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "model", camelToUnderline = false)
public class Model {

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 提供者id
     */
    private Long providerId;

    /**
     * 模型标识（如：qwen-plus）
     */
    private String modelKey;

    /**
     * 模型显示名称
     */
    private String modelName;

    /**
     * 模型类型：chat/embedding/image/audio
     */
    private String modelType;

    /**
     * 模型描述
     */
    private String description;

    /**
     * 上下文长度限制
     */
    private Integer contextLength;

    /**
     * 输入价格（元/千Token）
     */
    private BigDecimal inputPrice;

    /**
     * 输出价格（元/千Token）
     */
    private BigDecimal outputPrice;

    /**
     * 状态：active/inactive/deprecated
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
     * 综合得分（越低越好）
     */
    private BigDecimal score;

    /**
     * 优先级（越大越优先）
     */
    private Integer priority;

    /**
     * 默认超时时间（毫秒）
     */
    private Integer defaultTimeout;

    /**
     * 是否支持深度思考：0=不支持，1=支持
     */
    private Integer supportReasoning;

    /**
     * 能力标签（JSON数组）
     */
    private String capabilities;

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