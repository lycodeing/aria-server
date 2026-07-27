package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 标签来源枚举。
 *
 * <ul>
 *   <li>{@link #PRESET} — 系统预置标签，由管理员创建</li>
 *   <li>{@link #CUSTOM} — 坐席自定义标签，由坐席在打标时即时创建</li>
 * </ul>
 */
public enum TagSource {

    PRESET("PRESET"),
    CUSTOM("CUSTOM");

    @EnumValue
    private final String value;

    TagSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TagSource fromValue(String value) {
        if (value == null) return null;
        for (TagSource s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
