package com.lilac.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型 VO
 */
@Data
public class ModelVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    private Long id;

    /**
     * 提供者id
     */
    private Long providerId;

    /**
     * 提供者显示名称
     */
    private String providerDisplayName;

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
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
