package com.aria.conversation.infrastructure.persistence;

import java.util.List;

/**
 * 消息持久化失败异常。
 *
 * <p>I8 修复：替代原 {@code RuntimeException}，遵循阿里巴巴 Java 开发手册
 * 「禁止抛出 RuntimeException/Exception 等顶层异常」规范。
 * 调用方可精确捕获此异常决定是否 ACK/NACK（触发 MQ 重试 → DLQ）。
 */
public class MessagePersistException extends RuntimeException {

    private final List<String> failures;

    public MessagePersistException(String message, List<String> failures) {
        super(message);
        this.failures = List.copyOf(failures);
    }

    /**
     * 返回失败的消息标识列表（格式：sessionId/role）。
     *
     * @return 不可变列表
     */
    public List<String> getFailures() {
        return failures;
    }
}
