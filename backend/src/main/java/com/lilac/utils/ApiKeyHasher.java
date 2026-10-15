package com.lilac.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * API Key 哈希：SHA-256 小写 hex。
 *
 * <p>用 SHA-256 而不是 BCrypt/Argon2 是因为 Key 是 32 位随机 hex（128 bit 熵），
 * 不是人选的口令，不存在字典攻击面，慢哈希只会给每次鉴权加无谓的 CPU 开销。
 *
 * <p>与 {@code V1002} 迁移里的 {@code SHA2(keyValue, 256)} 必须逐字一致 ——
 * 否则存量 Key 迁移后全部失效。已在临时库上逐行比对过 19 行存量。
 */
public final class ApiKeyHasher {

    /** 明文前缀长度：足够区分列表里的 Key，又不至于泄露太多 */
    public static final int PREFIX_LENGTH = 8;

    /** 明文后缀长度：与主流平台展示惯例一致（GitHub / Stripe 都是后 4 位） */
    public static final int SUFFIX_LENGTH = 4;

    private ApiKeyHasher() {
    }

    public static String sha256Hex(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plainText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走不到这里
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 明文前缀，越界时退化为整个明文（短 Key 不至于抛异常）
     */
    public static String prefix(String plainText) {
        if (plainText == null) {
            return null;
        }
        return plainText.length() <= PREFIX_LENGTH ? plainText : plainText.substring(0, PREFIX_LENGTH);
    }

    /**
     * 明文后 4 位。哈希不可逆，展示用的后缀只能单独存一列
     */
    public static String suffix(String plainText) {
        if (plainText == null) {
            return null;
        }
        return plainText.length() <= SUFFIX_LENGTH
                ? plainText
                : plainText.substring(plainText.length() - SUFFIX_LENGTH);
    }

    /**
     * 列表展示形态：{@code sk-f7449****XXXX}。
     * 后缀缺失（V1002 迁移的存量行）时降级为只显示前缀，不留一串问号
     */
    public static String masked(String keyPrefix, String keySuffix) {
        if (keyPrefix == null) {
            return null;
        }
        return keySuffix == null || keySuffix.isEmpty()
                ? keyPrefix + "****"
                : keyPrefix + "****" + keySuffix;
    }
}
