package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 节假日类型枚举。
 *
 * <ul>
 *   <li>{@link #CLOSED}  — 法定节假日，当天不提供服务（timeRanges 为空）</li>
 *   <li>{@link #WORKDAY} — 调休补班日，使用指定时间段（默认复用周一排班）</li>
 *   <li>{@link #CUSTOM}  — 管理员手动自定义，使用自定义时间段</li>
 * </ul>
 */
public enum HolidayType {

    CLOSED("CLOSED"),
    WORKDAY("WORKDAY"),
    CUSTOM("CUSTOM");

    @EnumValue
    private final String value;

    HolidayType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static HolidayType fromValue(String value) {
        if (value == null) return null;
        for (HolidayType t : values()) {
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
