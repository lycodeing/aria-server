package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * CSAT 评价来源渠道枚举。
 *
 * <ul>
 *   <li>{@link #AI}    — AI 对话结束后发出的评价邀请</li>
 *   <li>{@link #HUMAN} — 人工座席会话结束后发出的评价邀请</li>
 * </ul>
 */
public enum CsatChannel {

    /**
     * AI 对话渠道
     */
    AI("AI"),

    /**
     * 人工座席渠道
     */
    HUMAN("HUMAN");

    @EnumValue
    private final String value;

    CsatChannel(String value) {
        this.value = value;
    }

    @JsonCreator
    public static CsatChannel fromValue(String value) {
        if (value == null) return null;
        for (CsatChannel s : values()) {
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
