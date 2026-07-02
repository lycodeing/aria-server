package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.application.service.SessionQueueService.OnlineAgentVO;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.infrastructure.mq.SessionEventSubscriber;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

/**
 * 座席会话队列接口。
 *
 * <pre>
 * GET  /api/v1/sessions/queue              → 获取等待队列列表
 * GET  /api/v1/sessions/active             → 获取进行中的会话列表
 * POST /api/v1/sessions/{id}/accept        → 接入会话（Sa-Token 鉴权，从 token 解析座席 ID）
 * POST /api/v1/sessions/{id}/close         → 结束会话
 * POST /api/v1/sessions/{id}/transfer      → 转交会话给指定座席
 * GET  /api/v1/sessions/agents/online      → 获取在线座席列表
 * GET  /api/v1/sessions/events             → SSE 实时事件流（@SaIgnore，token 经 query param 传入）
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class SessionQueueController {

    private static final long HEARTBEAT_INTERVAL_SEC = 20L;
    private static final long SSE_TIMEOUT_MS         = 30 * 60 * 1000L;
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
     * Sa-Token 拦截器已完成登录校验，直接从当前会话获取座席 ID。
     */
    @PostMapping("/{sessionId}/accept")
    public R<SessionQueueItem> accept(
            @PathVariable
            @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
            String sessionId) {
        String agentId = StpUtil.getLoginIdAsString();
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
     * Sa-Token 拦截器已完成登录校验，直接从当前会话获取操作座席 ID。
     *
     * @param sessionId 被转交的会话 ID
     * @param req       请求体，含目标座席 ID
     */
    @PostMapping("/{sessionId}/transfer")
    public R<Void> transfer(
            @PathVariable
            @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
            String sessionId,
            @RequestBody @Valid TransferRequest req) {
        String fromAgentId = StpUtil.getLoginIdAsString();
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
     *
     * <p>浏览器 EventSource 无法携带 Authorization Header，token 通过 query param 传入，
     * 此处用 {@code @SaIgnore} 跳过拦截器，手动验证 token 合法性并获取座席 ID。
     * token 无效或未传时返回 401，拒绝匿名订阅（防止未授权客户端接收会话数据）。
     */
    @SaIgnore
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam(required = false) String token) {
        String agentId = resolveAgentIdFromToken(token);

        // token 无效或未传：拒绝连接，返回 401
        if (agentId == null) {
            SseEmitter rejected = new SseEmitter();
            rejected.completeWithError(
                    new com.aria.common.core.exception.BusinessException(401, "未登录或登录已过期"));
            return rejected;
        }

        String displayName = buildDisplayName(agentId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        eventSubscriber.register(emitter);
        queueService.registerAgent(agentId, displayName);

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            eventSubscriber.remove(emitter);
            queueService.deregisterAgent(agentId);
            emitter.completeWithError(e);
            return emitter;
        }

        final String finalAgentId = agentId;
        Runnable cleanup = () -> {
            eventSubscriber.remove(emitter);
            queueService.deregisterAgent(finalAgentId);
            log.debug("[SSE] 座席断连 agentId={}", finalAgentId);
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    // ---- 工具方法 ----

    /**
     * 从 query param token 中用 Sa-Token 解析座席用户 ID。
     * token 无效或未传时返回 null（SSE 连接允许匿名监听，但不注册在线状态）。
     */
    private String resolveAgentIdFromToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            return loginId != null ? loginId.toString() : null;
        } catch (Exception e) {
            log.debug("[SSE] token 解析失败，作为匿名连接处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建座席显示名称，取 agentId 前 6 位作为标识。
     */
    private String buildDisplayName(String agentId) {
        return "座席-" + agentId.substring(0, Math.min(6, agentId.length()));
    }

    // ---- 请求体 DTO ----

    @Data
    public static class TransferRequest {
        /** 目标座席 ID */
        @NotBlank(message = "目标座席 ID 不能为空")
        private String targetAgentId;
    }
}
