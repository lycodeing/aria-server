package com.aria.conversation.interfaces.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StatsPeriod} 解析与起始时间单测。
 */
@DisplayName("StatsPeriod 解析")
class StatsPeriodTest {

    @Test
    @DisplayName("合法值大小写不敏感解析")
    void parse_validValues() {
        assertThat(StatsPeriod.parse("today")).isEqualTo(StatsPeriod.TODAY);
        assertThat(StatsPeriod.parse("7d")).isEqualTo(StatsPeriod.LAST_7D);
        assertThat(StatsPeriod.parse("30D")).isEqualTo(StatsPeriod.LAST_30D);
    }

    @Test
    @DisplayName("非法值抛 IllegalArgumentException（Controller 转 400）")
    void parse_invalidValue_throws() {
        assertThatThrownBy(() -> StatsPeriod.parse("yesterday"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatsPeriod.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TODAY 起始时间为当天零点，7d/30d 早于当前时刻")
    void startTime_boundaries() {
        OffsetDateTime now = OffsetDateTime.now();

        OffsetDateTime today = StatsPeriod.TODAY.startTime();
        assertThat(today.getHour()).isZero();
        assertThat(today.getMinute()).isZero();
        assertThat(today.toLocalDate()).isEqualTo(now.toLocalDate());

        assertThat(StatsPeriod.LAST_7D.startTime()).isBefore(now);
        assertThat(StatsPeriod.LAST_30D.startTime()).isBefore(StatsPeriod.LAST_7D.startTime());
    }
}
