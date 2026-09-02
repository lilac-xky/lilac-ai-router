package com.lilac.domain.entity;

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
 * 图片生成记录 实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "image_generation_record", camelToUnderline = false)
public class ImageGenerationRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * API Key id
     */
    private Long apiKeyId;

    /**
     * 使用的模型id
     */
    private Long modelId;

    /**
     * 模型标识
     */
    private String modelKey;

    /**
     * 生成提示词
     */
    private String prompt;

    /**
     * 修订后的提示词
     */
    private String revisedPrompt;

    /**
     * 图片URL
     */
    private String imageUrl;

    /**
     * Base64图片数据
     */
    private String imageData;

    /**
     * 图片尺寸
     */
    private String size;

    /**
     * 图片质量
     */
    private String quality;

    /**
     * 状态：success/failed
     */
    private String status;

    /**
     * 生成费用（元）
     */
    private BigDecimal cost;

    /**
     * 耗时（毫秒）
     */
    private Integer duration;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 客户端IP
     */
    private String clientIp;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}