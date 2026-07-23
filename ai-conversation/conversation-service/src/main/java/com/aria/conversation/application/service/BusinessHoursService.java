package com.aria.conversation.application.service;

import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessHoursService implements IBusinessHoursCalculator {

    private static final String   CACHE_KEY_PREFIX = "business_hours:schedule:";
    private static final ZoneId   ZONE             = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BusinessHoursScheduleMapper scheduleMapper;
    private final BusinessHoursHolidayMapper  holidayMapper;
    private final StringRedisTemplate         redisTemplate;

    /**
     * 判断当前时刻是否在服务时间内。
     *
     * <p>降级：若排班表为空（意外被删），记录 WARN 并返回 true（允许转人工）。
     */
    public boolean isOpen(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        List<BusinessHoursScheduleEntity.TimeRange> ranges = loadTodayRanges(today);

        if (ranges == null) {
            log.warn("[BusinessHours] schedule table is empty, degrading to open");
            return true;
        }

        LocalTime currentTime = now.toLocalTime();
        return ranges.stream().anyMatch(r ->
                !currentTime.isBefore(LocalTime.parse(r.getStart(), TIME_FMT))
                && currentTime.isBefore(LocalTime.parse(r.getEnd(), TIME_FMT)));
    }

    /**
     * 返回下一个开放时间的格式化字符串，如 "2026-07-24 09:00"。
     * 用于离线消息占位符 {nextOpenTime} 的替换。
     */
    public String nextOpenTime(ZonedDateTime now) {
        ZonedDateTime cursor = now.plusMinutes(1);
        // 最多向前查 14 天，防无限循环
        for (int i = 0; i < 14 * 24 * 60; i++) {
            if (isOpen(cursor)) {
                return cursor.format(DT_FMT);
            }
            cursor = cursor.plusMinutes(1);
        }
        return "—";
    }

    /** 供 SlaBreachEvaluator 使用，计算区间内业务时间秒数（按秒粒度累加）。 */
    @Override
    public long calcBusinessSeconds(OffsetDateTime start, OffsetDateTime end) {
        long seconds = 0;
        ZonedDateTime cursor = start.atZoneSameInstant(ZONE);
        ZonedDateTime endZ   = end.atZoneSameInstant(ZONE);
        while (cursor.isBefore(endZ)) {
            if (isOpen(cursor)) seconds++;
            cursor = cursor.plusSeconds(1);
        }
        return seconds;
    }

    /** 管理员修改排班或节假日后调用，主动失效缓存。 */
    public void evictCache(LocalDate date) {
        redisTemplate.delete(CACHE_KEY_PREFIX + date);
    }

    // ── 私有：加载当天生效时间段（含节假日覆盖） ──────────────────

    private List<BusinessHoursScheduleEntity.TimeRange> loadTodayRanges(LocalDate date) {
        // 1. 节假日覆盖
        BusinessHoursHolidayEntity holiday = holidayMapper.selectByDate(date);
        if (holiday != null) {
            return switch (holiday.getType()) {
                case "CLOSED"  -> Collections.emptyList();
                case "WORKDAY", "CUSTOM" -> holiday.getTimeRanges();
                default -> null;
            };
        }
        // 2. 周排班
        int dow = date.getDayOfWeek().getValue(); // 1=周一 … 7=周日
        BusinessHoursScheduleEntity schedule = scheduleMapper.selectByDayOfWeek(dow);
        if (schedule == null) return null;
        if (!Boolean.TRUE.equals(schedule.getIsOpen())) return Collections.emptyList();
        return schedule.getTimeRanges();
    }
}
