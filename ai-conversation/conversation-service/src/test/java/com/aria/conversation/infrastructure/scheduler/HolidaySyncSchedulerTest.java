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
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HolidaySyncScheduler 单元测试。
 *
 * <p>通过 Mockito spy 覆盖 {@code fetchWithRetry}（protected），避免真实 HTTP 调用。
 *
 * <p>注意：{@code syncYear} 的同步窗口为 [today, today+3mo]（由 LocalDate.now() 决定），
 * 因此测试数据日期必须相对"今天"动态生成，硬编码年份会随当前日期漂移导致断言失效。
 */
@ExtendWith(MockitoExtension.class)
class HolidaySyncSchedulerTest {

    @Mock BusinessHoursHolidayMapper  holidayMapper;
    @Mock BusinessHoursScheduleMapper scheduleMapper;
    @Mock BusinessHoursService        businessHoursService;

    HolidaySyncScheduler scheduler;

    // 同步窗口 [today, today+3mo] 内的测试日期
    private static final LocalDate CLOSED_DATE = LocalDate.now();
    private static final LocalDate WORKDAY_DATE = LocalDate.now().plusDays(1);
    // 窗口外日期（过去一天），用于验证过滤
    private static final LocalDate OUTSIDE_DATE = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        scheduler = new HolidaySyncScheduler(
                holidayMapper, scheduleMapper, businessHoursService, new ObjectMapper());
    }

    /** 用相对日期拼装 holiday-cn JSON（days 数组由调用方传入）。 */
    private String jsonWith(String daysJson) {
        return """
                {"year": %d, "days": [%s]}
                """.formatted(Year.now().getValue(), daysJson);
    }

    private String holidayEntryJson() {
        return "{\"name\":\"测试节假日\",\"date\":\"%s\",\"isOffDay\":true}"
                .formatted(CLOSED_DATE);
    }

    private String workdayEntryJson() {
        return "{\"name\":\"测试补班\",\"date\":\"%s\",\"isOffDay\":false}"
                .formatted(WORKDAY_DATE);
    }

    private String outsideEntryJson() {
        return "{\"name\":\"过期节日\",\"date\":\"%s\",\"isOffDay\":true}"
                .formatted(OUTSIDE_DATE);
    }

    /**
     * 覆盖 fetchWithRetry：同步窗口可能跨年（如 10~12 月执行时 cutoff 进入次年），
     * 额外的一次 fetch 返回空 days，避免真实 HTTP 调用且不引入重复数据。
     */
    private void stubFetch(HolidaySyncScheduler spy, String json) {
        int year = Year.now().getValue();
        int crossYear = LocalDate.now().plusMonths(3).getYear();
        doReturn(json).when(spy).fetchWithRetry(eq(year));
        if (crossYear != year) {
            doReturn("{\"year\": %d, \"days\": []}".formatted(crossYear))
                    .when(spy).fetchWithRetry(eq(crossYear));
        }
    }

    @Test
    @DisplayName("窗口外无数据时返回 0，不触发任何查询/写入")
    void syncYear_allOutsideWindow_returnsZero() {
        var spy = spy(scheduler);
        stubFetch(spy, jsonWith(outsideEntryJson()));

        int count = spy.syncYear(Year.now().getValue());

        assertThat(count).isZero();
        verify(scheduleMapper, never()).selectByDayOfWeek(anyInt());
        verify(holidayMapper, never()).insertBatch(any());
        verify(businessHoursService, never()).evictCache(any());
    }

    @Test
    @DisplayName("CLOSED 节假日写入时 type=CLOSED，timeRanges 为 null")
    void syncYear_closedHoliday_insertsWithNullTimeRanges() {
        var spy = spy(scheduler);
        stubFetch(spy, jsonWith(holidayEntryJson()));

        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        int count = spy.syncYear(Year.now().getValue());

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(holidayMapper).insertBatch(captor.capture());
        verify(businessHoursService).evictCache(CLOSED_DATE);

        BusinessHoursHolidayEntity saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(HolidayType.CLOSED);
        assertThat(saved.getTimeRanges()).isNull();
        assertThat(saved.getDate()).isEqualTo(CLOSED_DATE);
        assertThat(saved.getSource()).isEqualTo(HolidaySource.AUTO);
        assertThat(saved.getRemark()).isEqualTo("测试节假日");
    }

    @Test
    @DisplayName("WORKDAY 调休补班写入时 type=WORKDAY，timeRanges 来自周一排班")
    void syncYear_workdayHoliday_insertsWithMondayTimeRanges() {
        var spy = spy(scheduler);
        stubFetch(spy, jsonWith(workdayEntryJson()));

        BusinessHoursScheduleEntity monday = new BusinessHoursScheduleEntity();
        BusinessHoursScheduleEntity.TimeRange range = new BusinessHoursScheduleEntity.TimeRange();
        range.setStart("09:00");
        range.setEnd("18:00");
        monday.setDayOfWeek(1);
        monday.setIsOpen(true);
        monday.setTimeRanges(List.of(range));
        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(monday);
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        int count = spy.syncYear(Year.now().getValue());

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(holidayMapper).insertBatch(captor.capture());

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
        stubFetch(spy, jsonWith(workdayEntryJson()));

        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null); // 无周一排班

        spy.syncYear(Year.now().getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessHoursHolidayEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(holidayMapper).insertBatch(captor.capture());

        BusinessHoursHolidayEntity saved = captor.getValue().get(0);
        assertThat(saved.getType()).isEqualTo(HolidayType.WORKDAY);
        assertThat(saved.getTimeRanges()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("多天批量写入：返回 insertBatch 影响行数，并逐日失效缓存")
    void syncYear_batchInsert_returnsCountAndEvictsCache() {
        var spy = spy(scheduler);
        stubFetch(spy, jsonWith(holidayEntryJson() + "," + workdayEntryJson()));

        when(scheduleMapper.selectByDayOfWeek(1)).thenReturn(null);
        // 模拟 upsert：CLOSED 冲突跳过、WORKDAY 新增
        when(holidayMapper.insertBatch(any())).thenReturn(1);

        int count = spy.syncYear(Year.now().getValue());

        assertThat(count).isEqualTo(1);
        verify(businessHoursService, times(2)).evictCache(any(LocalDate.class));
    }
}
