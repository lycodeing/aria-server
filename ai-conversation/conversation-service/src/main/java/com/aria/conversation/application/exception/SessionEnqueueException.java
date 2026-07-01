package com.aria.conversation.application.exception;

import lombok.Getter;

/**
 * 会话入队失败时抛出的应用层异常。
 *
 * <p>仅在 Redis 写入失败（不可恢复）时抛出。
 * MQ 发布失败属于可降级场景，不抛此异常。
 * Controller 捕获后返回 HTTP 503 Service Unavailable。
 */
@Getter
public class SessionEnqueueException extends RuntimeException {

    private final String sessionId;

    public SessionEnqueueException(String message, String sessionId) {
        super(message);
        this.sessionId = sessionId;
    }

    public SessionEnqueueException(String message, String sessionId, Throwable cause) {
        super(message, cause);
        this.sessionId = sessionId;
    }

}
