package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CsatService;
import com.aria.conversation.application.service.SessionOwnershipValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

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

    /** 访客 token 请求头名 */
    private static final String HEADER_VISITOR_TOKEN = "X-Visitor-Token";
    /** 匿名标识请求头名（前端 localStorage 生成的 anonymousId） */
    private static final String HEADER_ANONYMOUS_ID = "X-Anonymous-Id";

    private final CsatService csatService;
    /** 会话归属校验器，防 IDOR——按 csatId/sessionId 操作前校验归属 */
    private final SessionOwnershipValidator sessionOwnershipValidator;

    @PostMapping("/{csatId}/rate")
    public R<Void> rate(@PathVariable Long csatId,
                        @RequestBody @Valid RateRequest req,
                        @RequestHeader(value = HEADER_VISITOR_TOKEN, required = false) String visitorToken,
                        @RequestHeader(value = HEADER_ANONYMOUS_ID, required = false) String anonymousId) {
        // 归属校验：防 IDOR——csatId 自增可枚举，须先反查其 sessionId 再校验归属，
        // 否则攻击者可为他人评价记录提交任意分数/评论并污染 CSAT 统计与下游 webhook。
        if (!isCsatOwner(csatId, visitorToken, anonymousId)) {
            return R.fail(403, "无权访问该评价记录");
        }
        csatService.rate(csatId, req.getScore(), req.getComment());
        return R.ok();
    }

    @PostMapping("/{csatId}/skip")
    public R<Void> skip(@PathVariable Long csatId,
                        @RequestHeader(value = HEADER_VISITOR_TOKEN, required = false) String visitorToken,
                        @RequestHeader(value = HEADER_ANONYMOUS_ID, required = false) String anonymousId) {
        if (!isCsatOwner(csatId, visitorToken, anonymousId)) {
            return R.fail(403, "无权访问该评价记录");
        }
        csatService.skip(csatId);
        return R.ok();
    }

    /**
     * 校验调用者是否为 csatId 对应会话的归属访客：先反查 sessionId，再走归属校验。
     * csatId 不存在时返回 false（不泄露存在性差异）。
     */
    private boolean isCsatOwner(Long csatId, String visitorToken, String anonymousId) {
        Optional<String> sessionId = csatService.findSessionIdByCsatId(csatId);
        return sessionId.isPresent()
                && sessionOwnershipValidator.isOwner(sessionId.get(), visitorToken, anonymousId);
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
    public R<Map<String, Object>> pending(
            @RequestParam String sessionId,
            @RequestHeader(value = HEADER_ANONYMOUS_ID, required = false) String anonymousId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        // 归属校验：刷新恢复场景，用 anonymousId 校验归属，防枚举 sessionId 探测他人评价邀请
        if (!sessionOwnershipValidator.isAnonymousOwner(sessionId, anonymousId)) {
            return R.fail(403, "无权访问该会话");
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
