package com.aria.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * CSAT 评价状态枚举。
 *
 * <p>通过 {@link EnumValue} 标注 {@link #value}，MyBatis-Plus 自动完成枚举与数据库 VARCHAR 列的双向映射。
 *
 * <ul>
 *   <li>{@link #PENDING}  — 已发出邀请，等待访客评价</li>
 *   <li>{@link #RATED}    — 访客已评价</li>
 *   <li>{@link #EXPIRED}  — 邀请已过期（超过 expiredAt 未评价）</li>
 *   <li>{@link #SKIPPED}  — 访客主动跳过</li>
 * </ul>
 */
public enum CsatStatus {

    /**
     * 等待访客评价
     */
    PENDING("PENDING"),

    /**
     * 访客已提交评分
     */
    RATED("RATED"),

    /**
     * 评价邀请已过期
     */
    EXPIRED("EXPIRED"),

    /**
     * 访客主动跳过
     */
    SKIPPED("SKIPPED");

    @EnumValue
    private final String value;

    CsatStatus(String value) {
        this.value = value;
    }

    @JsonCreator
    public static CsatStatus fromValue(String value) {
        if (value == null) return null;
        for (CsatStatus s : values()) {
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
