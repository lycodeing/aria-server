package com.aria.conversation.infrastructure.dit.pipeline;

/**
 * 工具调用执行状态枚举。
 *
 * <p>替代 {@link ToolCallResult} 中的魔法字符串，实现编译期类型安全。
 * 前端 SSE 解析依赖 {@link #name()} 序列化后的值，枚举成员名称视为 public API，不可随意重命名。
 *
 * <p><b>序列化约定</b>：序列化时统一调用 {@code .name()}，反序列化时用 {@code valueOf()}。
 * 前端 tool_call/tool_done 事件的 status 字段与此枚举一一对应。
 */
public enum ToolStatus {

    /** 工具调用进行中（瞬态，仅用于 SSE tool_call 事件） */
    RUNNING,

    /** 工具调用成功，有有效响应 */
    SUCCESS,

    /** 工具调用失败（HTTP 非 2xx 或响应解析异常） */
    ERROR,

    /** 工具调用超时，未获得有效响应 */
    TIMEOUT,

    /** 工具被跳过（前置条件不满足，如 OPTIONAL 工具 LLM 决策不调用） */
    SKIPPED
}
