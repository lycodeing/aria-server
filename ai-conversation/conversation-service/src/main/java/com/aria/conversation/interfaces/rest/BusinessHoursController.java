package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.BusinessHoursService;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursHolidayMapper;
import com.aria.conversation.infrastructure.persistence.mapper.BusinessHoursScheduleMapper;
import com.aria.conversation.infrastructure.scheduler.HolidaySyncScheduler;
import com.aria.sdk.auth.AuthClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/**
 * 业务时间管理接口（管理端）。
 *
 * <pre>
 * GET  /api/v1/admin/business-hours/schedule          → 读取 7 天排班配置
 * PUT  /api/v1/admin/business-hours/schedule          → 整体覆盖更新排班
 * GET  /api/v1/admin/business-hours/holidays          → 节假日列表（year 可选过滤）
 * POST /api/v1/admin/business-hours/holidays          → 新增节假日
 * PUT  /api/v1/admin/business-hours/holidays/{id}     → 修改节假日
 * DELETE /api/v1/admin/business-hours/holidays/{id}   → 删除节假日
 * POST /api/v1/admin/business-hours/holidays/sync     → 手动触发 holiday-cn 同步
 * GET  /api/v1/admin/business-hours/offline-reply     → 读取离线回复消息
 * PUT  /api/v1/admin/business-hours/offline-reply     → 更新离线回复消息
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/business-hours")
@RequiredArgsConstructor
public class BusinessHoursController {

    private static final String OFFLINE_REPLY_KEY     = "agent.offlineMessage";
    private static final String OFFLINE_REPLY_DEFAULT = "当前不在服务时间，我们将尽快回复您。";

    private final BusinessHoursScheduleMapper scheduleMapper;
    private final BusinessHoursHolidayMapper  holidayMapper;
    private final BusinessHoursService        businessHoursService;
    private final HolidaySyncScheduler        holidaySyncScheduler;
    private final AuthClient                  authClient;

    // ── 排班接口 ──────────────────────────────────────────────────────────────

    /** 读取 7 天排班配置。 */
    @GetMapping("/schedule")
    @SaCheckPermission("system:biz-hours:manage")
    public R<List<BusinessHoursScheduleEntity>> getSchedule() {
        return R.ok(scheduleMapper.selectList(null));
    }

    /**
     * 整体覆盖更新 7 天排班（只 UPDATE，不 DELETE）。
     * 更新后失效今天及未来 7 天的缓存，确保 isOpen 立即生效。
     */
    @PutMapping("/schedule")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Void> updateSchedule(@RequestBody @Validated List<ScheduleUpdateReq> req) {
        req.forEach(r -> {
            var entity = new BusinessHoursScheduleEntity();
            entity.setDayOfWeek(r.getDayOfWeek());
            entity.setIsOpen(r.getIsOpen());
            entity.setTimeRanges(r.getTimeRanges());
            scheduleMapper.update(entity,
                    Wrappers.<BusinessHoursScheduleEntity>lambdaUpdate()
                            .eq(BusinessHoursScheduleEntity::getDayOfWeek, r.getDayOfWeek()));
        });
        // 排班变更影响未来多日，保守地失效今天及未来 7 天
        for (int i = 0; i <= 7; i++) {
            businessHoursService.evictCache(LocalDate.now().plusDays(i));
        }
        return R.ok();
    }

    // ── 节假日接口 ────────────────────────────────────────────────────────────

    /** 节假日列表（支持 year 过滤）。 */
    @GetMapping("/holidays")
    @SaCheckPermission("system:biz-hours:manage")
    public R<List<BusinessHoursHolidayEntity>> listHolidays(
            @RequestParam(required = false) Integer year) {
        var wrapper = Wrappers.<BusinessHoursHolidayEntity>lambdaQuery();
        if (year != null) {
            wrapper.between(BusinessHoursHolidayEntity::getDate,
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        wrapper.orderByAsc(BusinessHoursHolidayEntity::getDate);
        return R.ok(holidayMapper.selectList(wrapper));
    }

    /** 新增节假日。 */
    @PostMapping("/holidays")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Void> addHoliday(@RequestBody @Validated HolidayReq req) {
        var entity = BusinessHoursHolidayEntity.builder()
                .date(req.getDate())
                .type(req.getType())
                .timeRanges(req.getTimeRanges())
                .remark(req.getRemark())
                .source("MANUAL")
                .build();
        holidayMapper.insert(entity);
        businessHoursService.evictCache(req.getDate());
        return R.ok();
    }

    /** 修改节假日。 */
    @PutMapping("/holidays/{id}")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Void> updateHoliday(@PathVariable Long id,
                                  @RequestBody @Validated HolidayReq req) {
        var entity = BusinessHoursHolidayEntity.builder()
                .id(id)
                .date(req.getDate())
                .type(req.getType())
                .timeRanges(req.getTimeRanges())
                .remark(req.getRemark())
                .build();
        holidayMapper.updateById(entity);
        businessHoursService.evictCache(req.getDate());
        return R.ok();
    }

    /** 删除节假日，同时失效对应日期缓存。 */
    @DeleteMapping("/holidays/{id}")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Void> deleteHoliday(@PathVariable Long id) {
        BusinessHoursHolidayEntity holiday = holidayMapper.selectById(id);
        if (holiday != null) {
            holidayMapper.deleteById(id);
            businessHoursService.evictCache(holiday.getDate());
        }
        return R.ok();
    }

    /**
     * 手动触发 holiday-cn 同步。
     *
     * @param year 目标年份，0 或不传时默认同步次年
     * @return 本次实际写入条数
     */
    @PostMapping("/holidays/sync")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Integer> syncHolidays(
            @RequestParam(defaultValue = "0") int year) {
        int targetYear = year > 0 ? year : Year.now().getValue() + 1;
        int count = holidaySyncScheduler.syncYear(targetYear);
        return R.ok(count);
    }

    // ── 离线回复接口 ──────────────────────────────────────────────────────────

    /**
     * 读取离线回复消息。
     * 从 auth-service system_config 表读取 key=agent.offlineMessage，
     * 读取失败或 key 不存在时返回默认值。
     */
    @GetMapping("/offline-reply")
    @SaCheckPermission("system:biz-hours:manage")
    public R<String> getOfflineReply() {
        try {
            String value = authClient.getSystemConfigValue(OFFLINE_REPLY_KEY);
            return R.ok(value != null ? value : OFFLINE_REPLY_DEFAULT);
        } catch (Exception e) {
            log.warn("[BusinessHours] 读取离线回复配置失败，返回默认值: {}", e.getMessage());
            return R.ok(OFFLINE_REPLY_DEFAULT);
        }
    }

    /**
     * 更新离线回复消息。
     *
     * <p>TODO: AuthClient 目前无系统配置写入接口，该接口暂为 stub。
     * 待 auth-service 暴露 PUT /internal/system-config/value 后补全实现。
     */
    @PutMapping("/offline-reply")
    @SaCheckPermission("system:biz-hours:manage")
    public R<Void> updateOfflineReply(@RequestBody @Validated OfflineReplyReq req) {
        // TODO: 跨服务写入 system_config，待 AuthClient 支持写操作后实现
        log.warn("[BusinessHours] updateOfflineReply stub invoked, message='{}'. " +
                "Pending AuthClient write-support for key={}", req.getMessage(), OFFLINE_REPLY_KEY);
        return R.ok();
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class ScheduleUpdateReq {
        @NotNull
        private Integer dayOfWeek;
        @NotNull
        private Boolean isOpen;
        private List<BusinessHoursScheduleEntity.TimeRange> timeRanges;
    }

    @Data
    public static class HolidayReq {
        @NotNull
        private LocalDate date;
        /** CLOSED | CUSTOM | WORKDAY */
        @NotNull
        private String type;
        private List<BusinessHoursScheduleEntity.TimeRange> timeRanges;
        private String remark;
    }

    @Data
    public static class OfflineReplyReq {
        @NotNull
        private String message;
    }
}
