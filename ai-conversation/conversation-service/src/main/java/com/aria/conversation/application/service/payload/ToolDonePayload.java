package com.aria.conversation.application.service.payload;

import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;

/**
 * 工具调用完成的 SSE payload（对应 event:tool_done）。
 *
 * <p>替代原来的 {@code Map.of("tool", ..., "status", tr.getStatus(), "duration_ms", ...)} 写法，
 * 通过 {@link ToolCallResult} 构造，确保 status 字段统一使用枚举 {@code .name()} 序列化。
 *
 * @param tool       工具标识（cs_tool.code）
 * @param status     执行结果字符串（ToolStatus.name()：SUCCESS/ERROR/TIMEOUT/SKIPPED）
 * @param durationMs 工具执行耗时（毫秒）
 */
public record ToolDonePayload(String tool, String status, long durationMs) {

    /**
     * 从 {@link ToolCallResult} 构造 payload。
     *
     * <p>status 取枚举 {@code .name()}，保证序列化值与 {@link
     * com.aria.conversation.infrastructure.dit.pipeline.ToolStatus} 定义一致。
     *
     * @param result 工具调用结果
     */
    public static ToolDonePayload from(ToolCallResult result) {
        return new ToolDonePayload(
                result.getToolCode(),
                result.getStatus().name(),  // 枚举 → 字符串，统一用 .name()
                result.getDurationMs()
        );
    }
}
