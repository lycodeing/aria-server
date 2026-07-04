package com.aria.conversation.application.service.payload;

import com.aria.conversation.infrastructure.dit.pipeline.ToolStatus;

/**
 * 工具调用开始的 SSE payload（对应 event:tool_call）。
 *
 * <p>序列化为 JSON 后通过 SSE 发送给前端，前端按字段名解析，
 * 替代原来的 {@code Map.of("tool", ..., "status", "running")} 魔法值写法。
 * status 字段使用 {@link ToolStatus#RUNNING} 枚举序列化，与 tool_done 事件的状态值保持一致。
 *
 * @param tool   工具标识（即 cs_tool.code）
 * @param status 固定为 {@link ToolStatus#RUNNING}，表示工具调用进行中
 */
public record ToolCallPayload(String tool, String status) {

    /**
     * 构造"调用中"状态的 payload，status 取 {@link ToolStatus#RUNNING#name()} 保持枚举一致性。
     *
     * @param toolCode 工具标识
     */
    public static ToolCallPayload running(String toolCode) {
        return new ToolCallPayload(toolCode, ToolStatus.RUNNING.name());
    }
}
