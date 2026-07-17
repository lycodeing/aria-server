package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.dto.InitSessionRequest;
import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.application.service.VisitorSessionService;
import com.aria.conversation.interfaces.rest.vo.InitSessionVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 访客会话管理接口。
 *
 * <p>提供 chat-widget 打开时的会话初始化入口，是唯一的会话创建/恢复入口。
 * 允许跨域访问，因为 chat-widget 会嵌入第三方页面。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat/session")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatSessionController {

    /** X-Anonymous-Id Header 名称，前端 localStorage 生成的持久 UUID */
    private static final String HEADER_ANONYMOUS_ID = "X-Anonymous-Id";

    private final VisitorSessionService visitorSessionService;

    /**
     * 初始化访客会话：有活跃会话就恢复，没有就新建。
     *
     * <p>chat-widget 展开时调用，返回 sessionId 供后续所有接口使用。
     * 前端须在 localStorage 持久化 anonymousId，每次请求通过 {@code X-Anonymous-Id} Header 传入。
     *
     * @param anonymousId   X-Anonymous-Id Header（必传，格式 {@code ^[a-zA-Z0-9_\-]{8,64}$}）
     * @param request       HTTP 请求（用于提取 IP 和 User-Agent）
     * @param body          请求体（可选 visitorName）
     * @return 会话初始化结果（sessionId、status、isNew）
     */
    @PostMapping("/init")
    public R<InitSessionVO> init(
            @RequestHeader(HEADER_ANONYMOUS_ID) String anonymousId,
            HttpServletRequest request,
            @RequestBody(required = false) InitSessionRequest body) {

        String visitorIp     = resolveClientIp(request);
        String visitorDevice = request.getHeader("User-Agent");
        String visitorName   = body != null ? body.getVisitorName() : null;

        InitSessionResult result = visitorSessionService.getOrCreate(
                anonymousId, visitorName, visitorIp, visitorDevice);

        return R.ok(new InitSessionVO(result.sessionId(), result.status().name(), result.isNew()));
    }

    /**
     * 提取客户端真实 IP：优先取 X-Forwarded-For 首个地址，未经代理时取 RemoteAddr。
     *
     * @param request HTTP 请求
     * @return 客户端 IP 字符串
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
