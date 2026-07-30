package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.application.service.BusinessHoursService;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.domain.HolidaySource;
import com.aria.conversation.domain.HolidayType;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /** 每 3 个月 1 日 00:00 自动同步未来 3 个月节假日。 */
    @Scheduled(cron = "0 0 0 1 */3 *")
    public void syncUpcomingHolidays() {
        // 传入当前年份：syncYear 的过滤窗口为「今天起 3 个月」，起始年份必须与窗口对齐；
        // 窗口跨年时由 syncYear 内部的 cutoff.getYear() > year 分支自动追加次年数据。
        int startYear = Year.now().getValue();
        log.info("[HolidaySync] 开始自动同步未来 3 个月节假日，起始年份 {}", startYear);
        try {
            int count = syncYear(startYear);
            log.info("[HolidaySync] 节假日同步完成，写入 {} 条", count);
        } catch (Exception e) {
            log.error("[HolidaySync] 节假日同步失败，请手动触发重试", e);
        }
    }

    /**
     * 同步指定年份起未来 3 个月的节假日数据（批量 upsert）。
     * 窗口可跨年，跨年时自动追加次年数据一并处理。
     * AUTO 来源的已有记录会被更新；MANUAL 手动录入的记录不受影响。
     *
     * @param year 起始年份
     * @return 本次实际影响的条数（含新增和更新）
     */
    public int syncYear(int year) {
        // 同步窗口：今天起未来 3 个月，可跨年
        LocalDate today  = LocalDate.now();
        LocalDate cutoff = today.plusMonths(3);

        // 收集窗口内 entries；跨年时合并两年数据
        List<HolidayEntry> entries = new ArrayList<>(parseEntries(fetchWithRetry(year)));
        if (cutoff.getYear() > year) {
            entries.addAll(parseEntries(fetchWithRetry(cutoff.getYear())));
        }

        // 过滤：仅保留 [today, cutoff] 窗口内的条目
        List<HolidayEntry> inWindow = entries.stream()
                .filter(e -> !e.date().isBefore(today) && !e.date().isAfter(cutoff))
                .toList();

        if (inWindow.isEmpty()) {
            log.info("[HolidaySync] 年份 {} 窗口 [{}, {}] 内无数据", year, today, cutoff);
            return 0;
        }

        // 取周一排班作为 WORKDAY 调休补班的默认时间段
        BusinessHoursScheduleEntity mondaySchedule = scheduleMapper.selectByDayOfWeek(1);
        List<BusinessHoursScheduleEntity.TimeRange> defaultRanges =
                mondaySchedule != null ? mondaySchedule.getTimeRanges() : List.of();

        // 构建实体列表，全量交给 upsert 处理（已有 AUTO 记录更新，MANUAL 记录跳过）
        List<BusinessHoursHolidayEntity> toUpsert = inWindow.stream()
                .map(e -> BusinessHoursHolidayEntity.builder()
                        .date(e.date())
                        .type(e.isOffDay() ? HolidayType.CLOSED : HolidayType.WORKDAY)
                        .timeRanges(e.isOffDay() ? null : defaultRanges)
                        .remark(e.name())
                        .source(HolidaySource.AUTO)
                        .build())
                .collect(java.util.stream.Collectors.toList());

        // 批量 upsert，失效所有涉及日期的缓存
        int count = holidayMapper.insertBatch(toUpsert);
        toUpsert.forEach(e -> businessHoursService.evictCache(e.getDate()));
        log.info("[HolidaySync] 年份 {} 窗口 [{}, {}] 处理 {} 条，影响 {} 条",
                year, today, cutoff, inWindow.size(), count);
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
            @JsonProperty("isOffDay") boolean isOffDay
    ) {
        LocalDate date() {
            return LocalDate.parse(dateStr);
        }
    }
}
