-- migration-007: cs_conversation 新增 AI_CHAT 状态，用于记录纯 AI 对话会话

-- ① 更新 status 列 CHECK 约束，增加 AI_CHAT
ALTER TABLE cs_conversation.cs_conversation
    DROP CONSTRAINT IF EXISTS cs_conversation_status_check;
ALTER TABLE cs_conversation.cs_conversation
    ADD CONSTRAINT cs_conversation_status_check
    CHECK (status IN ('WAITING', 'ACTIVE', 'CLOSED', 'AI_CHAT'));

-- 更新 status 列注释
COMMENT ON COLUMN cs_conversation.cs_conversation.status
    IS 'WAITING=等待接入, ACTIVE=座席接待中, CLOSED=已结束, AI_CHAT=纯AI对话（无转人工）';
