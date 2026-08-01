package com.aria.conversation.application.exception;

/**
 * 会话入队因 MQ 发布失败而被回滚时抛出的专属异常。
 *
 * <p>继承自 {@link SessionEnqueueException}，用于区分两种入队失败场景：
 * <ul>
 *   <li>{@link SessionEnqueueMqFailedException} — SESSION_START MQ 发布失败，Redis 已回滚，
 *       会话<b>未入队</b>。调用方（如 {@code BuiltinTools.transferToAgent}）不应继续发 SSE transfer 事件。</li>
 *   <li>{@link SessionEnqueueException}（基类）— Redis 写入失败或会话已在队列（幂等兜底），
 *       调用方可继续发 SSE。</li>
 * </ul>
 *
 * <p><b>注意</b>：新增此子类后，{@link SessionEnqueueException} 的 Javadoc 需同步更新契约：
 * "当 Redis 写入失败时抛出基类；当 SESSION_START MQ 发布失败并已回滚 Redis 时抛出
 * {@link SessionEnqueueMqFailedException} 子类。调用方若需区分两种场景，须先 catch 子类再 catch 基类。"
 */
public class SessionEnqueueMqFailedException extends SessionEnqueueException {

    public SessionEnqueueMqFailedException(String message, String sessionId, Throwable cause) {
        super(message, sessionId, cause);
    }
}
