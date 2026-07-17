package com.aria.conversation.application.service.payload;

/**
 * 服务端触发的「需要短信验证」信号，对应 SSE event:auth_required。
 *
 * <p>用于将当前散落在前端的关键词硬编码（例：订单/退款/投诉/账单）下沉到服务端策略，
 * 由后端在识别到「访客未认证 + 命中需认证意图/规则」时下发。前端收到后弹起手机号验证浮层。
 *
 * <p>本次 PR 仅定义 payload 与 {@code ChatEvent.EventType.AUTH_REQUIRED} 常量，
 * 具体触发点由后续 PR 补齐（意图判定/DIT pipeline 钩子）。
 *
 * @param reason 触发原因，前端可展示（如："处理"退款"需要验证身份"）；可为 null
 */
public record AuthRequiredPayload(String reason) {}
