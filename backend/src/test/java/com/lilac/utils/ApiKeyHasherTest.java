package com.lilac.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哈希工具的正确性。
 *
 * <p>这里的期望值是**用 MySQL 的 {@code SHA2(x, 256)} 算出来的**，不是从本实现抄的 ——
 * 因为生产上存量 Key 是由 {@code V1002} 迁移用 SQL 回填哈希的，
 * 两边的算法必须逐字一致，否则迁移完存量 Key 全部失效。
 * 若哪天有人把实现换成 BCrypt 或改变 hex 大小写，本用例会红。
 */
class ApiKeyHasherTest {

    @Test
    @DisplayName("与 MySQL SHA2(x,256) 一致 · 已知向量（这些期望值取自 MySQL，不是本实现）")
    void sha256Hex_matchesMySqlSha2() {
        // 以下三行的期望值均由 SELECT SHA2('<输入>',256) 得到
        assertThat(ApiKeyHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeyHasher.sha256Hex(""))
                .as("空串也要能算（MySQL 同样返回 e3b0c4…，不是 NULL）")
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ApiKeyHasher.sha256Hex("sk-00000000000000000000000000000000"))
                .isEqualTo("8acd31c3c56e13199b66b48bff98d6b6070c1478f7955fad80b1ff084799dc77");
    }

    @Test
    @DisplayName("输出形态 · 64 位小写 hex（不是 Base64、不是大写、无前缀）")
    void sha256Hex_shape() {
        String hash = ApiKeyHasher.sha256Hex("sk-f74495471d0e4b8f9a1c2d3e4f5a6b7c");

        assertThat(hash)
                .as("长度必须 64，否则 CHAR(64) 列会截断/报错")
                .hasSize(64);
        assertThat(hash)
                .as("必须全小写 —— MySQL SHA2 返回小写，大小写不一致会导致查不到存量行")
                .isEqualTo(hash.toLowerCase())
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("中文与特殊字符 · 按 UTF-8 编码，与 MySQL 的 utf8mb4 一致")
    void sha256Hex_handlesUtf8() {
        // 期望值取自 MySQL（连接字符集 utf8mb4）：SELECT SHA2('sk-中文测试',256)
        assertThat(ApiKeyHasher.sha256Hex("sk-\u4e2d\u6587\u6d4b\u8bd5"))
                .as("中文必须按 UTF-8 字节算；用平台默认编码会得到不同结果")
                .isEqualTo("c1a47d0426bf5b7c25b20fa304889c378ea736aae0824b5b38f665d29a9a8780");
    }

    @Test
    @DisplayName("⚠️ 连接字符集必须是 utf8mb4 · 否则同一个中文串会算出两种哈希（踩过的坑）")
    void sha256Hex_charsetMatters() {
        // 这个用例把上面那个坑固定下来：同一个中文字面量，经不同字符集的连接算出来是不一样的。
        // 实测：mysql 客户端默认字符集下 SHA2('sk-中文测试') = 79368d78…，
        // 加 --default-character-set=utf8mb4 后 = c1a47d04…。
        // 连 Java 侧与 utf8mb4 连接的 MySQL 一致（本类其它用例用的就是 c1a47d04…）。
        String viaJavaUtf8 = ApiKeyHasher.sha256Hex("sk-\u4e2d\u6587\u6d4b\u8bd5");
        String viaWrongCharset = "79368d7818ba644657f53a72af6aea912a03edfb7a4e61be1b283b77735942d4";

        assertThat(viaJavaUtf8)
                .as("两者必须不同 —— 若哪天相同，说明这个坑的记录已失效，请复核")
                .isNotEqualTo(viaWrongCharset);
        // 关键结论：ApiKeyHasher 用 UTF-8，与 MySQL 在 utf8mb4 连接下的结果一致。
        // 所以 V1002 迁移与运行期鉴权只要都用 utf8mb4 就是安全的（本项目 DDL 全是 utf8mb4）。
        assertThat(ApiKeyHasher.sha256Hex("sk-\u4e2d\u6587\u6d4b\u8bd5"))
                .isEqualTo("c1a47d0426bf5b7c25b20fa304889c378ea736aae0824b5b38f665d29a9a8780");
    }

    @Test
    @DisplayName("前缀 · 取前 8 位；明文短于 8 位时不抛异常")
    void prefix_length() {
        assertThat(ApiKeyHasher.prefix("sk-f74495471d0e4b8f")).isEqualTo("sk-f7449");
        assertThat(ApiKeyHasher.prefix("sk-abc")).as("短于 8 位时退化为整个明文，不能 substring 越界").isEqualTo("sk-abc");
        assertThat(ApiKeyHasher.prefix(null)).isNull();
    }

    @Test
    @DisplayName("不可逆 · 哈希不包含明文片段的直接可读形式（前缀是唯一留存的可读信息）")
    void hash_doesNotContainPlaintext() {
        String plain = "sk-f74495471d0e4b8f9a1c2d3e4f5a6b7c";
        String hash = ApiKeyHasher.sha256Hex(plain);

        assertThat(hash).as("哈希里不应出现明文的后 27 位").doesNotContain(plain.substring(8));
        assertThat(hash).as("哈希与明文长度无关，恒为 64").hasSize(64);
    }
}
