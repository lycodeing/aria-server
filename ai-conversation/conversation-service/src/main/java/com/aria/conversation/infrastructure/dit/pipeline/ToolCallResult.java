package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import lombok.Builder;
import lombok.Data;

/**
 * 工具调用结果。
 *
 * @param toolCode    工具标识（cs_tool.code）
 * @param status      执行状态（枚举，消除魔法字符串）
 * @param response    工具返回的原始响应内容（已按 responseJsonpath 提取）
 * @param httpStatus  HTTP 状态码（HTTP 工具有值）
 * @param durationMs  耗时毫秒
 * @param errorMsg    错误信息（status=ERROR/TIMEOUT 时有值）
 */
@Data
@Builder
public class ToolCallResult {

    private String toolCode;
    /** 执行状态，使用 {@link ToolStatus} 枚举，序列化时调用 {@link ToolStatus#name()} */
    private ToolStatus status;
    private String response;
    private Integer httpStatus;
    private long durationMs;
    private String errorMsg;

    /** 工具是否执行成功 */
    public boolean isSuccess() {
        return ToolStatus.SUCCESS == status;
    }

    public static ToolCallResult success(String toolCode, String response,
                                         int httpStatus, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.SUCCESS)
                .response(response)
                .httpStatus(httpStatus)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult error(String toolCode, String errorMsg, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.ERROR)
                .errorMsg(errorMsg)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult timeout(String toolCode, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.TIMEOUT)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult skipped(String toolCode) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.SKIPPED)
                .build();
    }
}

