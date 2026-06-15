package com.lilac.domain.dto.provider;

import com.lilac.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型提供者查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProviderQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 显示名称（模糊查询）
     */
    private String displayName;

    /**
     * 健康状态：healthy/unhealthy/degraded/unknown
     */
    private String healthStatus;

    /**
     * 状态：active/inactive/maintenance
     */
    private String status;
}
