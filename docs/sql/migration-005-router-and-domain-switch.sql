-- migration-005: 新增 ROUTER 模型类型 + 会话域切换历史表 + __system__ 域意图数据

-- ① 扩展 ai_model_config CHECK 约束，支持 ROUTER 类型
ALTER TABLE cs_auth.ai_model_config
    DROP CONSTRAINT IF EXISTS ai_model_config_model_type_check;
ALTER TABLE cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_model_type_check
    CHECK (model_type IN ('CHAT', 'EMBEDDING', 'ROUTER'));

-- ② 插入默认 ROUTER 模型（本地 Ollama，可在后台修改）
INSERT INTO cs_auth.ai_model_config
    (name, provider, api_protocol, model_type, base_url, api_key_enc,
     model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled)
VALUES ('Qwen2.5-0.5B (域路由)', 'Ollama', 'OPENAI_COMPATIBLE', 'ROUTER',
        'http://localhost:11434/v1', 'PLAINTEXT:none',
        'qwen2.5:0.5b', 0.0, 32, 5, true, true);

-- ③ 会话域切换历史表
CREATE TABLE cs_conversation.cs_session_domain_switch (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(100) NOT NULL,
    from_domain     VARCHAR(64),
    to_domain       VARCHAR(64)  NOT NULL,
    switch_type     VARCHAR(32)  NOT NULL,
    trigger_message TEXT,
    reason          TEXT,
    msg_seq         BIGINT,
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);
CREATE INDEX idx_cs_session_domain_switch_session_id ON cs_conversation.cs_session_domain_switch(session_id);
CREATE INDEX idx_cs_session_domain_switch_created_at ON cs_conversation.cs_session_domain_switch(created_at);
COMMENT ON TABLE cs_conversation.cs_session_domain_switch
    IS '会话领域切换历史表，记录每次 session 跨域切换事件，供运营分析';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.switch_type
    IS 'INITIAL=初始进入, ROUTER_MODEL=小模型检测, LLM_TOOL=大模型工具触发, USER_SELECTED=用户手动选择';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.trigger_message
    IS '触发切换的用户消息，用于分析跨域切换原因';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.msg_seq
    IS '关联 cs_conversation_message.seq，定位切换发生在哪条消息';

-- ④ __system__ 域：FAQ 路径通用意图（由运营维护，不再硬编码）
INSERT INTO cs_conversation.cs_domain (code, name, description, enabled)
VALUES ('__system__', '系统通用', 'FAQ 路径路由意图，系统保留域，勿删', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO cs_conversation.cs_intent (domain_id, code, name, description, enabled)
SELECT d.id, v.code, v.name, v.description, true
FROM cs_conversation.cs_domain d,
     (VALUES
        ('FAQ_QUERY',        'FAQ 问答',   '咨询产品、服务、政策等业务问题'),
        ('TRANSFER_REQUEST', '转人工',     '要求转人工客服，如"我要真人"、"转客服"'),
        ('COMPLAINT',        '投诉',       '投诉、强烈不满，如"投诉"、"要求赔偿"'),
        ('CHITCHAT',         '闲聊',       '闲聊、问候，与业务无关'),
        ('OUT_OF_SCOPE',     '超出范围',   '与本业务完全无关的话题'),
        ('UNKNOWN',          '未知',       '无法判断意图')
     ) AS v(code, name, description)
WHERE d.code = '__system__'
ON CONFLICT (domain_id, code) DO NOTHING;
