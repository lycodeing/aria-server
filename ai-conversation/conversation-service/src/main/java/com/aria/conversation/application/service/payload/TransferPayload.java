package com.aria.conversation.application.service.payload;

/**
 * 自动转人工的 SSE payload（对应 event:transfer）。
 *
 * <p>合并文字提示与语义信号为单一事件，前端只需处理一次即可：
 * 读取 {@code message} 渲染气泡文字，并根据事件类型切换 WebSocket 模式。
 *
 * @param reason  转人工原因标识（取意图 code，便于前端埋点和日志追踪）
 * @param message 展示给用户的转接提示语（来自意图配置的 fallback_reply）
 */
public record TransferPayload(String reason, String message) {}
