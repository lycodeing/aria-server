package com.aria.conversation.domain.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * SLA 违规类型。
 *
 * <ul>
 *   <li>{@link #WAIT}   — 等待时长（访客从入队到座席接入的时间）</li>
 *   <li>{@link #FRT}    — 首次响应时长（First Reply Time）</li>
 *   <li>{@link #HANDLE} — 处理时长（整个会话时长）</li>
 * </ul>
 */
public enum BreachType {
    WAIT("WAIT"),
    FRT("FRT"),
    HANDLE("HANDLE");

    @EnumValue
    private final String value;

    BreachType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static BreachType fromValue(String value) {
        if (value == null) return null;
        for (BreachType t : values()) {
            if (t.value.equalsIgnoreCase(value)) return t;
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
