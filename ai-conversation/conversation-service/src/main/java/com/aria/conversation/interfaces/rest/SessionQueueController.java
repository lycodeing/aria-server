package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.application.service.SessionQueueService.OnlineAgentVO;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.infrastructure.mq.SessionEventSubscriber;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 座席会话队列接口。
 *
 * <pre>
 * GET  /api/v1/sessions/queue              → 获取等待队列列表
 * GET  /api/v1/sessions/active             → 获取进行中的会话列表
 * POST /api/v1/sessions/{id}/accept        → 接入会话（从 token query param 解析座席 ID）
 * POST /api/v1/sessions/{id}/close         → 结束会话
 * POST /api/v1/sessions/{id}/transfer      → 转交会话给指定座席
 * GET  /api/v1/sessions/agents/online      → 获取在线座席列表
 * GET  /api/v1/sessions/events             → SSE 实时事件流
 * </pre>
 */
@Slf4j
@Validated
@SaIgnore
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class SessionQueueController {

    private static final long HEARTBEAT_INTERVAL_SEC = 20L;
    private static final long SSE_TIMEOUT_MS         = 30 * 60 * 1000L;
    /** 匿名座席 ID（token 未传或解析失败时使用） */
    private static final String ANONYMOUS_AGENT      = "anonymous";
    /** 座席 ID 合法字符集（与 sessionId 一致），用于防注入和 JSON 转义风险 */
    private static final java.util.regex.Pattern AGENT_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final SessionQueueService  queueService;
    private final SessionEventSubscriber eventSubscriber;
    /** WS Handler，close 时主动以 NORMAL 状态关闭访客 WS（触发前端 code=1000 流程） */
    private final com.aria.conversation.infrastructure.websocket.ChatWebSocketHandler chatWebSocketHandler;

    /**
     * 全局单任务心跳调度器（单线程即可，心跳任务串行执行）。
     * 替代原来每个 SSE 连接独立 ScheduledFuture 的方案，N 个座席只占 1 个周期任务。
     */
    private final ScheduledExecutorService heartbeatScheduler = new ScheduledThreadPoolExecutor(
            1,
            r -> {
                Thread t = new Thread(r, "sse-global-heartbeat");
                t.setDaemon(true);
                return t;
            }
    );

    /** 当前激活的 Spring Profile 列表，用于区分生产/非生产环境 */
    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /** 启动全局心跳任务：每 20s 向所有活跃 SSE 连接广播一次 comment 心跳 */
    @jakarta.annotation.PostConstruct
    public void startGlobalHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(
                eventSubscriber::broadcastHeartbeat,
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdownScheduler() {
        heartbeatScheduler.shutdown();
    }

    /** 获取等待队列 */
    @GetMapping("/queue")
    public R<List<SessionQueueItem>> getQueue() {
        return R.ok(queueService.getQueue());
    }

    /** 获取进行中的会话（刷新恢复用） */
    @GetMapping("/active")
    public R<List<SessionQueueItem>> getActive() {
        return R.ok(queueService.getActiveSessions());
    }

    /**
     * 座席接入会话。
     * 从请求 Header Authorization 或 query param token 中提取座席 ID。
     *
     * <p>body.agentId 直传模式仅在非生产环境（非 prod profile）下启用，
     * 生产环境必须通过 token 认证，防止任意用户伪装座席接管会话。
     */
    @PostMapping("/{sessionId}/accept")
    public R<SessionQueueItem> accept(
            @PathVariable
            @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
            String sessionId,
            @RequestParam(required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
                    java.util.Map<String, String> body) {
        String agentId = resolveAgentId(token, authorization);
        // body.agentId 直传仅允许非生产环境（开发/测试阶段前端无 token 时使用）
        if (ANONYMOUS_AGENT.equals(agentId) && body != null && isDevMode()) {
            String bodyAgentId = body.get("agentId");
            if (bodyAgentId != null && AGENT_ID_PATTERN.matcher(bodyAgentId).matches()) {
                agentId = bodyAgentId;
            }
        }
        if (ANONYMOUS_AGENT.equals(agentId)) {
            throw new com.aria.common.core.exception.BusinessException(401, "未登录或登录已过期");
        }
        return R.ok(queueService.accept(sessionId, agentId));
    }

    /**
     * 结束会话。
     * <p>除了执行 SessionQueueService.close（清 Redis + 发 SESSION_END + DB 状态转 CLOSED），
     * 还主动以 NORMAL（code=1000）关闭访客端 WebSocket，触发前端显示"会话已结束"提示。
     */
    @PostMapping("/{sessionId}/close")
    public R<Void> close(
            @PathVariable
            @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
            String sessionId) {
        queueService.close(sessionId);
        chatWebSocketHandler.closeVisitorSessionNormal(sessionId);
        return R.ok();
    }

    /**
     * 转交会话给指定座席。
     *
     * @param sessionId 被转交的会话 ID
     * @param req       请求体，含目标座席 ID
     */
    @PostMapping("/{sessionId}/transfer")
    public R<Void> transfer(
            @PathVariable
            @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
            String sessionId,
            @RequestBody @Valid TransferRequest req,
            @RequestParam(required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String fromAgentId = requireAuthenticatedAgent(token, authorization);
        if (!AGENT_ID_PATTERN.matcher(req.getTargetAgentId()).matches()) {
            throw new com.aria.common.core.exception.BusinessException(
                    400, "targetAgentId 格式非法");
        }
        queueService.transfer(sessionId, fromAgentId, req.getTargetAgentId());
        return R.ok();
    }

    /** 获取在线座席列表（用于转交 Modal 填充） */
    @GetMapping("/agents/online")
    public R<List<OnlineAgentVO>> getOnlineAgents() {
        return R.ok(queueService.getOnlineAgents());
    }

    /**
     * SSE 事件流：座席长连接订阅会话队列变更事件。
     * 连接建立时注册座席在线状态，断开时注销。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestParam(required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        String agentId = resolveAgentId(token, authorization);
        // Phase-1：agentId 取自 token 原始值，Phase-2 接入 Sa-Token 后可获取用户名
        String displayName = agentId.equals(ANONYMOUS_AGENT) ? "未知座席" : "座席-" + agentId.substring(0, Math.min(6, agentId.length()));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        eventSubscriber.register(emitter);

        // 注册座席上线
        if (!ANONYMOUS_AGENT.equals(agentId)) {
            queueService.registerAgent(agentId, displayName);
        }

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            eventSubscriber.remove(emitter);
            if (!ANONYMOUS_AGENT.equals(agentId)) queueService.deregisterAgent(agentId);
            emitter.completeWithError(e);
            return emitter;
        }

        // 心跳由全局单任务（startGlobalHeartbeat）统一广播，此处无需为每个连接独立调度
        final String finalAgentId = agentId;
        Runnable cleanup = () -> {
            eventSubscriber.remove(emitter);
            if (!ANONYMOUS_AGENT.equals(finalAgentId)) {
                queueService.deregisterAgent(finalAgentId);
            }
            log.debug("[SSE] 座席断连 agentId={}", finalAgentId);
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    // ---- 工具方法 ----

    /**
     * 从 token query param 或 Authorization header 中解析座席 ID。
     * Phase-1：直接使用 token 值作为 agentId；
     * Phase-2（TODO）：接入 Sa-Token，通过 StpUtil.getLoginIdByToken(token) 获取真实 userId。
     * SSE 等无 token 的访问返回 {@link #ANONYMOUS_AGENT}。
     */
    private String resolveAgentId(String token, String authorization) {
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String bearer = authorization.substring(7).trim();
            if (!bearer.isBlank()) return bearer;
        }
        return ANONYMOUS_AGENT;
    }

    /**
     * 判断当前是否为非生产环境（开发/测试）。
     * 生产环境禁止 body.agentId 直传绕过 token 认证。
     */
    private boolean isDevMode() {
        return !activeProfile.contains("prod") && !activeProfile.contains("production");
    }

    /**
     * 要求请求必须携带合法 token，对需要写操作的接口（accept/transfer）使用。
     * <ul>
     *   <li>无 token → 401</li>
     *   <li>token 格式非法 → 400</li>
     *   <li>合法 → 返回 agentId</li>
     * </ul>
     */
    private String requireAuthenticatedAgent(String token, String authorization) {
        String agentId = resolveAgentId(token, authorization);
        if (ANONYMOUS_AGENT.equals(agentId)) {
            throw new com.aria.common.core.exception.BusinessException(
                    401, "未登录或登录已过期");
        }
        if (!AGENT_ID_PATTERN.matcher(agentId).matches()) {
            throw new com.aria.common.core.exception.BusinessException(
                    400, "agentId 格式非法");
        }
        return agentId;
    }

    // ---- 请求体 DTO ----

    @Data
    public static class TransferRequest {
        /** 目标座席 ID */
        @NotBlank(message = "目标座席 ID 不能为空")
        private String targetAgentId;
    }
}
