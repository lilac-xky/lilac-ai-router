package com.lilac.domain.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户配额更新请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * Token配额（-1表示无限制）
     */
    private Long tokenQuota;
}
