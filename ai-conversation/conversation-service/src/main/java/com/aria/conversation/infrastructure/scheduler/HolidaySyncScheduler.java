package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.application.service.BusinessHoursService;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/**
 * 中国法定节假日自动同步调度器。
 * 数据来源：NateScarlet/holiday-cn，通过 jsDelivr CDN 拉取。
 * 每年 12 月 1 日 00:00 自动同步次年数据；管理员也可通过接口手动触发。
 *
 * <p>幂等性：写入时通过 holiday_date 唯一索引做 ON CONFLICT DO NOTHING，
 * 重复同步安全无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HolidaySyncScheduler {

    private static final String CDN_URL_TEMPLATE =
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;
    private static final int MAX_RETRY_TIMES    = 3;

    private final BusinessHoursHolidayMapper  holidayMapper;
    private final BusinessHoursScheduleMapper scheduleMapper;
    private final BusinessHoursService        businessHoursService;
    private final ObjectMapper                objectMapper;

    /** 每年 12 月 1 日 00:00 自动同步次年节假日。 */
    @Scheduled(cron = "0 0 0 1 12 *")
    public void syncNextYear() {
        int nextYear = Year.now().getValue() + 1;
        log.info("[HolidaySync] 开始自动同步 {} 年节假日", nextYear);
        try {
            int count = syncYear(nextYear);
            log.info("[HolidaySync] {} 年节假日同步完成，写入 {} 条", nextYear, count);
        } catch (Exception e) {
            log.error("[HolidaySync] {} 年节假日同步失败，请手动触发重试", nextYear, e);
        }
    }

    /**
     * 同步指定年份的节假日数据（幂等）。
     *
     * @param year 目标年份
     * @return 本次实际写入的条数（已存在的记录跳过不计）
     */
    public int syncYear(int year) {
        String json = fetchWithRetry(year);
        List<HolidayEntry> entries = parseEntries(json);

        // 取周一排班作为 WORKDAY 调休补班的默认时间段
        BusinessHoursScheduleEntity mondaySchedule = scheduleMapper.selectByDayOfWeek(1);
        List<BusinessHoursScheduleEntity.TimeRange> defaultRanges =
                mondaySchedule != null ? mondaySchedule.getTimeRanges() : List.of();

        int count = 0;
        for (HolidayEntry entry : entries) {
            // 幂等：日期已存在则跳过（DB 有唯一约束 uq_holiday_date）
            boolean exists = holidayMapper.exists(Wrappers.<BusinessHoursHolidayEntity>lambdaQuery()
                    .eq(BusinessHoursHolidayEntity::getDate, entry.date()));
            if (exists) {
                log.debug("[HolidaySync] {} 已存在，跳过", entry.date());
                continue;
            }

            String type       = entry.isOffDay() ? "CLOSED" : "WORKDAY";
            // CLOSED 节假日 timeRanges 为 null（不开放服务）
            // WORKDAY 调休补班复用周一排班时间段
            List<BusinessHoursScheduleEntity.TimeRange> timeRanges =
                    entry.isOffDay() ? null : defaultRanges;

            holidayMapper.insert(BusinessHoursHolidayEntity.builder()
                    .date(entry.date())
                    .type(type)
                    .timeRanges(timeRanges)
                    .remark(entry.name())
                    .source("AUTO")
                    .build());

            businessHoursService.evictCache(entry.date());
            count++;
        }
        log.info("[HolidaySync] 年份 {} 处理 {} 条，新写入 {} 条", year, entries.size(), count);
        return count;
    }

    // ── 包保护方法（protected 以便测试通过 spy 覆盖，避免真实 HTTP 调用） ──────────

    /**
     * 带指数退避重试的 HTTP 获取，退避间隔：1s / 3s / 9s。
     */
    protected String fetchWithRetry(int year) {
        String url = String.format(CDN_URL_TEMPLATE, year);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .GET()
                .build();

        Exception lastEx = null;
        long delaySec = 1;
        for (int attempt = 1; attempt <= MAX_RETRY_TIMES; attempt++) {
            try {
                HttpResponse<String> resp =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode());
                }
                return resp.body();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("holiday-cn 数据拉取被中断", ie);
            } catch (Exception e) {
                lastEx = e;
                log.warn("[HolidaySync] 第 {}/{} 次请求失败: {}", attempt, MAX_RETRY_TIMES,
                        e.getMessage());
                if (attempt < MAX_RETRY_TIMES) {
                    try {
                        Thread.sleep(delaySec * 1_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("holiday-cn 重试等待被中断", ie);
                    }
                    delaySec *= 3; // 指数退避：1s → 3s → 9s
                }
            }
        }
        throw new RuntimeException(
                "holiday-cn 数据拉取失败，已重试 " + MAX_RETRY_TIMES + " 次", lastEx);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private List<HolidayEntry> parseEntries(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return objectMapper.readerForListOf(HolidayEntry.class)
                    .readValue(root.get("days"));
        } catch (IOException e) {
            throw new RuntimeException("holiday-cn JSON 解析失败", e);
        }
    }

    /**
     * holiday-cn JSON 中单个日期条目。
     *
     * <p>{@code dateStr} 对应 JSON 中的 {@code "date"} 字段（如 "2026-01-01"）。
     * 因 Java record 组件 {@code date} 会自动生成 {@code String date()} 访问器，
     * 与本类提供的 {@code LocalDate date()} 方法冲突，故使用别名 {@code dateStr}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HolidayEntry(
            String name,
            @JsonProperty("date") String dateStr,
            boolean isOffDay
    ) {
        LocalDate date() {
            return LocalDate.parse(dateStr);
        }
    }
}
