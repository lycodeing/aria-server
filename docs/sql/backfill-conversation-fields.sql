-- =====================================================================
-- 存量数据修复：补全 cs_conversation.cs_conversation 空字段
--
-- 背景：accepted_at / first_reply_at / closed_by 为事后新增列，
--       历史会话记录这三列为 NULL；closed_by 在 closeBySessionId
--       之前也未写入。本脚本对存量数据做最优近似补全。
--
-- 执行前提：已完成 schema 变更（accepted_at / first_reply_at / closed_by 列存在）
-- 执行方式：psql -U postgres -d ai_customerservice -f backfill-conversation-fields.sql
-- 幂等性：所有 UPDATE 均加 IS NULL 条件，重复执行安全
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------
-- Step 1  first_reply_at
--         从消息表取首条 role='agent' 消息的时间，最精确。
--         未收到过 agent 消息的会话（纯 AI 对话）不更新，保持 NULL。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation c
SET    first_reply_at = sub.first_agent_at
FROM (
    SELECT session_id,
           MIN(created_at) AS first_agent_at
    FROM   cs_conversation.cs_conversation_message
    WHERE  role = 'agent'
    GROUP  BY session_id
) sub
WHERE  c.session_id    = sub.session_id
  AND  c.first_reply_at IS NULL;

-- ---------------------------------------------------------------
-- Step 2  accepted_at
--         WAITING/AI_CHAT 状态语义上不应有 accepted_at，跳过。
--         ACTIVE/CLOSED 会话：优先使用 first_reply_at（座席首回复
--         前必已接入，误差通常在秒级）；若该会话无 agent 消息则
--         退化为 started_at（等待时长统计会偏大，但优于 NULL）。
--
--         注意：Step 1 须先执行，保证本步能读到刚写入的 first_reply_at。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    accepted_at = COALESCE(first_reply_at, started_at)
WHERE  status      IN ('ACTIVE', 'CLOSED')
  AND  accepted_at IS NULL;

-- ---------------------------------------------------------------
-- Step 3  ended_at
--         closeBySessionId 明确将 updated_at 设为 ended_at，
--         因此对存量 CLOSED 会话用 updated_at 反向恢复语义等价。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    ended_at = updated_at
WHERE  status   = 'CLOSED'
  AND  ended_at IS NULL;

-- ---------------------------------------------------------------
-- Step 4  closed_by
--         历史数据无法还原关闭发起方，统一标记为 system。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    closed_by = 'system'
WHERE  status    = 'CLOSED'
  AND  closed_by IS NULL;

COMMIT;

-- ---------------------------------------------------------------
-- 验证（执行后应全部返回 0）
-- ---------------------------------------------------------------
SELECT 'accepted_at NULL (ACTIVE/CLOSED)' AS check_item,
       COUNT(*)                            AS remaining_nulls
FROM   cs_conversation.cs_conversation
WHERE  status IN ('ACTIVE', 'CLOSED')
  AND  accepted_at IS NULL
UNION ALL
SELECT 'first_reply_at NULL (has agent msg)',
       COUNT(*)
FROM   cs_conversation.cs_conversation c
WHERE  first_reply_at IS NULL
  AND  EXISTS (
      SELECT 1 FROM cs_conversation.cs_conversation_message m
      WHERE  m.session_id = c.session_id
        AND  m.role = 'agent'
  )
UNION ALL
SELECT 'ended_at NULL (CLOSED)',
       COUNT(*)
FROM   cs_conversation.cs_conversation
WHERE  status   = 'CLOSED'
  AND  ended_at IS NULL
UNION ALL
SELECT 'closed_by NULL (CLOSED)',
       COUNT(*)
FROM   cs_conversation.cs_conversation
WHERE  status   = 'CLOSED'
  AND  closed_by IS NULL;
