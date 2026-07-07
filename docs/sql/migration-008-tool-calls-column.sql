-- migration-008: 为 cs_conversation_message 添加 assistant 触发的 tool_calls 列
--
-- 补齐 LangChain4j tool_call ↔ tool_result 完整链路：
--   - migration-006 已为 role=tool 结果消息加入 tool_request_id / tool_name（结果侧）
--   - 本迁移新增 tool_calls_json，落库 role=assistant 触发的 tool_calls 请求侧
--   - 二者共同保障历史回放时 AI 请求工具 ↔ 工具返回结果的严格配对
ALTER TABLE cs_conversation.cs_conversation_message
    ADD COLUMN IF NOT EXISTS tool_calls_json TEXT;

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_calls_json
    IS 'assistant 触发的 tool_calls JSON 数组：[{"id":"...","name":"...","arguments":"..."}]。仅 role=assistant 且模型返回 tool_calls 时非空';
