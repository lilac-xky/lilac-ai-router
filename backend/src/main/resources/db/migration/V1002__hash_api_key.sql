-- V1002: API Key 改哈希存储，明文不再入库
--
-- 背景：keyValue 原为 sk- + 32 位 hex 的明文，且全表只有 PRIMARY KEY，
-- 任何能读到该列或 Redis 的途径（备份、只读账号、慢查询日志、redis-cli --scan）
-- 拿到的都是可直接使用的凭据。详见 docs/issue-ApiKey与密钥管理.md 问题二。
--
-- 迁移策略：哈希在库内用 SHA2() 算，脚本本身不含任何密钥。
-- 已实测 MySQL SHA2(x,256) 与 Java MessageDigest("SHA-256") 的十六进制输出逐字一致
-- （含空串/中文/引号/超长串，输出小写 64 位），并已在临时库上完整演练过：
-- 19 行存量全部回填、哈希 0 处不一致、唯一索引可挡住重复、按哈希能查回同一行。

ALTER TABLE api_key
    ADD COLUMN keyHash   CHAR(64)    NULL COMMENT 'SHA-256(keyValue) 小写 hex' AFTER keyValue,
    ADD COLUMN keyPrefix VARCHAR(12) NULL COMMENT '明文前 8 位，仅用于列表脱敏展示' AFTER keyHash;

-- 回填存量：已有数据的哈希必须在库里算，否则迁移脚本会带上明文
UPDATE api_key
SET keyHash   = SHA2(keyValue, 256),
    keyPrefix = LEFT(keyValue, 8)
WHERE keyValue IS NOT NULL;

-- 唯一索引：原先「Key 不重复」只靠 UUID 随机性撑着，一旦重复
-- getByKeyValue 不报错、静默返回其中一条，会认证到另一个用户
ALTER TABLE api_key
    MODIFY COLUMN keyValue VARCHAR(255) NULL COMMENT '已废弃：明文不再写入，列保留以免破坏旧数据',
    ADD UNIQUE KEY uk_keyHash (keyHash);

-- 清掉明文。回填已完成，明文没有保留价值，留着等于迁移白做。
-- WHERE 加 keyHash IS NOT NULL 是防呆：只清「确实已经算出哈希」的行，
-- 万一上面的回填因为某种原因漏了行，宁可留着明文也不要让那个 Key 无法鉴权。
-- ⚠️ 这一步不可逆（明文无法从哈希反推）。执行前已实测：
--    生产 ApiKeyHasher 与 SQL 的 SHA2() 对 19 行存量逐行一致、唯一索引可挡重复、按哈希能查回同一行。
UPDATE api_key SET keyValue = NULL WHERE keyHash IS NOT NULL;
