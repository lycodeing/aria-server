package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.application.service.BusinessHoursService;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HolidaySyncScheduler 单元测试。
 *
 * <p>通过 Mockito spy 覆盖 {@code fetchWithRetry}（protected），避免真实 HTTP 调用。
 */
@ExtendWith(MockitoExtension.class)
class HolidaySyncSchedulerTest {

    @Mock BusinessHoursHolidayMapper  holidayMapper;
    @Mock BusinessHoursScheduleMapper scheduleMapper;
    @Mock BusinessHoursService        businessHoursService;

    HolidaySyncScheduler scheduler;

    // 测试用 JSON：一个 CLOSED 节假日 + 一个 WORKDAY 调休补班
    private static final String MOCK_JSON = """
            {
              "year": 2026,
              "days": [
                {"name": "元旦", "date": "2026-01-01", "isOffDay": true},
                {"name": "元旦补班", "date": "2025-12-27", "isOffDay": false}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        scheduler = new HolidaySyncScheduler(
                holidayMapper, scheduleMapper, businessHoursService, new ObjectMapper());
    }

    @Test
    @DisplayName("所有记录已存在时幂等跳过，返回 0，不调用 insert")
    @SuppressWarnings("unchecked")
    void syncYear_allExist_skipsAndReturnsZero() {
        var spy = spy(scheduler);
        doReturn(MOCK_JSON).when(spy).fetchWithRetry(2026);

        // 所有日期都已存在
        when(holidayMapper.exists(any())).thenReturn(true);
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);

        int count = spy.syncYear(2026);

        assertThat(count).isZero();
        verify(holidayMapper, never()).insert(any(BusinessHoursHolidayEntity.class));
        verify(businessHoursService, never()).evictCache(any());
    }

    @Test
    @DisplayName("CLOSED 节假日写入时 type=CLOSED，timeRanges 为 null")
    @SuppressWarnings("unchecked")
    void syncYear_closedHoliday_insertsWithNullTimeRanges() {
        var spy = spy(scheduler);
        // JSON 只含元旦（isOffDay=true），补班（isOffDay=false）标记为已存在
        String closedOnlyJson = """
                {"year":2026,"days":[{"name":"元旦","date":"2026-01-01","isOffDay":true}]}
                """;
        doReturn(closedOnlyJson).when(spy).fetchWithRetry(2026);

        when(holidayMapper.exists(any())).thenReturn(false);
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);

        spy.syncYear(2026);

        ArgumentCaptor<BusinessHoursHolidayEntity> captor =
                ArgumentCaptor.forClass(BusinessHoursHolidayEntity.class);
        verify(holidayMapper, times(1)).insert(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("CLOSED");
        assertThat(saved.getTimeRanges()).isNull();
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getSource()).isEqualTo("AUTO");
        assertThat(saved.getRemark()).isEqualTo("元旦");
    }

    @Test
    @DisplayName("WORKDAY 调休补班写入时 type=WORKDAY，timeRanges 来自周一排班")
    @SuppressWarnings("unchecked")
    void syncYear_workdayHoliday_insertsWithMondayTimeRanges() {
        var spy = spy(scheduler);
        String workdayOnlyJson = """
                {"year":2026,"days":[{"name":"元旦补班","date":"2025-12-27","isOffDay":false}]}
                """;
        doReturn(workdayOnlyJson).when(spy).fetchWithRetry(2026);

        when(holidayMapper.exists(any())).thenReturn(false);

        // 周一排班：09:00-18:00
        BusinessHoursScheduleEntity monday = new BusinessHoursScheduleEntity();
        BusinessHoursScheduleEntity.TimeRange range = new BusinessHoursScheduleEntity.TimeRange();
        range.setStart("09:00");
        range.setEnd("18:00");
        monday.setDayOfWeek(1);
        monday.setIsOpen(true);
        monday.setTimeRanges(List.of(range));
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(monday);

        int count = spy.syncYear(2026);

        assertThat(count).isEqualTo(1);

        ArgumentCaptor<BusinessHoursHolidayEntity> captor =
                ArgumentCaptor.forClass(BusinessHoursHolidayEntity.class);
        verify(holidayMapper, times(1)).insert(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("WORKDAY");
        assertThat(saved.getTimeRanges()).isNotNull().hasSize(1);
        assertThat(saved.getTimeRanges().get(0).getStart()).isEqualTo("09:00");
        assertThat(saved.getTimeRanges().get(0).getEnd()).isEqualTo("18:00");
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2025, 12, 27));
        assertThat(saved.getSource()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("周一排班不存在时，WORKDAY 的 timeRanges 为空列表")
    @SuppressWarnings("unchecked")
    void syncYear_workdayHoliday_noMondaySchedule_insertsWithEmptyTimeRanges() {
        var spy = spy(scheduler);
        String workdayOnlyJson = """
                {"year":2026,"days":[{"name":"元旦补班","date":"2025-12-27","isOffDay":false}]}
                """;
        doReturn(workdayOnlyJson).when(spy).fetchWithRetry(2026);

        when(holidayMapper.exists(any())).thenReturn(false);
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null); // 无周一排班

        spy.syncYear(2026);

        ArgumentCaptor<BusinessHoursHolidayEntity> captor =
                ArgumentCaptor.forClass(BusinessHoursHolidayEntity.class);
        verify(holidayMapper, times(1)).insert(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("WORKDAY");
        assertThat(saved.getTimeRanges()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("部分已存在时，只写入新记录并返回正确计数")
    @SuppressWarnings("unchecked")
    void syncYear_partialExists_onlyInsertsNewRecords() {
        var spy = spy(scheduler);
        doReturn(MOCK_JSON).when(spy).fetchWithRetry(2026);

        // 2026-01-01 已存在，2025-12-27 不存在
        when(holidayMapper.exists(any()))
                .thenReturn(true)   // 元旦已有
                .thenReturn(false); // 补班没有
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);

        int count = spy.syncYear(2026);

        assertThat(count).isEqualTo(1);
        verify(holidayMapper, times(1)).insert(any(BusinessHoursHolidayEntity.class));
        verify(businessHoursService, times(1)).evictCache(LocalDate.of(2025, 12, 27));
    }
}
