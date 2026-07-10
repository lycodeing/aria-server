package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 访客 WebSocket 连接注册表，实现 {@link VisitorNotifier} 接口。
 *
 * <p>与 {@link AgentConnectionRegistry} 对称，是 infrastructure 层的访客 Session Repository。
 * 职责：管理访客单连接的注册/注销/推送，同步维护 Redis presence + 启停心跳。
 *
 * <p>将 session 管理从 {@link ChatWebSocketHandler} 中分离，消除
 * ChatWebSocketHandler ↔ WsMessageRouter 的 Spring Bean 循环依赖：
 * WsMessageRouter 注入本类（无循环），ChatWebSocketHandler 也注入本类（无循环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorSessionRegistry implements VisitorNotifier {

    private final ObjectMapper       objectMapper;
    private final WsPresenceRegistry presenceRegistry;
    private final PodIdentity        podIdentity;

    /** sessionId → 活跃 WS 连接（每个访客始终只有一个活跃连接） */
    private final ConcurrentHashMap<String, WebSocketSession> visitorSessions =
            new ConcurrentHashMap<>();

    /** per-session 发送锁：wsSession.getId() → lock，串行化 sendMessage */
    private final ConcurrentHashMap<String, Object> sendLocks =
            new ConcurrentHashMap<>();

    /** per-session 心跳任务：wsSession.getId() → ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats =
            new ConcurrentHashMap<>();

    /** 心跳调度器：内部初始化，不作为 @Bean 注入 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2);

    /**
     * 返回当前 Pod 的唯一标识，用于 CONNECTED 信令中的 podId 字段，便于多 Pod 调试。
     *
     * @return podId 字符串
     */
    public String getPodId() {
        return podIdentity.get();
    }

    /**
     * 访客连接建立：注册 session、写 Redis presence、启动 30s 心跳。
     * 若已存在旧连接（重连场景），关闭旧连接并清理旧 sendLock/heartbeat。
     *
     * @param sessionId 会话 ID
     * @param session   WS 连接
     */
    public void register(String sessionId, WebSocketSession session) {
        WebSocketSession oldSession = visitorSessions.put(sessionId, session);
        closeStaleSession(sessionId, oldSession);
        presenceRegistry.registerVisitor(sessionId, podIdentity.get());
        ScheduledFuture<?> hb = heartbeatScheduler.scheduleAtFixedRate(
                () -> presenceRegistry.refreshVisitor(sessionId),
                30, 30, TimeUnit.SECONDS);
        heartbeats.put(session.getId(), hb);
        log.info("[VisitorRegistry] 注册连接 sessionId={} wsId={}", sessionId, session.getId());
    }

    /**
     * 访客连接断开：原子条件删除，防止重连竞态。
     * 仅当 {@code session} 仍是 {@code sessionId} 的活跃连接时，才清理 presence。
     * 若访客已重连（新 session 已替换旧连接），旧连接关闭不影响新连接的 presence。
     *
     * @param sessionId 会话 ID
     * @param session   断开的 WS 连接
     */
    public void unregister(String sessionId, WebSocketSession session) {
        sendLocks.remove(session.getId());
        ScheduledFuture<?> hb = heartbeats.remove(session.getId());
        if (hb != null) hb.cancel(false);
        boolean removed = visitorSessions.remove(sessionId, session);
        if (removed) {
            presenceRegistry.unregisterVisitor(sessionId);
            log.info("[VisitorRegistry] 注销连接 sessionId={} wsId={}", sessionId, session.getId());
        }
    }

    /**
     * 向访客推送消息（本地 session 直推）。
     * 若访客不在线或 session 已关闭，打印 warn 日志后跳过。
     */
    @Override
    public void notifyVisitor(String sessionId, Object payload) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs == null || !vs.isOpen()) {
            log.warn("[VisitorRegistry] notifyVisitor 跳过：sessionId={} 不在线", sessionId);
            return;
        }
        sendJson(sessionId, vs, payload);
    }

    /**
     * 以正常状态（code=1000 NORMAL）关闭访客端 WS。
     * 用于座席主动结束会话时通知访客端显示"会话已结束"提示。
     */
    @Override
    public void closeVisitorSessionNormal(String sessionId) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs != null && vs.isOpen()) {
            try {
                vs.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] closeNormal IO 异常 sessionId={} msg={}",
                        sessionId, e.getMessage());
            } finally {
                boolean removed = visitorSessions.remove(sessionId, vs);
                if (removed) {
                    presenceRegistry.unregisterVisitor(sessionId);
                    ScheduledFuture<?> hb = heartbeats.remove(vs.getId());
                    if (hb != null) hb.cancel(false);
                    sendLocks.remove(vs.getId());
                }
            }
        }
    }

    /**
     * 优雅关闭：清理本 Pod 所有在线访客的 Redis presence，再关闭心跳调度器。
     * Spring 容器停止时调用，确保 JVM 可以正常退出，并避免旧 Pod presence 残留。
     */
    @PreDestroy
    public void shutdown() {
        // 层1 优雅关闭：主动清理所有访客 presence
        int count = visitorSessions.size();
        visitorSessions.keySet().forEach(presenceRegistry::unregisterVisitor);
        log.info("[VisitorRegistry] 优雅关闭：已清理 {} 个访客 presence", count);
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[VisitorRegistry] 心跳调度器已关闭");
    }

    /**
     * 关闭已被替换的旧连接，清理其 sendLock 和心跳。
     */
    private void closeStaleSession(String sessionId, WebSocketSession stale) {
        if (stale == null) return;
        sendLocks.remove(stale.getId());
        ScheduledFuture<?> hb = heartbeats.remove(stale.getId());
        if (hb != null) hb.cancel(false);
        if (stale.isOpen()) {
            try {
                stale.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] 关闭旧连接失败 sessionId={} wsId={} msg={}",
                        sessionId, stale.getId(), e.getMessage());
            }
        }
    }

    /**
     * 向指定 WS 连接发送 JSON 消息，使用 per-session 锁防止并发帧损坏。
     */
    private void sendJson(String sessionId, WebSocketSession session, Object payload) {
        Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            if (!session.isOpen()) return;
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[VisitorRegistry] payload 序列化失败 sessionId={}", sessionId, e);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] 发送失败 sessionId={} msg={}", sessionId, e.getMessage());
                try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
            }
        }
    }
}
