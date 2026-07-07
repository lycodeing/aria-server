package com.aria.conversation.application.service.payload;

/**
 * 业务错误的 SSE payload（对应 event:error）。
 *
 * <p>与 token / transfer / tool_* 等事件一样，统一 JSON 信封，
 * 前端解析器可以按同一套 {@code JSON.parse(data)} 逻辑处理所有事件。
 *
 * @param message 展示给用户的错误提示语
 */
public record ErrorPayload(String message) {}
