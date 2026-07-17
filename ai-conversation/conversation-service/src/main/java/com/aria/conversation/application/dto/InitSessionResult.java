package com.aria.conversation.application.dto;

import com.aria.conversation.domain.SessionStatus;

/**
 * 会话初始化结果，由 {@link com.aria.conversation.application.service.VisitorSessionService} 返回。
 *
 * @param sessionId 会话唯一标识，前端后续所有请求均需携带
 * @param status    当前会话状态（AI_CHAT / WAITING / ACTIVE）
 * @param isNew     true 表示本次新建，false 表示恢复已有会话
 */
public record InitSessionResult(
        String sessionId,
        SessionStatus status,
        boolean isNew
) {}
