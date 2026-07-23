package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessHoursServiceTest {

    @Mock BusinessHoursScheduleMapper     scheduleMapper;
    @Mock BusinessHoursHolidayMapper      holidayMapper;
    @Mock StringRedisTemplate             redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    BusinessHoursService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // default: no cache hit — lenient so tests that short-circuit via cache don't fail
        lenient().when(valueOps.get(any())).thenReturn(null);
        lenient().when(holidayMapper.selectByDate(any())).thenReturn(null);
        service = new BusinessHoursService(scheduleMapper, holidayMapper, redisTemplate, new ObjectMapper());
    }

    private BusinessHoursScheduleEntity schedule(int dow, boolean open, String start, String end) {
        var e = new BusinessHoursScheduleEntity();
        e.setDayOfWeek(dow);
        e.setIsOpen(open);
        var r = new BusinessHoursScheduleEntity.TimeRange();
        r.setStart(start); r.setEnd(end);
        e.setTimeRanges(open ? List.of(r) : List.of());
        return e;
    }

    @Test
    @DisplayName("工作日服务时间内 -> open")
    void workday_within_hours_isOpen() {
        // 2026-07-22 是周三（dow=3），09:30 在服务时间内
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai"));
        when(scheduleMapper.selectByDayOfWeek(3)).thenReturn(schedule(3, true, "09:00", "18:00"));
        assertThat(service.isOpen(now)).isTrue();
    }

    @Test
    @DisplayName("工作日服务时间外（下班后）-> closed")
    void workday_after_hours_isClosed() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 18, 1, 0, 0, ZoneId.of("Asia/Shanghai"));
        when(scheduleMapper.selectByDayOfWeek(3)).thenReturn(schedule(3, true, "09:00", "18:00"));
        assertThat(service.isOpen(now)).isFalse();
    }

    @Test
    @DisplayName("法定节假日 CLOSED -> closed")
    void holiday_closed_isClosed() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        var holiday = BusinessHoursHolidayEntity.builder()
                .date(now.toLocalDate()).type("CLOSED").build();
        when(holidayMapper.selectByDate(now.toLocalDate())).thenReturn(holiday);
        assertThat(service.isOpen(now)).isFalse();
    }

    @Test
    @DisplayName("调休补班 WORKDAY 且在服务时间内 -> open")
    void workday_holiday_within_hours_isOpen() {
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        var r = new BusinessHoursScheduleEntity.TimeRange();
        r.setStart("09:00"); r.setEnd("18:00");
        var holiday = BusinessHoursHolidayEntity.builder()
                .date(now.toLocalDate()).type("WORKDAY").timeRanges(List.of(r)).build();
        when(holidayMapper.selectByDate(now.toLocalDate())).thenReturn(holiday);
        assertThat(service.isOpen(now)).isTrue();
    }

    @Test
    @DisplayName("排班表为空时降级返回 true")
    void empty_schedule_degradesToOpen() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        when(scheduleMapper.selectByDayOfWeek(3)).thenReturn(null);
        assertThat(service.isOpen(now)).isTrue();
    }

    @Test
    @DisplayName("Redis 缓存命中时不走 DB")
    void isOpen_cacheHit_doesNotCallDb() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        // Simulate cache hit: open 09:00-18:00
        when(valueOps.get("business_hours:schedule:2026-07-22"))
                .thenReturn("[{\"start\":\"09:00\",\"end\":\"18:00\"}]");

        boolean result = service.isOpen(now);

        assertThat(result).isTrue();
        verify(scheduleMapper, never()).selectByDayOfWeek(anyInt());
        verify(holidayMapper, never()).selectByDate(any());
    }
}
