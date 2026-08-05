-- 2026-08-05 P0-D：LLM Token 成本日志
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
CREATE TABLE IF NOT EXISTS cs_conversation.cs_llm_cost_log
(
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(64),
    model_name    VARCHAR(128)    NOT NULL,
    call_type     VARCHAR(32)     NOT NULL,
    input_tokens  INTEGER         NOT NULL DEFAULT 0,
    output_tokens INTEGER         NOT NULL DEFAULT 0,
    total_tokens  INTEGER         NOT NULL DEFAULT 0,
    latency_ms    INTEGER,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  cs_conversation.cs_llm_cost_log            IS 'P0-D LLM Token 消耗日志，管理台成本报表数据源';
COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.session_id IS '会话 ID，可为 null（意图分类等系统级调用无会话上下文）';
COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.model_name IS '实际调用的模型名称（取自活跃 AiModelConfig.modelName）';
COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.call_type  IS '调用类型：CHAT | INTENT_CLASSIFY';
COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.latency_ms IS '本次调用耗时（毫秒）';

CREATE INDEX IF NOT EXISTS idx_llm_cost_log_created ON cs_conversation.cs_llm_cost_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_cost_log_model   ON cs_conversation.cs_llm_cost_log (model_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_cost_log_session ON cs_conversation.cs_llm_cost_log (session_id);
