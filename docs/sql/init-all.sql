-- ============================================================
-- AI 智能客服系统 — 全量初始化 SQL
-- 版本：v2.0  更新：2026-06-30
--
-- 数据库：
--   ai_customerservice  → cs_auth schema（用户/角色/权限/菜单/部门）
--                       → cs_conversation schema（会话/消息）
--   ai_knowledge        → public schema（知识库/文档/Chunk 向量表）
--
-- 执行方式：
--   psql -U aidev -f init-all.sql
--   docker exec -i <pg_container> psql -U aidev -f /tmp/init-all.sql
--
-- 注意：
--   - 执行前确保 pgvector 已安装（postgres:16 镜像需额外安装）
--   - 所有 CREATE 语句使用 IF NOT EXISTS，可重复执行（幂等）
--   - 测试账号密码统一：Test@123456（BCrypt cost=10）
--   - 生产环境务必修改密码哈希
--
-- ⚠️  本项目不使用 Flyway，所有 schema 变更直接写入本文件。
--     变更流程：1）直连 DB 执行 ALTER/CREATE；2）同步更新本文件。
-- ============================================================

-- ============================================================
-- 第一部分：cs_auth schema（ai_customerservice 库）
-- ============================================================
\c ai_customerservice

CREATE SCHEMA IF NOT EXISTS cs_auth;

CREATE OR REPLACE FUNCTION cs_auth.set_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$ LANGUAGE plpgsql;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS cs_auth.sys_user (
    id                   BIGSERIAL    PRIMARY KEY,
    username             VARCHAR(50)  NOT NULL,
    display_name         VARCHAR(100) NOT NULL,
    email                VARCHAR(200) NOT NULL,
    phone                VARCHAR(20),
    password_hash        VARCHAR(200) NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'active',
    provider             VARCHAR(30)  NOT NULL DEFAULT 'LOCAL',
    login_fail_count     INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    password_changed_at  TIMESTAMP,
    password_history     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    last_login_at        TIMESTAMP,
    last_login_ip        VARCHAR(50),
    dept_id              BIGINT,
    dept_name            VARCHAR(100),
    deleted_at           TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_user_username ON cs_auth.sys_user(username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_user_email    ON cs_auth.sys_user(email)    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_cs_user_status         ON cs_auth.sys_user(status)   WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_cs_user_lastlogin      ON cs_auth.sys_user(last_login_at);
COMMENT ON COLUMN cs_auth.sys_user.password_hash    IS 'BCrypt(cost=10) 密码哈希';
COMMENT ON COLUMN cs_auth.sys_user.password_history IS '最近5次密码哈希，防重用';
COMMENT ON COLUMN cs_auth.sys_user.locked_until     IS '锁定截止时间，NULL=未锁定';
CREATE OR REPLACE TRIGGER trg_cs_user_updated BEFORE UPDATE ON cs_auth.sys_user
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- 2. 角色表
CREATE TABLE IF NOT EXISTS cs_auth.sys_role (
    id          BIGSERIAL    PRIMARY KEY,
    role_key    VARCHAR(50)  NOT NULL,
    role_name   VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_role_key ON cs_auth.sys_role(role_key);
CREATE OR REPLACE TRIGGER trg_cs_role_updated BEFORE UPDATE ON cs_auth.sys_role
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- 3. 用户-角色关联
CREATE TABLE IF NOT EXISTS cs_auth.sys_user_role (
    user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(), granted_by BIGINT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_cs_user_role_role ON cs_auth.sys_user_role(role_id);

-- ::ANCHOR_AUTH_TABLES::

-- 4. 权限定义表
CREATE TABLE IF NOT EXISTS cs_auth.sys_permission (
    id BIGSERIAL PRIMARY KEY, permission_key VARCHAR(100) NOT NULL,
    permission_name VARCHAR(200) NOT NULL, module VARCHAR(50) NOT NULL,
    description TEXT, created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_permission_key ON cs_auth.sys_permission(permission_key);
CREATE INDEX IF NOT EXISTS idx_cs_permission_module    ON cs_auth.sys_permission(module);

-- 5. 角色-权限关联
CREATE TABLE IF NOT EXISTS cs_auth.sys_role_permission (
    role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL, PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX IF NOT EXISTS idx_cs_role_permission_perm ON cs_auth.sys_role_permission(permission_id);

-- 6. 菜单/按钮表
CREATE TABLE IF NOT EXISTS cs_auth.sys_menu (
    id BIGSERIAL PRIMARY KEY, parent_id BIGINT NOT NULL DEFAULT 0,
    menu_type VARCHAR(20) NOT NULL, menu_name VARCHAR(100) NOT NULL,
    menu_key VARCHAR(100) NOT NULL, path VARCHAR(200), component VARCHAR(200),
    icon VARCHAR(100), sort_order INT NOT NULL DEFAULT 0,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE, is_cache BOOLEAN NOT NULL DEFAULT TRUE,
    is_external BOOLEAN NOT NULL DEFAULT FALSE, redirect VARCHAR(200),
    permission_key VARCHAR(100), status VARCHAR(20) NOT NULL DEFAULT 'active',
    remark TEXT, created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_menu_key    ON cs_auth.sys_menu(menu_key);
CREATE INDEX IF NOT EXISTS idx_cs_menu_parent       ON cs_auth.sys_menu(parent_id);
CREATE INDEX IF NOT EXISTS idx_cs_menu_type_status  ON cs_auth.sys_menu(menu_type, status);
COMMENT ON COLUMN cs_auth.sys_menu.menu_type IS 'DIRECTORY=目录, MENU=菜单页面, BUTTON=按钮/接口';
CREATE OR REPLACE TRIGGER trg_cs_menu_updated BEFORE UPDATE ON cs_auth.sys_menu
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- 7. 角色-菜单关联
CREATE TABLE IF NOT EXISTS cs_auth.sys_role_menu (
    role_id BIGINT NOT NULL, menu_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), PRIMARY KEY (role_id, menu_id)
);
CREATE INDEX IF NOT EXISTS idx_cs_role_menu_menu ON cs_auth.sys_role_menu(menu_id);

-- 8. 部门表
CREATE TABLE IF NOT EXISTS cs_auth.sys_dept (
    id BIGSERIAL PRIMARY KEY, parent_id BIGINT NOT NULL DEFAULT 0,
    dept_name VARCHAR(100) NOT NULL, dept_code VARCHAR(50) NOT NULL,
    ancestor_ids TEXT NOT NULL DEFAULT '', sort_order INT NOT NULL DEFAULT 0,
    leader VARCHAR(50), phone VARCHAR(20), email VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'active', deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_dept_code ON cs_auth.sys_dept(dept_code) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_cs_dept_parent     ON cs_auth.sys_dept(parent_id)  WHERE deleted_at IS NULL;
CREATE OR REPLACE TRIGGER trg_cs_dept_updated BEFORE UPDATE ON cs_auth.sys_dept
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- 9. 用户-部门关联
CREATE TABLE IF NOT EXISTS cs_auth.sys_user_dept (
    user_id BIGINT NOT NULL, dept_id BIGINT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, dept_id)
);
CREATE INDEX IF NOT EXISTS idx_cs_user_dept_dept ON cs_auth.sys_user_dept(dept_id);

-- 10. 角色数据权限范围
CREATE TABLE IF NOT EXISTS cs_auth.sys_role_data_scope (
    id BIGSERIAL PRIMARY KEY, role_id BIGINT NOT NULL UNIQUE,
    scope_type VARCHAR(30) NOT NULL DEFAULT 'SELF',
    custom_dept_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(), updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE cs_auth.sys_role_data_scope IS 'ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF';
CREATE OR REPLACE TRIGGER trg_cs_role_scope_updated BEFORE UPDATE ON cs_auth.sys_role_data_scope
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- 11. AI 模型配置表（对话模型 + 向量模型统一管理，model_type 区分类型）
CREATE TABLE IF NOT EXISTS cs_auth.ai_model_config (
    id           BIGSERIAL     PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    provider     VARCHAR(50)   NOT NULL,
    api_protocol VARCHAR(30)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    model_type   VARCHAR(20)   NOT NULL DEFAULT 'CHAT'
                 CHECK (model_type IN ('CHAT','EMBEDDING')),
    remark       VARCHAR(255),
    base_url     VARCHAR(500)  NOT NULL,
    api_key_enc  VARCHAR(1000),
    model_name   VARCHAR(100)  NOT NULL,
    temperature  NUMERIC(4,2)  DEFAULT 0.7,
    max_tokens   INT           DEFAULT 2048,
    timeout_sec  INT           DEFAULT 60,
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    is_enabled   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ai_model_type_default
    ON cs_auth.ai_model_config(model_type, is_default)
    WHERE deleted_at IS NULL AND is_enabled = TRUE;
COMMENT ON COLUMN cs_auth.ai_model_config.model_type   IS 'CHAT=对话大模型, EMBEDDING=向量模型';
COMMENT ON COLUMN cs_auth.ai_model_config.api_key_enc  IS '加密存储，格式：PLAINTEXT:{raw} 或 AES:{base64}';
COMMENT ON COLUMN cs_auth.ai_model_config.is_default   IS '同一 model_type 内唯一默认，setDefault 操作按 type 范围 clear';
CREATE OR REPLACE TRIGGER trg_ai_model_updated BEFORE UPDATE ON cs_auth.ai_model_config
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- ::ANCHOR_CONVERSATION_SCHEMA::

-- ============================================================
-- 第二部分：cs_conversation schema（ai_customerservice 库）
-- ============================================================
CREATE SCHEMA IF NOT EXISTS cs_conversation;

CREATE OR REPLACE FUNCTION cs_conversation.set_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$ LANGUAGE plpgsql;

-- 11. 会话生命周期表
-- agent_id：接入座席 ID，WAITING 时为 NULL，ACTIVE 后填入，转交时更新
CREATE TABLE IF NOT EXISTS cs_conversation.cs_conversation (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(100) NOT NULL,
    visitor_name    VARCHAR(100) NOT NULL DEFAULT '访客',
    transfer_reason TEXT,
    tag             VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    agent_id        VARCHAR(100),
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_conv_session ON cs_conversation.cs_conversation(session_id);
CREATE INDEX IF NOT EXISTS idx_cs_conv_status ON cs_conversation.cs_conversation(status) WHERE status != 'CLOSED';
CREATE INDEX IF NOT EXISTS idx_cs_conv_agent  ON cs_conversation.cs_conversation(agent_id) WHERE agent_id IS NOT NULL;
COMMENT ON TABLE  cs_conversation.cs_conversation IS '客服会话生命周期记录表';
COMMENT ON COLUMN cs_conversation.cs_conversation.status   IS 'WAITING=等待接入, ACTIVE=接待中, CLOSED=已结束';
COMMENT ON COLUMN cs_conversation.cs_conversation.agent_id IS '接入座席ID，WAITING时为NULL，ACTIVE后填入，转交时更新';
CREATE OR REPLACE TRIGGER trg_cs_conv_updated BEFORE UPDATE ON cs_conversation.cs_conversation
    FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_updated_at();

-- 12. 消息明细表（由 RabbitMQ 异步写入）
-- seq: session 内单调递增序号（Redis INCR chat:seq:{sessionId} 生成），
--      支持客户端断线重连时按 sinceSeq 增量拉取，避免每次重连全量历史
CREATE TABLE IF NOT EXISTS cs_conversation.cs_conversation_message (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    content     TEXT         NOT NULL,
    seq         BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_msg_session_time ON cs_conversation.cs_conversation_message(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cs_msg_session_seq  ON cs_conversation.cs_conversation_message(session_id, seq);
COMMENT ON TABLE  cs_conversation.cs_conversation_message IS '对话消息明细表，由 RabbitMQ 异步写入';
COMMENT ON COLUMN cs_conversation.cs_conversation_message.role IS 'user=访客, assistant=AI, agent=人工座席';
COMMENT ON COLUMN cs_conversation.cs_conversation_message.seq  IS 'session 内单调递增序号，支持断线重连后的增量同步';

-- ::ANCHOR_KNOWLEDGE_SCHEMA::

-- ============================================================
-- 第三部分：knowledge schema（ai_knowledge 库）
-- ============================================================
\c ai_knowledge

CREATE EXTENSION IF NOT EXISTS vector;

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$ LANGUAGE plpgsql;

-- 13. 知识库表
CREATE TABLE IF NOT EXISTS knowledge_kb (
    id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL,
    description TEXT, owner_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kb_owner  ON knowledge_kb(owner_id);
CREATE INDEX IF NOT EXISTS idx_kb_status ON knowledge_kb(status);
CREATE OR REPLACE TRIGGER trg_kb_updated BEFORE UPDATE ON knowledge_kb
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 14. 文档表（状态机：DRAFT → REVIEW → PUBLISHED → DEPRECATED / FAILED）
-- storage_path 格式：oss://bucket/docs/{docId}/{filename}（MinIO S3兼容）
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id             VARCHAR(36)  PRIMARY KEY,
    kb_id          VARCHAR(36)  NOT NULL REFERENCES knowledge_kb(id),
    file_name      VARCHAR(255) NOT NULL,
    file_type      VARCHAR(20)  NOT NULL,
    storage_path   VARCHAR(500) NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL DEFAULT 'pending',
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version        VARCHAR(50),
    effective_from DATE,
    expires_at     DATE,
    uploader_id    VARCHAR(36)  NOT NULL,
    reviewer_id    VARCHAR(36),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_doc_kb_status ON knowledge_doc(kb_id, status);
CREATE INDEX IF NOT EXISTS idx_doc_expires   ON knowledge_doc(expires_at) WHERE expires_at IS NOT NULL AND status != 'DEPRECATED';
CREATE INDEX IF NOT EXISTS idx_doc_hash      ON knowledge_doc(content_hash);
COMMENT ON COLUMN knowledge_doc.status       IS 'DRAFT=草稿 / REVIEW=审核中 / PUBLISHED=已发布 / DEPRECATED=已下线 / FAILED=摄取失败';
COMMENT ON COLUMN knowledge_doc.storage_path IS 'MinIO 存储路径，格式：oss://bucket/docs/{docId}/{filename}';
CREATE OR REPLACE TRIGGER trg_doc_updated BEFORE UPDATE ON knowledge_doc
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 15. Chunk 向量表（pgvector 核心，HNSW 索引）
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id                     VARCHAR(36)  PRIMARY KEY,
    doc_id                 VARCHAR(36)  NOT NULL REFERENCES knowledge_doc(id),
    kb_id                  VARCHAR(36)  NOT NULL,
    doc_status             VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    parent_chunk_id        VARCHAR(36),
    breadcrumb             TEXT,
    content                TEXT         NOT NULL,
    content_vector         vector(1024) NOT NULL,
    token_count            INTEGER      NOT NULL,
    retrieval_weight       DECIMAL(3,2) NOT NULL DEFAULT 1.0,
    feedback_downvotes     INTEGER      NOT NULL DEFAULT 0,
    hypothetical_questions JSONB,
    metadata               JSONB,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunk_vector ON knowledge_chunk
    USING hnsw (content_vector vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX IF NOT EXISTS idx_chunk_kb_status ON knowledge_chunk(kb_id, doc_status, retrieval_weight)
    WHERE doc_status = 'PUBLISHED' AND retrieval_weight > 0;
CREATE INDEX IF NOT EXISTS idx_chunk_doc    ON knowledge_chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_parent ON knowledge_chunk(parent_chunk_id) WHERE parent_chunk_id IS NOT NULL;
COMMENT ON COLUMN knowledge_chunk.kb_id          IS '冗余字段，避免检索时 JOIN knowledge_doc';
COMMENT ON COLUMN knowledge_chunk.content_vector IS 'BGE-M3 生成的 1024 维 embedding';
COMMENT ON COLUMN knowledge_chunk.retrieval_weight IS '检索权重 0~1.0，被踩多次时下调至 0 停止检索';

-- ::ANCHOR_SEED_DATA::

-- ============================================================
-- 第四部分：种子数据（切回 ai_customerservice）
-- ============================================================
\c ai_customerservice

-- 菜单树
INSERT INTO cs_auth.sys_menu (id,parent_id,menu_type,menu_name,menu_key,path,component,icon,sort_order,is_visible,is_cache,permission_key) VALUES
(1,   0,   'DIRECTORY','概览',       'Dashboard',               '/dashboard',                 NULL,                              'lucide:layout-dashboard',1, TRUE, FALSE,NULL),
(2,   1,   'MENU',     '分析页',     'DashboardAnalysis',       '/dashboard/analysis',        '_core/about/index',               'lucide:area-chart',      1, TRUE, TRUE, NULL),
(3,   1,   'MENU',     '工作台',     'DashboardWorkspace',      '/dashboard/workspace',       'dashboard/workspace/index',       'carbon:workspace',       2, TRUE, TRUE, NULL),
(100, 0,   'DIRECTORY','智能客服',   'CustomerService',         '/customerservice',           NULL,                              'lucide:bot',             10,TRUE, FALSE,NULL),
(101, 100, 'MENU',     '对话',       'CustomerServiceChat',     '/customerservice/chat',      'customerservice/chat/index',      'lucide:message-circle',  1, TRUE, TRUE, NULL),
(102, 100, 'MENU',     '知识库',     'CustomerServiceKnowledge','/customerservice/knowledge', 'customerservice/knowledge/index', 'lucide:book-open',       2, TRUE, TRUE, NULL),
(103, 100, 'MENU',     '座席工作台', 'CustomerServiceAgent',    '/customerservice/agent',     'customerservice/agent/index',    'lucide:headphones',      3, TRUE, TRUE, NULL),
(110, 102, 'BUTTON','上传文档','knowledge:doc:upload',  NULL,NULL,NULL,1,FALSE,FALSE,'knowledge:doc:upload'),
(111, 102, 'BUTTON','审核文档','knowledge:doc:review',  NULL,NULL,NULL,2,FALSE,FALSE,'knowledge:doc:review'),
(112, 102, 'BUTTON','下线文档','knowledge:doc:offline', NULL,NULL,NULL,3,FALSE,FALSE,'knowledge:doc:offline'),
(113, 102, 'BUTTON','删除文档','knowledge:doc:delete',  NULL,NULL,NULL,4,FALSE,FALSE,'knowledge:doc:delete'),
(120, 103, 'BUTTON','接入会话','agent:session:accept',  NULL,NULL,NULL,1,FALSE,FALSE,'agent:session:accept'),
(121, 103, 'BUTTON','结束会话','agent:session:close',   NULL,NULL,NULL,2,FALSE,FALSE,'agent:session:close'),
(122, 103, 'BUTTON','转交会话','agent:session:transfer',NULL,NULL,NULL,3,FALSE,FALSE,'agent:session:transfer'),
(200, 0,   'DIRECTORY','系统管理','System',     '/system',      NULL,                'lucide:settings',   90,TRUE,FALSE,NULL),
(201, 200, 'MENU',    '用户管理','SystemUser', '/system/user', 'system/user/index', 'lucide:users',       1,TRUE,TRUE, NULL),
(202, 200, 'MENU',    '角色管理','SystemRole', '/system/role', 'system/role/index', 'lucide:shield',      2,TRUE,TRUE, NULL),
(203, 200, 'MENU',    '菜单管理','SystemMenu', '/system/menu', 'system/menu/index', 'lucide:layout-list', 3,TRUE,TRUE, NULL),
(205, 200, 'MENU',   'AI 模型配置','SystemAiModel','/system/ai-model','system/ai-model/index','lucide:cpu',5,TRUE,TRUE,NULL),
-- AI 模型配置 BUTTON（parent=205，230-233 号段）
(230, 205, 'BUTTON','新增配置',   'system:ai-model:create',      NULL,NULL,NULL,1,FALSE,FALSE,'system:ai-model:create'),
(231, 205, 'BUTTON','编辑配置',   'system:ai-model:update',      NULL,NULL,NULL,2,FALSE,FALSE,'system:ai-model:update'),
(232, 205, 'BUTTON','删除配置',   'system:ai-model:delete',      NULL,NULL,NULL,3,FALSE,FALSE,'system:ai-model:delete'),
(233, 205, 'BUTTON','设为默认',   'system:ai-model:set-default', NULL,NULL,NULL,4,FALSE,FALSE,'system:ai-model:set-default'),
(210, 201, 'BUTTON','新增用户','system:user:create',     NULL,NULL,NULL,1,FALSE,FALSE,'system:user:create'),
(211, 201, 'BUTTON','编辑用户','system:user:update',     NULL,NULL,NULL,2,FALSE,FALSE,'system:user:update'),
(212, 201, 'BUTTON','删除用户','system:user:delete',     NULL,NULL,NULL,3,FALSE,FALSE,'system:user:delete'),
(213, 201, 'BUTTON','重置密码','system:user:reset-pwd',  NULL,NULL,NULL,4,FALSE,FALSE,'system:user:reset-pwd'),
(220, 202, 'BUTTON','新增角色','system:role:create',     NULL,NULL,NULL,1,FALSE,FALSE,'system:role:create'),
(221, 202, 'BUTTON','编辑角色','system:role:update',     NULL,NULL,NULL,2,FALSE,FALSE,'system:role:update'),
(222, 202, 'BUTTON','删除角色','system:role:delete',     NULL,NULL,NULL,3,FALSE,FALSE,'system:role:delete'),
(900, 0,   'MENU',  '关于','About','/about','_core/about/index','lucide:copyright',99,TRUE,FALSE,NULL)
ON CONFLICT (menu_key) DO NOTHING;
SELECT setval('cs_auth.sys_menu_id_seq', (SELECT MAX(id) FROM cs_auth.sys_menu));

-- 权限定义
INSERT INTO cs_auth.sys_permission (permission_key, permission_name, module) VALUES
('knowledge:doc:upload','上传文档','knowledge'),('knowledge:doc:review','审核文档','knowledge'),
('knowledge:doc:offline','下线文档','knowledge'),('knowledge:doc:delete','删除文档','knowledge'),
('agent:session:accept','接入会话','agent'),('agent:session:close','结束会话','agent'),
('agent:session:transfer','转交会话','agent'),
('system:user:create','新增用户','system'),('system:user:update','编辑用户','system'),
('system:user:delete','删除用户','system'),('system:user:reset-pwd','重置密码','system'),
('system:role:create','新增角色','system'),('system:role:update','编辑角色','system'),
('system:role:delete','删除角色','system'),
-- AI 模型配置按钮权限
('system:ai-model:create',      '新增AI模型配置', 'system'),
('system:ai-model:update',      '编辑AI模型配置', 'system'),
('system:ai-model:delete',      '删除AI模型配置', 'system'),
('system:ai-model:set-default', '设为默认AI模型', 'system')
ON CONFLICT (permission_key) DO NOTHING;

-- AI 模型配置种子数据（两条默认配置：对话模型 + 向量模型）
INSERT INTO cs_auth.ai_model_config
    (name, provider, api_protocol, model_type, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_at, updated_at) VALUES
(
    '默认对话模型', 'Custom', 'OPENAI_COMPATIBLE', 'CHAT',
    'http://localhost:11434/v1', 'PLAINTEXT:', 'qwen2.5:7b',
    0.7, 2048, 60, TRUE, TRUE, NOW(), NOW()
),
(
    '默认向量模型', 'Custom', 'OPENAI_COMPATIBLE', 'EMBEDDING',
    'http://localhost:8000', 'PLAINTEXT:', 'bge-m3',
    0.0, 0, 30, TRUE, TRUE, NOW(), NOW()
)
ON CONFLICT DO NOTHING;

-- 角色
INSERT INTO cs_auth.sys_role (id,role_key,role_name,is_system,status) VALUES
(10,'super_admin','超级管理员',TRUE,'active'),
(11,'kf_manager','客服管理员',FALSE,'active'),
(12,'kf_staff','普通客服',FALSE,'active')
ON CONFLICT (role_key) DO NOTHING;
SELECT setval('cs_auth.sys_role_id_seq', (SELECT MAX(id) FROM cs_auth.sys_role));

-- 角色数据权限
INSERT INTO cs_auth.sys_role_data_scope (role_id,scope_type) VALUES
(10,'ALL'),(11,'DEPT_TREE'),(12,'SELF')
ON CONFLICT (role_id) DO UPDATE SET scope_type = EXCLUDED.scope_type;

-- 角色菜单（超管全部，客服管理员智能客服+按钮，普通客服仅对话）
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) SELECT 10, id FROM cs_auth.sys_menu ON CONFLICT DO NOTHING;
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(11,100),(11,101),(11,102),(11,103),(11,110),(11,111),(11,112),(11,113),(11,120),(11,121),(11,122),
(12,100),(12,101)
ON CONFLICT DO NOTHING;

-- 测试用户（密码：Test@123456）
-- BCrypt hash: $2b$10$IbF1B4L.Tog2vsx7coxiTehgwstvHi96dSx.7GIgqTnD0yyGcPcO2
INSERT INTO cs_auth.sys_user (id,username,display_name,email,password_hash,status,provider,must_change_password) VALUES
(1001,'superadmin','超级管理员','superadmin@example.com','$2b$10$IbF1B4L.Tog2vsx7coxiTehgwstvHi96dSx.7GIgqTnD0yyGcPcO2','active','LOCAL',FALSE),
(1002,'kfmanager','客服管理员','kfmanager@example.com', '$2b$10$IbF1B4L.Tog2vsx7coxiTehgwstvHi96dSx.7GIgqTnD0yyGcPcO2','active','LOCAL',FALSE),
(1003,'kfstaff','普通客服',   'kfstaff@example.com',   '$2b$10$IbF1B4L.Tog2vsx7coxiTehgwstvHi96dSx.7GIgqTnD0yyGcPcO2','active','LOCAL',FALSE)
ON CONFLICT (id) DO NOTHING;
SELECT setval('cs_auth.sys_user_id_seq', (SELECT MAX(id) FROM cs_auth.sys_user));

-- 用户-角色关联
INSERT INTO cs_auth.sys_user_role (user_id,role_id) VALUES (1001,10),(1002,11),(1003,12) ON CONFLICT DO NOTHING;

-- ============================================================
-- 知识库默认数据（ai_knowledge 库）
-- ============================================================
\c ai_knowledge

INSERT INTO knowledge_kb (id,name,description,owner_id,status) VALUES
('default','默认知识库','通用产品知识库，包含 FAQ、产品手册、政策文档','system','active'),
('faq','FAQ 知识库','常见问题解答专用知识库','system','active'),
('ticket','历史工单库','历史客服工单数据，用于提升召回率','system','active')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
\c postgres
\echo '=========================================='
\echo '✅ AI 智能客服系统数据库初始化完成'
\echo '数据库：ai_customerservice / ai_knowledge'
\echo '测试账号：superadmin / kfmanager / kfstaff'
\echo '统一密码：Test@123456'
\echo '=========================================='
