-- migration-006: 为 cs_conversation_message 添加工具调用相关列
ALTER TABLE cs_conversation.cs_conversation_message
    ADD COLUMN IF NOT EXISTS tool_request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS tool_name       VARCHAR(128);
COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_request_id
    IS 'LangChain4j ToolExecutionRequest ID，role=tool 时填充，用于关联工具调用上下文';
COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_name
    IS '工具名称，role=tool 时填充';
