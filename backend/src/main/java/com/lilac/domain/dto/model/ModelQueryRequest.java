package com.lilac.domain.dto.model;

import com.lilac.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型名称（模糊查询）
     */
    private String modelName;

    /**
     * 模型类型：chat/embedding/image/audio
     */
    private String modelType;

    /**
     * 状态：active/inactive/deprecated
     */
    private String status;

    /**
     * 提供者id
     */
    private Long providerId;
}
