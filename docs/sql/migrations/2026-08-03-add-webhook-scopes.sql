-- 2026-08-03 通用 Webhook 配置：新增事件范围（scope）订阅
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
ALTER TABLE cs_conversation.cs_webhook_config
    ADD COLUMN scopes JSONB NOT NULL DEFAULT '["SLA_BREACH"]';

COMMENT ON COLUMN cs_conversation.cs_webhook_config.scopes
    IS '订阅的事件范围数组（WebhookScope 枚举名），空数组=不订阅任何事件；默认 ["SLA_BREACH"]';
