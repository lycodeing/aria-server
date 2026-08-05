package com.aria.conversation.interfaces.dto;

import java.time.OffsetDateTime;

/**
 * 统计时间范围枚举。
 *
 * <p>Admin 统计接口的 {@code period} 参数，非法值由 Controller 显式校验后返回 400。
 */
public enum StatsPeriod {

    TODAY("today"),
    LAST_7D("7d"),
    LAST_30D("30d");

    private final String code;

    StatsPeriod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 解析 period 参数，非法值抛 {@link IllegalArgumentException}（Controller 转 400）。
     *
     * @param raw 原始参数，如 today / 7d / 30d
     * @return 对应枚举
     */
    public static StatsPeriod parse(String raw) {
        if (raw != null) {
            for (StatsPeriod p : values()) {
                if (p.code.equalsIgnoreCase(raw)) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException("非法 period 参数：" + raw + "，可选值 today / 7d / 30d");
    }

    /**
     * 本 period 的起始时间（含）。TODAY = 当天零点，7d/30d = N 天前的同一时刻。
     *
     * @return 起始时间
     */
    public OffsetDateTime startTime() {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (this) {
            case TODAY -> now.toLocalDate().atStartOfDay(now.getOffset()).toOffsetDateTime();
            case LAST_7D -> now.minusDays(7);
            case LAST_30D -> now.minusDays(30);
        };
    }
}
