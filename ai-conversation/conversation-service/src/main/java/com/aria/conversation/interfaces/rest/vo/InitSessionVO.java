package com.aria.conversation.interfaces.rest.vo;

/**
 * 会话初始化响应 VO。
 *
 * @param sessionId 会话唯一标识，前端后续所有请求均需携带
 * @param status    当前会话状态字符串（AI_CHAT / WAITING / ACTIVE）
 * @param isNew     true 表示本次新建，false 表示恢复已有会话
 */
public record InitSessionVO(
        String sessionId,
        String status,
        boolean isNew
) {}
