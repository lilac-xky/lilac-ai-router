package com.lilac.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型提供者 VO
 */
@Data
public class ProviderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
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
     * API密钥（脱敏显示）
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
