package com.lilac.domain.dto.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 更新模型请求
 */
@Data
public class ModelUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    private Long id;

    /**
     * 模型显示名称
     */
    private String modelName;

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
}
