package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaIgnore;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.BusinessHoursService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务时间状态查询接口（访客端 / 坐席端展示用）。
 *
 * <pre>
 * GET /api/v1/business-hours/status → 查询当前是否在服务时间内
 * </pre>
 *
 * <p>该接口使用 {@code @SaIgnore} 跳过 Sa-Token 拦截，允许未登录访客查询。
 */
@RestController
@RequestMapping("/api/v1/business-hours")
@RequiredArgsConstructor
public class BusinessHoursStatusController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BusinessHoursService businessHoursService;

    /**
     * 查询当前是否在服务时间内。
     *
     * <p>返回：
     * <ul>
     *   <li>{@code open} — true 表示当前在服务时间内</li>
     *   <li>{@code nextOpenTime} — 下一个开放时间，格式 "yyyy-MM-dd HH:mm"；
     *       当前已开放时为空字符串</li>
     * </ul>
     */
    @SaIgnore
    @GetMapping("/status")
    public R<Map<String, Object>> getStatus() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        boolean open = businessHoursService.isOpen(now);
        String nextOpenTime = open ? "" : businessHoursService.nextOpenTime(now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("open", open);
        data.put("nextOpenTime", nextOpenTime != null ? nextOpenTime : "");
        return R.ok(data);
    }
}
