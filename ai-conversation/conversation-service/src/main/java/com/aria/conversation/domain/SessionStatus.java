package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 会话状态枚举，实现状态机转换规则。
 *
 * <p>通过 {@link EnumValue} 标注 {@link #value} 字段，
 * MyBatis-Plus 自动完成枚举与 DB VARCHAR 列的双向映射，无需手动调用 {@code .name()}。
 *
 * <p>合法转换路径：
 * <pre>
 *   WAITING → ACTIVE  （座席接入）
 *   WAITING → CLOSED  （未接入直接取消）
 *   ACTIVE  → CLOSED  （座席结束或断线）
 * </pre>
 */
public enum SessionStatus {

    /** 等待座席接入 */
    WAITING("WAITING"),

    /** 座席已接入，对话进行中 */
    ACTIVE("ACTIVE"),

    /** 会话已结束（终止状态，不可再转换） */
    CLOSED("CLOSED");

    /**
     * DB 存储值（与数据库列 VARCHAR 值一一对应）。
     * 【强制】{@link EnumValue} 标注此字段，MyBatis-Plus 以该值与数据库列双向映射。
     */
    @EnumValue
    private final String value;

    SessionStatus(String value) {
        this.value = value;
    }

    /**
     * 获取 DB 存储值。
     *
     * @return 大写状态字符串（WAITING / ACTIVE / CLOSED）
     */
    public String getValue() {
        return value;
    }

    /**
     * 执行状态转换，并校验合法性。
     *
     * @param next 目标状态
     * @return 新状态
     * @throws IllegalStateException 非法状态转换时抛出
     */
    public SessionStatus transitionTo(SessionStatus next) {
        return switch (this) {
            case WAITING -> {
                if (next == ACTIVE || next == CLOSED) {
                    yield next;
                }
                throw new IllegalStateException(
                        String.format("非法状态转换: %s → %s", this, next));
            }
            case ACTIVE -> {
                if (next == CLOSED) {
                    yield next;
                }
                throw new IllegalStateException(
                        String.format("非法状态转换: %s → %s", this, next));
            }
            case CLOSED -> throw new IllegalStateException(
                    String.format("CLOSED 是终止状态，不可再转换: %s → %s", this, next));
        };
    }

    /**
     * 是否为终止状态。
     *
     * @return true 表示 CLOSED，不可再转换
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    @Override
    public String toString() {
        return value;
    }
}
