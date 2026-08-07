package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 访客会话归属校验器（防 IDOR 越权）。
 *
 * <p>访客侧会话接口（历史查询/清除、会话状态、访客 WS 连接）过去只做 sessionId 格式校验，
 * 任意人拿到/枚举 sessionId 即可访问他人会话。本组件在应用层统一收口归属校验，
 * 供 REST Controller 与 WS 握手拦截器复用。
 *
 * <p>校验策略（「绑定校验 + 匿名兼容」）：
 * <ol>
 *   <li><b>已短信认证会话</b>：若 {@code visitor:session:auth:{sessionId}} 存在（该会话绑定了手机号），
 *       则请求必须携带有效访客 token，且该 token 解析出的手机号与会话绑定的手机号一致。</li>
 *   <li><b>匿名会话</b>：会话未绑定手机号时，请求必须携带 {@code X-Anonymous-Id}，
 *       且与该会话在 DB 中记录的 {@code visitorId} 一致。</li>
 *   <li>会话在 DB 中不存在 visitorId 且未绑定手机号（脏数据/已清理）时，保守拒绝。</li>
 * </ol>
 *
 * <p>凭证载体约定：
 * <ul>
 *   <li>REST：访客 token 走请求头 {@code X-Visitor-Token}，匿名标识走 {@code X-Anonymous-Id}。</li>
 *   <li>WS：浏览器 WebSocket 无法自定义 header，token 走 {@code ?token=}、匿名标识走 {@code ?anonymousId=} query 参数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionOwnershipValidator {

    /** anonymousId 格式，与 VisitorSessionService.ANONYMOUS_ID_PATTERN 保持一致 */
    private static final Pattern ANONYMOUS_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{8,64}$");

    private final VisitorAuthService visitorAuthService;
    private final ConversationPersistRepository conversationPersistRepository;

    /**
     * 校验调用者是否有权访问指定会话。
     *
     * @param sessionId   目标会话 ID（调用方已完成格式校验）
     * @param visitorToken 访客 token（X-Visitor-Token / ?token=），可为 null
     * @param anonymousId  匿名标识（X-Anonymous-Id / ?anonymousId=），可为 null
     * @return true 表示归属校验通过
     */
    public boolean isOwner(String sessionId, String visitorToken, String anonymousId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        // 分支 1：会话已绑定手机号 → 强制 token 匹配
        Optional<String> boundPhone = visitorAuthService.resolveSessionPhone(sessionId);
        if (boundPhone.isPresent()) {
            String tokenPhone = visitorAuthService.resolvePhone(visitorToken);
            boolean ok = tokenPhone != null && tokenPhone.equals(boundPhone.get());
            if (!ok) {
                log.warn("[Ownership] 已认证会话 token 不匹配，拒绝访问 sessionId={}", sessionId);
            }
            return ok;
        }

        // 分支 2：匿名会话 → X-Anonymous-Id 与 DB visitorId 匹配
        if (anonymousId == null || !ANONYMOUS_ID_PATTERN.matcher(anonymousId).matches()) {
            log.warn("[Ownership] 匿名会话缺少合法 anonymousId，拒绝访问 sessionId={}", sessionId);
            return false;
        }
        Optional<String> visitorId = conversationPersistRepository.findVisitorIdBySessionId(sessionId);
        boolean ok = visitorId.isPresent() && visitorId.get().equals(anonymousId);
        if (!ok) {
            log.warn("[Ownership] 匿名标识与会话归属不匹配，拒绝访问 sessionId={}", sessionId);
        }
        return ok;
    }
}
