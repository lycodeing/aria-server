package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 节假日数据来源枚举。
 *
 * <ul>
 *   <li>{@link #AUTO}   — 由系统定时任务从 NateScarlet/holiday-cn 自动同步</li>
 *   <li>{@link #MANUAL} — 由管理员手动录入或覆盖</li>
 * </ul>
 */
public enum HolidaySource {

    AUTO("AUTO"),
    MANUAL("MANUAL");

    @EnumValue
    private final String value;

    HolidaySource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static HolidaySource fromValue(String value) {
        if (value == null) return null;
        for (HolidaySource s : values()) {
            if (s.value.equalsIgnoreCase(value)) return s;
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
