package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话关闭方枚举。
 *
 * <p>DB 存储值为小写字符串，与 WebSocket 事件协议中的 {@code closedBy} 字段保持一致。
 *
 * <ul>
 *   <li>{@link #AGENT}   — 座席主动关闭</li>
 *   <li>{@link #VISITOR} — 访客主动关闭</li>
 *   <li>{@link #SYSTEM}  — 系统自动关闭（超时/异常）</li>
 * </ul>
 */
public enum ClosedBy {

    /**
     * 座席主动关闭
     */
    AGENT("agent"),

    /**
     * 访客主动关闭
     */
    VISITOR("visitor"),

    /**
     * 系统自动关闭
     */
    SYSTEM("system");

    @EnumValue
    private final String value;

    ClosedBy(String value) {
        this.value = value;
    }

    /**
     * 从字符串解析枚举（大小写不敏感）。
     * 用于 MQ 消费端从 payload 还原枚举值；无法匹配时返回 null，调用方应降级为 {@link #SYSTEM}。
     */
    @JsonCreator
    public static ClosedBy fromValue(String value) {
        if (value == null) return null;
        for (ClosedBy cb : values()) {
            if (cb.value.equalsIgnoreCase(value)) return cb;
        }
        return null;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
