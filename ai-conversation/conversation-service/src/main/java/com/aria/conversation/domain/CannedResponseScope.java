package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 快捷回复可见范围枚举。
 *
 * <ul>
 *   <li>{@link #PUBLIC}  — 全员可见，由管理员维护</li>
 *   <li>{@link #PRIVATE} — 仅创建者可见，由坐席自行管理</li>
 * </ul>
 */
public enum CannedResponseScope {

    PUBLIC("PUBLIC"),
    PRIVATE("PRIVATE");

    @EnumValue
    private final String value;

    CannedResponseScope(String value) {
        this.value = value;
    }

    @JsonCreator
    public static CannedResponseScope fromValue(String value) {
        if (value == null) return null;
        for (CannedResponseScope s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
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
