package com.aria.conversation.application.exception;

import lombok.Getter;

/**
 * 会话入队失败时抛出的应用层异常。
 *
 * <p>两种入队失败场景：
 * <ul>
 *   <li>本基类 — Redis 写入失败（不可恢复）或会话已在队列（幂等兜底）</li>
 *   <li>{@link SessionEnqueueMqFailedException} — SESSION_START MQ 发布失败，
 *       Redis 已补偿回滚（子类，调用方须先 catch 子类再 catch 基类）</li>
 * </ul>
 *
 * <p>Controller 捕获后返回 HTTP 503 Service Unavailable。
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
