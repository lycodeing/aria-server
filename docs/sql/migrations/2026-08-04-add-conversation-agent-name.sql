-- =============================================================================
-- 会话查询：为 cs_conversation 增加 agent_name 快照列
-- =============================================================================
-- 背景：
--   会话查询页（/session/history）需展示"接待客服"名称。此前实现为每次查询实时
--   调用 auth-service 将 agent_id 解析为 displayName，存在两个问题：
--     1. 热路径跨服务调用 + N+1，翻页性能差；
--     2. 历史真相被破坏 —— 客服改名后历史被追溯改写、客服离职删号后列表显示裸数字 ID。
--
-- 方案：
--   在会话「接入 / 转交」写库时，把当时的客服 displayName 快照进 agent_name 列（历史数据
--   不可变原则）。查询侧直接读快照，免跨服务调用、免 N+1，且冻结"当时是谁接待的"。
--
-- 执行方式（Flyway 未启用，手动执行）：
--   docker exec -i ai-cs-postgres psql -U postgres -d aria_cs \
--     < docs/sql/migrations/2026-08-04-add-conversation-agent-name.sql
-- =============================================================================

-- 1. 加列（幂等：已存在则跳过）
ALTER TABLE cs_conversation.cs_conversation
    ADD COLUMN IF NOT EXISTS agent_name varchar(100) DEFAULT NULL;

COMMENT ON COLUMN cs_conversation.cs_conversation.agent_name
    IS '接入座席显示名称快照（接入/转交时冻结，反映当时接待人，不随客服改名/离职变化）';

-- 2. 回填存量：用 cs_auth.sys_user.display_name 填充历史会话的 agent_name
--    agent_id 为 varchar，sys_user.id 为 bigint，用 id::text 匹配避免脏数据 cast 报错。
UPDATE cs_conversation.cs_conversation c
SET    agent_name = u.display_name
FROM   cs_auth.sys_user u
WHERE  c.agent_id = u.id::text
  AND  c.agent_id IS NOT NULL
  AND  c.agent_name IS NULL;
