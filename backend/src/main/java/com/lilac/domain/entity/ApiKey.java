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
import java.time.LocalDateTime;

/**
 * API Key
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "api_key", camelToUnderline = false)
public class ApiKey implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * API Key 明文。
     */
    @Column(ignore = true)
    private String keyValue;

    /**
     * SHA-256(明文) 的小写 hex，鉴权时按它查
     */
    private String keyHash;

    /**
     * 明文前 8 位，仅用于列表脱敏展示（哈希不可逆，不能拿它反推展示前缀）
     */
    private String keyPrefix;

    /**
     * 明文后 4 位。V1002 迁移的存量行为 NULL（哈希反推不出），展示时降级为只有前缀
     */
    private String keySuffix;

    /**
     * Key名称/备注
     */
    private String keyName;

    /**
     * 状态：active/inactive/revoked
     */
    private String status;

    /**
     * 已使用Token总数
     */
    private Long totalTokens;

    /**
     * 最后使用时间
     */
    private LocalDateTime lastUsedTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(isLogicDelete = true)
    private Integer isDelete;
}