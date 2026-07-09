package com.aria.conversation.application.service.payload;

import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;

/**
 * 工具调用完成的 SSE payload（对应 event:tool_done）。
 *
 * @param tool       工具标识（cs_tool.code）
 * @param status     执行结果字符串（ToolStatus.name()：SUCCESS/ERROR/TIMEOUT/SKIPPED）
 * @param durationMs 工具执行耗时（毫秒）
 * @param errorMsg   错误信息（status=ERROR/TIMEOUT 时有值，SUCCESS/SKIPPED 时为 null）
 */
public record ToolDonePayload(String tool, String status, long durationMs, String errorMsg) {

    /**
     * 从 {@link ToolCallResult} 构造 payload。
     * status 取枚举 {@code .name()} 保证与前端约定一致，携带 errorMsg 供前端展示失败原因。
     *
     * @param result 工具调用结果
     */
    public static ToolDonePayload from(ToolCallResult result) {
        return new ToolDonePayload(
                result.getToolCode(),
                result.getStatus().name(),
                result.getDurationMs(),
                result.getErrorMsg()
        );
    }

    /** MCP 工具执行成功时构造 payload。 */
    public static ToolDonePayload success(String toolName, long durationMs) {
        return new ToolDonePayload(toolName, "SUCCESS", durationMs, null);
    }

    /** MCP 工具执行失败时构造 payload。 */
    public static ToolDonePayload error(String toolName, long durationMs, String errorMsg) {
        return new ToolDonePayload(toolName, "ERROR", durationMs, errorMsg);
    }
}
