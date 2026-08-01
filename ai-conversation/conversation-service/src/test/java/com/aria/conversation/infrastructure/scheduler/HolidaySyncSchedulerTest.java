package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.application.service.BusinessHoursService;
import com.aria.conversation.domain.HolidaySource;
import com.aria.conversation.domain.HolidayType;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * HolidaySyncScheduler 单元测试。
 *
 * <p>通过 Mockito spy 覆盖 {@code fetchWithRetry}（protected），避免真实 HTTP 调用。
 *
 * <p><b>日期动态生成</b>：{@code syncYear} 的过滤窗口是「今天起 3 个月」，
 * 测试用日期必须相对 {@code LocalDate.now()} 动态计算（而非硬编码），
 * 否则随时间推移会因窗口过滤而失效（entries 被滤空，insertBatch 从未被调用）。
 *
 * <p>实际实现调用的是批量 {@code insertBatch}（upsert，非逐条 exists+insert），
 * 断言均以此为准。
 */
@ExtendWith(MockitoExtension.class)
class HolidaySyncSchedulerTest {

    @Mock BusinessHoursHolidayMapper  holidayMapper;
    @Mock BusinessHoursScheduleMapper scheduleMapper;
    @Mock BusinessHoursService        businessHoursService;

    HolidaySyncScheduler scheduler;

    /** 窗口内的一个法定节假日日期（今天 + 10 天），isOffDay=true */
    private static final LocalDate CLOSED_DATE = LocalDate.now().plusDays(10);
    /** 窗口内的一个调休补班日期（今天 + 20 天），isOffDay=false */
    private static final LocalDate WORKDAY_DATE = LocalDate.now().plusDays(20);

    @BeforeEach
    void setUp() {
        scheduler = new HolidaySyncScheduler(
                holidayMapper, scheduleMapper, businessHoursService, new ObjectMapper());
    }

    private String mockJson() {
        return """
                {
                  "year": %d,
                  "days": [
                    {"name": "元旦", "date": "%s", "isOffDay": true},
                    {"name": "元旦补班", "date": "%s", "isOffDay": false}
                  ]
                }
                """.formatted(LocalDate.now().getYear(), CLOSED_DATE, WORKDAY_DATE);
    }

    @Test
    @DisplayName("窗口内无数据时返回 0，不调用 insertBatch")
    void syncYear_noDataInWindow_skipsAndReturnsZero() {
        var spy = spy(scheduler);
        // 窗口外的日期（10 年前），过滤后 entries 为空
        String outOfWindowJson = """
                {"year":2016,"days":[{"name":"元旦","date":"2016-01-01","isOffDay":true}]}
                """;
        doReturn(outOfWindowJson).when(spy).fetchWithRetry(anyInt());

        int count = spy.syncYear(LocalDate.now().getYear());

        assertThat(count).isZero();
        verify(holidayMapper, never()).insertBatch(any());
        verify(businessHoursService, never()).evictCache(any());
    }

    @Test
    @DisplayName("CLOSED 节假日写入时 type=CLOSED，timeRanges 为 null")
    void syncYear_closedHoliday_insertsWithNullTimeRanges() {
        var spy = spy(scheduler);
        String closedOnlyJson = """
                {"year":%d,"days":[{"name":"元旦","date":"%s","isOffDay":true}]}
                """.formatted(LocalDate.now().getYear(), CLOSED_DATE);
        doReturn(closedOnlyJson).when(spy).fetchWithRetry(anyInt());
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        int count = spy.syncYear(LocalDate.now().getYear());

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayMapper, times(1)).insertBatch(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(HolidayType.CLOSED);
        assertThat(saved.getTimeRanges()).isNull();
        assertThat(saved.getDate()).isEqualTo(CLOSED_DATE);
        assertThat(saved.getSource()).isEqualTo(HolidaySource.AUTO);
        assertThat(saved.getRemark()).isEqualTo("元旦");
        verify(businessHoursService).evictCache(CLOSED_DATE);
    }

    @Test
    @DisplayName("WORKDAY 调休补班写入时 type=WORKDAY，timeRanges 来自周一排班")
    void syncYear_workdayHoliday_insertsWithMondayTimeRanges() {
        var spy = spy(scheduler);
        String workdayOnlyJson = """
                {"year":%d,"days":[{"name":"元旦补班","date":"%s","isOffDay":false}]}
                """.formatted(LocalDate.now().getYear(), WORKDAY_DATE);
        doReturn(workdayOnlyJson).when(spy).fetchWithRetry(anyInt());

        // 周一排班：09:00-18:00
        BusinessHoursScheduleEntity monday = new BusinessHoursScheduleEntity();
        BusinessHoursScheduleEntity.TimeRange range = new BusinessHoursScheduleEntity.TimeRange();
        range.setStart("09:00");
        range.setEnd("18:00");
        monday.setDayOfWeek(1);
        monday.setIsOpen(true);
        monday.setTimeRanges(List.of(range));
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(monday);
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        int count = spy.syncYear(LocalDate.now().getYear());

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayMapper, times(1)).insertBatch(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(HolidayType.WORKDAY);
        assertThat(saved.getTimeRanges()).isNotNull().hasSize(1);
        assertThat(saved.getTimeRanges().get(0).getStart()).isEqualTo("09:00");
        assertThat(saved.getTimeRanges().get(0).getEnd()).isEqualTo("18:00");
        assertThat(saved.getDate()).isEqualTo(WORKDAY_DATE);
        assertThat(saved.getSource()).isEqualTo(HolidaySource.AUTO);
    }

    @Test
    @DisplayName("周一排班不存在时，WORKDAY 的 timeRanges 为空列表")
    void syncYear_workdayHoliday_noMondaySchedule_insertsWithEmptyTimeRanges() {
        var spy = spy(scheduler);
        String workdayOnlyJson = """
                {"year":%d,"days":[{"name":"元旦补班","date":"%s","isOffDay":false}]}
                """.formatted(LocalDate.now().getYear(), WORKDAY_DATE);
        doReturn(workdayOnlyJson).when(spy).fetchWithRetry(anyInt());

        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null); // 无周一排班
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        spy.syncYear(LocalDate.now().getYear());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayMapper, times(1)).insertBatch(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(HolidayType.WORKDAY);
        assertThat(saved.getTimeRanges()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("窗口内两条记录批量写入，返回 insertBatch 的影响行数")
    void syncYear_twoEntriesInWindow_batchInsertsBoth() {
        var spy = spy(scheduler);
        doReturn(mockJson()).when(spy).fetchWithRetry(anyInt());
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);
        // 模拟 upsert 影响行数（不必等于 list.size()，即 SQL 层的 ON CONFLICT 语义）
        when(holidayMapper.insertBatch(any())).thenReturn(2);

        int count = spy.syncYear(LocalDate.now().getYear());

        assertThat(count).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(holidayMapper, times(1)).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(businessHoursService, times(2)).evictCache(any());
    }
}
