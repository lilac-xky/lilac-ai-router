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
@Table("api_key")
public class ApiKey implements Serializable {

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
     * API Key值（sk-xxx格式）
     */
    @Column(value = "keyValue")
    private String keyValue;

    /**
     * Key名称/备注
     */
    @Column(value = "keyName")
    private String keyName;

    /**
     * 状态：active/inactive/revoked
     */
    @Column(value = "status")
    private String status;

    /**
     * 已使用Token总数
     */
    @Column(value = "totalTokens")
    private Long totalTokens;

    /**
     * 最后使用时间
     */
    @Column(value = "lastUsedTime")
    private LocalDateTime lastUsedTime;

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

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}