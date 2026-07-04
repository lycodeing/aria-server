package com.aria.conversation.infrastructure.dit.pipeline;

/**
 * 工具调用执行状态枚举。
 *
 * <p>替代 {@link ToolCallResult} 中的魔法字符串 "SUCCESS"/"ERROR"/"TIMEOUT"/"SKIPPED"，
 * 实现编译期类型安全，消除跨层字符串约定。前端 SSE 解析使用 {@code .name()} 序列化后的值。
 */
public enum ToolStatus {

    /** 工具调用成功，有有效响应 */
    SUCCESS,

    /** 工具调用失败（HTTP 非 2xx 或响应解析异常） */
    ERROR,

    /** 工具调用超时，未获得有效响应 */
    TIMEOUT,

    /** 工具被跳过（前置条件不满足，如 OPTIONAL 工具 LLM 决策不调用） */
    SKIPPED
}
