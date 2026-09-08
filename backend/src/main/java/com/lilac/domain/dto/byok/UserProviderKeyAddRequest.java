package com.lilac.domain.dto.byok;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户提供者密钥添加请求DTO。
 */
@Data
public class UserProviderKeyAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 提供者 ID
     */
    private Long providerId;

    /**
     * API Key
     */
    private String apiKey;
}