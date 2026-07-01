package com.aria.common.core.exception;

/**
 * 非法状态流转异常。
 * <p>当领域状态机校验失败时抛出（如 Story 从 DRAFT 直接流转到 DONE）。
 */
public class InvalidStatusTransitionException extends RuntimeException {

    private final String fromStatus;
    private final String toStatus;

    public InvalidStatusTransitionException(String fromStatus, String toStatus) {
        super(String.format("非法状态流转：%s → %s", fromStatus, toStatus));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public InvalidStatusTransitionException(Enum<?> from, Enum<?> to) {
        this(from.name(), to.name());
    }

    public static InvalidStatusTransitionException of(Enum<?> from, Enum<?> to) {
        return new InvalidStatusTransitionException(from, to);
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }
}
