-- ============================================================
-- DIT 框架表结构迁移
-- migration-002-dit-tables.sql
-- 执行前提：cs_conversation schema 已存在
-- ============================================================

-- 1. 领域/场景表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_domain (
    id                  BIGSERIAL    PRIMARY KEY,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    system_prompt_addon TEXT,
    knowledge_base_id   BIGINT,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_domain_code UNIQUE (code)
);
COMMENT ON TABLE  cs_conversation.cs_domain IS '领域/场景配置表';
COMMENT ON COLUMN cs_conversation.cs_domain.code IS '前端传入的领域标识，如 ecommerce';
COMMENT ON COLUMN cs_conversation.cs_domain.system_prompt_addon IS '追加到 system prompt 的领域专属说明';

-- 2. 意图表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent (
    id              BIGSERIAL    PRIMARY KEY,
    domain_id       BIGINT       NOT NULL REFERENCES cs_conversation.cs_domain(id),
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT         NOT NULL,
    example_queries JSONB        NOT NULL DEFAULT '[]',
    auto_transfer   BOOLEAN      NOT NULL DEFAULT FALSE,
    skip_rag        BOOLEAN      NOT NULL DEFAULT FALSE,
    fallback_reply  TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_intent_domain_code UNIQUE (domain_id, code)
);
COMMENT ON TABLE  cs_conversation.cs_intent IS '意图定义表';
COMMENT ON COLUMN cs_conversation.cs_intent.example_queries IS '少样本示例，JSON 数组，如 ["查订单","我的包裹到哪了"]';
COMMENT ON COLUMN cs_conversation.cs_intent.auto_transfer   IS 'true=命中后自动转人工（投诉/敏感操作）';
COMMENT ON COLUMN cs_conversation.cs_intent.skip_rag        IS 'true=跳过 RAG 检索';

-- 3. 槽位定义表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_slot (
    id                    BIGSERIAL    PRIMARY KEY,
    intent_id             BIGINT       NOT NULL REFERENCES cs_conversation.cs_intent(id),
    slot_name             VARCHAR(64)  NOT NULL,
    slot_type             VARCHAR(32)  NOT NULL DEFAULT 'string',
    description           VARCHAR(256) NOT NULL,
    required              BOOLEAN      NOT NULL DEFAULT FALSE,
    resolve_strategy      JSONB        NOT NULL DEFAULT '["EXTRACT","SESSION","DISCOVER","ASK_USER"]',
    session_key           VARCHAR(64),
    discover_tool_code    VARCHAR(64),
    discover_fixed_params JSONB        DEFAULT '{}',
    ask_user_prompt       VARCHAR(256),
    enum_values           JSONB,
    sort_order            INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_slot_intent_name UNIQUE (intent_id, slot_name)
);
COMMENT ON COLUMN cs_conversation.cs_intent_slot.resolve_strategy   IS '解析策略优先级，JSON 数组，按顺序尝试';
COMMENT ON COLUMN cs_conversation.cs_intent_slot.discover_tool_code IS 'DISCOVER 级使用的发现工具 code';

-- 4. 工具注册表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_tool (
    id                BIGSERIAL    PRIMARY KEY,
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       TEXT         NOT NULL,
    tool_type         VARCHAR(32)  NOT NULL DEFAULT 'HTTP',
    http_method       VARCHAR(16),
    url_template      VARCHAR(512),
    headers_template  JSONB        DEFAULT '{}',
    body_template     JSONB,
    param_schema      JSONB        NOT NULL DEFAULT '{}',
    response_jsonpath VARCHAR(256),
    auth_type         VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    auth_config       JSONB        DEFAULT '{}',
    timeout_ms        INT          NOT NULL DEFAULT 5000,
    is_discover_tool  BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tool_code UNIQUE (code)
);
COMMENT ON TABLE  cs_conversation.cs_tool IS '工具注册表（HTTP 调用或内置 Java 实现）';
COMMENT ON COLUMN cs_conversation.cs_tool.tool_type        IS 'HTTP=通用 HTTP 调用, BUILTIN=Java 内置实现';
COMMENT ON COLUMN cs_conversation.cs_tool.url_template     IS 'URL 模板，支持 {slot_name} 路径参数';
COMMENT ON COLUMN cs_conversation.cs_tool.param_schema     IS '参数 JSON Schema，供 LLM Function Calling 使用';
COMMENT ON COLUMN cs_conversation.cs_tool.is_discover_tool IS 'true=可作为槽位 DISCOVER 级发现工具';

-- 5. 意图-工具绑定表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_tool (
    id               BIGSERIAL   PRIMARY KEY,
    intent_id        BIGINT      NOT NULL REFERENCES cs_conversation.cs_intent(id),
    tool_id          BIGINT      NOT NULL REFERENCES cs_conversation.cs_tool(id),
    execution_mode   VARCHAR(16) NOT NULL DEFAULT 'OPTIONAL',
    execution_order  INT         NOT NULL DEFAULT 0,
    param_mappings   JSONB       NOT NULL DEFAULT '{}',
    CONSTRAINT uq_intent_tool UNIQUE (intent_id, tool_id)
);
COMMENT ON COLUMN cs_conversation.cs_intent_tool.execution_mode  IS 'REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策';
COMMENT ON COLUMN cs_conversation.cs_intent_tool.param_mappings  IS '参数来源映射，JSON，key=工具参数名，value={source,key}';

-- 6. 工具调用日志
CREATE TABLE IF NOT EXISTS cs_conversation.cs_tool_call_log (
    id           BIGSERIAL   PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL,
    tool_code    VARCHAR(64) NOT NULL,
    intent_code  VARCHAR(64),
    domain_code  VARCHAR(64),
    params       JSONB,
    response     TEXT,
    status       VARCHAR(16) NOT NULL,
    http_status  INT,
    duration_ms  INT,
    error_msg    TEXT,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_session ON cs_conversation.cs_tool_call_log(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_created ON cs_conversation.cs_tool_call_log(created_at);
COMMENT ON TABLE  cs_conversation.cs_tool_call_log IS '工具调用日志（调试+监控）';
COMMENT ON COLUMN cs_conversation.cs_tool_call_log.status IS 'SUCCESS/ERROR/TIMEOUT/SKIPPED';

-- 7. 对话 pipeline 挂起状态表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_pending_slot (
    session_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    domain_code    VARCHAR(64) NOT NULL,
    intent_code    VARCHAR(64) NOT NULL,
    pending_slot   VARCHAR(64) NOT NULL,
    pending_type   VARCHAR(16) NOT NULL,
    candidates     JSONB,
    resolved_slots JSONB       NOT NULL DEFAULT '{}',
    retry_count    INT         NOT NULL DEFAULT 0,
    expires_at     TIMESTAMP   NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  cs_conversation.cs_pending_slot IS '槽位解析挂起状态，用于多轮对话中间状态恢复';
COMMENT ON COLUMN cs_conversation.cs_pending_slot.pending_type IS 'DISCOVERED=候选项待选, MISSING=等待用户输入';
