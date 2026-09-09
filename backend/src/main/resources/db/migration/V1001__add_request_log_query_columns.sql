-- V1001: 补齐 request_log 缺失的调用历史查询列
ALTER TABLE request_log
    ADD COLUMN requestModel VARCHAR(128) NULL COMMENT '请求模型标识' AFTER modelName,
    ADD COLUMN requestType  VARCHAR(32)  NULL COMMENT '请求类型' AFTER requestModel,
    ADD COLUMN source       VARCHAR(32)  NULL COMMENT '来源：api/web' AFTER requestType;

-- 回填历史数据：新列默认为 NULL，而 LIKE 查询匹配不到 NULL，
-- 故用已有的 modelName 补齐，保证加列后历史记录立刻可被检索
UPDATE request_log SET requestModel = modelName, requestType = 'chat' WHERE requestModel IS NULL;

CREATE INDEX idx_request_createTime_model ON request_log(createTime, requestModel);