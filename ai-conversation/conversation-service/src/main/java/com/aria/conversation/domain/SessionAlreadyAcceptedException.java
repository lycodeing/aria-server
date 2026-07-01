package com.aria.conversation.domain;

/**
 * 会话已被其他座席接入时抛出的领域异常。
 *
 * <p>用于 {@code SessionQueueService.accept()} 中 Lua CAS 返回 0（被抢占）的场景。
 * Controller 捕获后返回 HTTP 409 Conflict，防止重复接入同一会话。
 */
public class SessionAlreadyAcceptedException extends RuntimeException {

    private final String sessionId;

    public SessionAlreadyAcceptedException(String sessionId) {
        super("会话已被其他座席接入，请刷新队列: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
