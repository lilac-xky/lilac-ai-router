-- V1003: 补 keySuffix（明文后 4 位），恢复列表展示为「前 8 + **** + 后 4」
--
-- 背景：V1002 把明文置空后，列表只能显示 8 位前缀，比改动前少了后 4 位。
-- 后 4 位是各家平台展示 Key 的惯例（GitHub ghp_…XXXX、Stripe sk_live_…XXXX），
-- 对「这个 Key 是不是我正在用的那个」的辨识有用。
--
-- 为什么不改 V1002：它已被应用，改它会让校验和不匹配、应用启动直接 validate failed。
-- 存量行由 V1002 之后的数据决定 —— keyHash 无法反推后缀，所以存量行 keySuffix 只能留 NULL，
-- 前端按「后缀为空就只显示前缀」降级展示。存量 Key 本就要重新生成（见 W8 文档），
-- 重新生成时才会带上后缀。
ALTER TABLE api_key
    ADD COLUMN keySuffix VARCHAR(8) NULL COMMENT '明文后 4 位，与 keyPrefix 一起用于列表辨识' AFTER keyPrefix;
