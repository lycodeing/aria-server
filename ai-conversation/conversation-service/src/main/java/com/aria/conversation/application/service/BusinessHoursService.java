package com.aria.conversation.application.service;

import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    private final ObjectMapper                objectMapper;

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

    /**
     * 供 SlaBreachEvaluator 使用，计算区间内业务时间秒数。
     *
     * <p>采用区间交集算法（O(天数 × 时间段数)），逐日加载当天排班，
     * 计算 [start, end] 与各营业时间段的重叠秒数并累加。
     * 相较按秒步进的 O(elapsed_seconds) 实现，性能提升数千倍。
     */
    @Override
    public long calcBusinessSeconds(OffsetDateTime start, OffsetDateTime end) {
        ZonedDateTime startZ = start.atZoneSameInstant(ZONE);
        ZonedDateTime endZ   = end.atZoneSameInstant(ZONE);
        if (!startZ.isBefore(endZ)) {
            return 0;
        }

        long totalSeconds = 0;
        LocalDate day     = startZ.toLocalDate();
        LocalDate lastDay = endZ.toLocalDate();

        while (!day.isAfter(lastDay)) {
            List<BusinessHoursScheduleEntity.TimeRange> ranges = loadTodayRanges(day);
            if (ranges != null && !ranges.isEmpty()) {
                for (BusinessHoursScheduleEntity.TimeRange range : ranges) {
                    ZonedDateTime rangeStart = day.atTime(
                            LocalTime.parse(range.getStart(), TIME_FMT)).atZone(ZONE);
                    ZonedDateTime rangeEnd   = day.atTime(
                            LocalTime.parse(range.getEnd(), TIME_FMT)).atZone(ZONE);

                    // 与查询区间求交集
                    ZonedDateTime overlapStart = rangeStart.isBefore(startZ) ? startZ : rangeStart;
                    ZonedDateTime overlapEnd   = rangeEnd.isAfter(endZ)      ? endZ   : rangeEnd;

                    if (overlapStart.isBefore(overlapEnd)) {
                        totalSeconds += ChronoUnit.SECONDS.between(overlapStart, overlapEnd);
                    }
                }
            }
            day = day.plusDays(1);
        }

        return totalSeconds;
    }

    /** 管理员修改排班或节假日后调用，主动失效缓存。 */
    public void evictCache(LocalDate date) {
        redisTemplate.delete(CACHE_KEY_PREFIX + date);
    }

    // ── 私有：加载当天生效时间段（含节假日覆盖） ──────────────────

    /**
     * 加载指定日期的有效营业时间段列表，优先级：Redis 缓存 → 节假日配置 → 周排班。
     *
     * <p>返回值语义：
     * <ul>
     *   <li>{@code null}              — 排班表无配置（视为全天开放，降级处理）</li>
     *   <li>{@code Collections.emptyList()} — 该日为休息日或节假日关闭（全天不开放）</li>
     *   <li>非空列表               — 当天各营业时段（HH:mm 格式，左闭右开区间）</li>
     * </ul>
     *
     * <p>Redis 缓存 TTL 为当天剩余秒数（到次日零点失效），命中率高；
     * 节假日配置优先于周排班，支持临时调班（WORKDAY / CUSTOM / CLOSED）。
     *
     * @param date 目标日期（Asia/Shanghai 时区）
     * @return 时间段列表，{@code null} 表示无排班数据
     */
    private List<BusinessHoursScheduleEntity.TimeRange> loadTodayRanges(LocalDate date) {
        String cacheKey = CACHE_KEY_PREFIX + date;

        // 1. Redis 缓存命中
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if ("null".equals(cached)) return null;
                if ("[]".equals(cached))   return Collections.emptyList();
                return objectMapper.readValue(
                        cached, new TypeReference<List<BusinessHoursScheduleEntity.TimeRange>>() {});
            }
        } catch (Exception e) {
            log.warn("[BusinessHours] Redis read error for key {}: {}", cacheKey, e.getMessage());
        }

        // 2. 节假日覆盖
        List<BusinessHoursScheduleEntity.TimeRange> result;
        BusinessHoursHolidayEntity holiday = holidayMapper.selectByDate(date);
        if (holiday != null) {
            result = switch (holiday.getType()) {
                case "CLOSED"            -> Collections.emptyList();
                case "WORKDAY", "CUSTOM" -> holiday.getTimeRanges();
                default                  -> null;
            };
        } else {
            // 3. 周排班
            int dow = date.getDayOfWeek().getValue(); // 1=周一 … 7=周日
            BusinessHoursScheduleEntity schedule = scheduleMapper.selectByDayOfWeek(dow);
            if (schedule == null) {
                result = null;
            } else if (!Boolean.TRUE.equals(schedule.getIsOpen())) {
                result = Collections.emptyList();
            } else {
                result = schedule.getTimeRanges();
            }
        }

        // 4. 写入缓存，TTL = 当天剩余秒数
        try {
            long ttlSeconds = ChronoUnit.SECONDS.between(
                    ZonedDateTime.now(ZONE),
                    date.plusDays(1).atStartOfDay(ZONE));
            if (ttlSeconds > 0) {
                String value = (result == null) ? "null" : objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(cacheKey, value, ttlSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("[BusinessHours] Redis write error for key {}: {}", cacheKey, e.getMessage());
        }

        return result;
    }
}
