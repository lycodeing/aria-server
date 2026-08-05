-- 2026-08-05 P0-C 坐席纠错反馈：新增 cs_session_feedback 表
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
CREATE TABLE IF NOT EXISTS cs_conversation.cs_session_feedback
(
    id             BIGSERIAL PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    message_id     VARCHAR(64),
    feedback_type  VARCHAR(20)  NOT NULL,
    original_query TEXT         NOT NULL,
    correct_intent VARCHAR(64),
    correct_answer TEXT,
    agent_id       BIGINT,
    accumulated    BOOLEAN      NOT NULL DEFAULT FALSE,
    kb_queued      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_session_feedback_session
    ON cs_conversation.cs_session_feedback (session_id);
CREATE INDEX IF NOT EXISTS idx_session_feedback_pending
    ON cs_conversation.cs_session_feedback (accumulated, kb_queued)
    WHERE accumulated = FALSE OR kb_queued = FALSE;

COMMENT ON TABLE cs_conversation.cs_session_feedback
    IS '坐席对 AI 回答的纠错/点赞反馈';
COMMENT ON COLUMN cs_conversation.cs_session_feedback.feedback_type
    IS 'WRONG_INTENT（意图错，回写样本库）| WRONG_ANSWER（回答错，待 KB 审核）| GOOD（点赞，仅计数）';
COMMENT ON COLUMN cs_conversation.cs_session_feedback.accumulated
    IS '是否已写入 intent_example_vectors';
COMMENT ON COLUMN cs_conversation.cs_session_feedback.kb_queued
    IS '是否已推入 KB 审核队列（当前恒为 false，KB 审核队列 P1 迭代补全后启用）';
