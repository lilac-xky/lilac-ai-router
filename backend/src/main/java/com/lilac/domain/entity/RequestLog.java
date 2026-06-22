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

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 请求日志
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("request_log")
public class RequestLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 用户id
     */
    @Column(value = "userId")
    private Long userId;

    /**
     * API Key id
     */
    @Column(value = "apiKeyId")
    private Long apiKeyId;

    /**
     * 使用的模型名称
     */
    @Column(value = "modelName")
    private String modelName;

    /**
     * 输入Token数
     */
    @Column(value = "promptTokens")
    private Integer promptTokens;

    /**
     * 输出Token数
     */
    @Column(value = "completionTokens")
    private Integer completionTokens;

    /**
     * 总Token数
     */
    @Column(value = "totalTokens")
    private Integer totalTokens;

    /**
     * 请求耗时（毫秒）
     */
    @Column(value = "duration")
    private Integer duration;

    /**
     * 状态：success/failed
     */
    @Column(value = "status")
    private String status;

    /**
     * 错误信息
     */
    @Column(value = "errorMessage")
    private String errorMessage;

    /**
     * 本次请求费用（元）
     */
    @Column(value = "cost")
    private BigDecimal cost;

    /**
     * 创建时间
     */
    @Column(value = "createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column(value = "updateTime")
    private LocalDateTime updateTime;
}