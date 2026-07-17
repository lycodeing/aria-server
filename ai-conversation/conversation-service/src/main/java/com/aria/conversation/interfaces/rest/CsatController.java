package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CsatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 访客侧 CSAT 接口（不需要坐席鉴权，仅需访客 Token）。
 *
 * <p>POST /api/v1/chat/csat/{csatId}/rate  提交评分（1–5星 + 可选文字）
 * <p>POST /api/v1/chat/csat/{csatId}/skip  跳过评价
 * <p>GET  /api/v1/chat/csat/pending        按 sessionId 恢复待评价（刷新场景）
 *
 * <p>CORS 策略：rate/skip 通过网关鉴权后转发，不额外放行任意源；
 * 仅 pending 属于访客公开接口，方法级 {@code @CrossOrigin(origins="*")}。
 */
@RestController
@RequestMapping("/api/v1/chat/csat")
@RequiredArgsConstructor
public class CsatController {

    /** sessionId 格式校验：与 ChatController 保持一致，防止 Redis/DB key 注入。 */
    private static final java.util.regex.Pattern SESSION_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final CsatService csatService;

    @PostMapping("/{csatId}/rate")
    public R<Void> rate(@PathVariable Long csatId,
                        @RequestBody @Valid RateRequest req) {
        csatService.rate(csatId, req.getScore(), req.getComment());
        return R.ok();
    }

    @PostMapping("/{csatId}/skip")
    public R<Void> skip(@PathVariable Long csatId) {
        csatService.skip(csatId);
        return R.ok();
    }

    /**
     * 查询指定 session 是否存在待评价的 CSAT 邀请。
     *
     * <p>用于访客刷新页面后恢复评价弹窗：命中返回与 SSE {@code csat_request}
     * 事件一致的字段（csatId/sessionId/message/expiresAt），前端可直接复用
     * {@code CsatRequestPayload} 类型；未命中返回 {@code data: null}。
     *
     * <p>此接口的 payload 与 SSE 流末尾追加、人工会话关闭时下发的
     * {@code csat_request} 事件共用同一份构造逻辑
     * （{@link com.aria.conversation.application.service.support.CsatInvites}），
     * 保证刷新前后弹窗文案与时间格式完全一致。
     */
    @CrossOrigin(origins = "*")
    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        return R.ok(csatService.findPending(sessionId)
                .map(com.aria.conversation.application.service.support.CsatInvites::payload)
                .orElse(null));
    }

    @Data
    public static class RateRequest {
        @NotNull @Min(1) @Max(5)
        private Short score;
        private String comment;
    }
}
