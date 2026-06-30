-- ============================================================
-- V1: 对话服务持久化 Schema（cs_conversation）
-- 服务：conversation-service（端口 8082）
-- 架构：Redis Stream 异步消费 → PostgreSQL 持久化
--
-- 包含：
--   1. cs_conversation       会话生命周期记录
--   2. cs_conversation_message 消息明细记录
-- ============================================================

-- --------------------------------------------------------
-- 1. 会话表（cs_conversation）
--    记录每次人机对话的生命周期信息。
--    由 SessionQueueService.enqueue() 触发创建，
--    由 SessionQueueService.close()   触发关闭。
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS cs_conversation.cs_conversation
(
    id BIGSERIAL PRIMARY KEY,
    -- 前端生成的唯一会话标识（如 guest-xxx 或登录用户 sessionId）
    session_id      VARCHAR(100) NOT NULL,
    -- 访客名称（匿名访客默认为 "访客"）
    visitor_name    VARCHAR(100) NOT NULL DEFAULT '访客',
    -- 转接原因（AI 置信度低 / 用户主动请求等）
    transfer_reason TEXT,
    -- 问题分类标签（投诉 / 退款 / 咨询等）
    tag             VARCHAR(50),
    -- 会话状态：WAITING / ACTIVE / CLOSED（与 SessionStatus 枚举对应）
    status          VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    -- 会话开始时间（入队时间）
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 会话结束时间（NULL 表示进行中）
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- session_id 全局唯一，业务上一个 sessionId 只对应一次会话
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_conv_session
    ON cs_conversation.cs_conversation (session_id);

-- 按状态查询（座席工作台加载 ACTIVE 列表）
CREATE INDEX IF NOT EXISTS idx_cs_conv_status
    ON cs_conversation.cs_conversation (status) WHERE status != 'CLOSED';

COMMENT ON TABLE  cs_conversation.cs_conversation IS '客服会话生命周期记录表';
COMMENT ON COLUMN cs_conversation.cs_conversation.session_id      IS '前端唯一会话 ID，与 Redis chat:session:{id} 对应';
COMMENT ON COLUMN cs_conversation.cs_conversation.status          IS '会话状态：WAITING=等待接入, ACTIVE=接待中, CLOSED=已结束';
COMMENT ON COLUMN cs_conversation.cs_conversation.transfer_reason IS '转接人工的原因描述';

-- updated_at 自动维护触发器
CREATE OR
REPLACE FUNCTION cs_conversation.set_updated_at()
    RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR
REPLACE TRIGGER trg_cs_conv_updated
    BEFORE
UPDATE ON cs_conversation.cs_conversation
    FOR EACH ROW
EXECUTE FUNCTION cs_conversation.set_updated_at();

-- --------------------------------------------------------
-- 2. 消息明细表（cs_conversation_message）
--    记录每条对话消息，由 Redis Stream 异步消费写入。
--    角色值：user（访客）/ assistant（AI）/ agent（人工座席）
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS cs_conversation.cs_conversation_message
(
    id BIGSERIAL PRIMARY KEY,
    -- 关联会话（冗余 session_id，避免 JOIN cs_conversation）
    session_id VARCHAR(100) NOT NULL,
    -- 消息角色：user=访客, assistant=AI, agent=人工座席
    role       VARCHAR(20)  NOT NULL,
    -- 消息内容（不限长度）
    content    TEXT         NOT NULL,
    -- 消息时间（从 Redis Stream 消息的 timestamp 字段读取，保证顺序一致）
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 按 session_id + 时间查询历史消息（最常见的访问模式）
CREATE INDEX IF NOT EXISTS idx_cs_msg_session_time
    ON cs_conversation.cs_conversation_message (session_id, created_at);

COMMENT ON TABLE  cs_conversation.cs_conversation_message IS '对话消息明细表，由 Redis Stream 异步写入';
COMMENT ON COLUMN cs_conversation.cs_conversation_message.role      IS '消息角色：user=访客, assistant=AI, agent=人工座席';
COMMENT ON COLUMN cs_conversation.cs_conversation_message.session_id IS '冗余字段，避免查询历史时 JOIN cs_conversation';
