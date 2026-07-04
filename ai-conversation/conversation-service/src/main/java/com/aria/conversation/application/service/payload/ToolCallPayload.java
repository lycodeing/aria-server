package com.aria.conversation.application.service.payload;

/**
 * 工具调用开始的 SSE payload（对应 event:tool_call）。
 *
 * <p>序列化为 JSON 后通过 SSE 发送给前端，前端按字段名解析，
 * 替代原来的 {@code Map.of("tool", ..., "status", "running")} 魔法值写法。
 *
 * @param tool   工具标识（即 cs_tool.code）
 * @param status 固定为 "running"，表示工具调用进行中
 */
public record ToolCallPayload(String tool, String status) {

    /**
     * 构造"调用中"状态的 payload。
     *
     * @param toolCode 工具标识
     */
    public static ToolCallPayload running(String toolCode) {
        return new ToolCallPayload(toolCode, "running");
    }
}
