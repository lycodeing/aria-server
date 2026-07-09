package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 座席 WebSocket 连接注册表。
 *
 * <p>职责：管理座席多端连接的注册/注销/推送，同步维护 Redis presence + 启停心跳。
 * 不感知业务逻辑（KICK/BROADCAST 由调用方决策）。
 *
 * <p>数据结构：
 * <ul>
 *   <li>{@code agentToSessions}：正向索引，agentId → 所有在线 WS 连接集合</li>
 *   <li>{@code sessionIdToAgentId}：反向索引，wsSession.getId() → agentId，供 unregister O(1) 清理</li>
 *   <li>{@code sendLocks}：per-session 发送锁，串行化 sendMessage 调用，防止并发帧损坏</li>
 *   <li>{@code agentLocks}：per-agentId 粗粒度锁，供 KICK 模式原子化 register+kick+close</li>
 *   <li>{@code heartbeats}：per-session 心跳任务，每 30s 刷新 Redis presence TTL</li>
 * </ul>
 *
 * <p>⚠️ {@code agentToSessions} value 类型必须保持 {@link CopyOnWriteArraySet}（有序插入），
 * {@code broadcast} 遍历时按插入顺序加锁，替换为无序集合会引入死锁风险。
 */
@Slf4j
@Component
public class AgentConnectionRegistry {

    private final ObjectMapper objectMapper;
    private final WsPresenceRegistry presenceRegistry;
    private final PodIdentity podIdentity;

    /** 心跳调度器：内部初始化，不作为 @Bean 注入 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2);

    /** per-session 心跳任务：wsSession.getId() → ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats =
            new ConcurrentHashMap<>();

    /**
     * 正向索引：agentId → 该座席所有在线 WS 连接
     */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSession>> agentToSessions = new ConcurrentHashMap<>();

    /**
     * 反向索引：wsSession.getId() → agentId
     */
    private final ConcurrentHashMap<String, String> sessionIdToAgentId = new ConcurrentHashMap<>();

    /**
     * per-session 发送锁：wsSession.getId() → lock
     */
    private final ConcurrentHashMap<String, Object> sendLocks = new ConcurrentHashMap<>();

    /**
     * per-agentId 粗粒度锁，供 KICK 模式三步原子化使用
     */
    private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

    /**
     * Spring 主构造器：注入 presence 组件，由容器自动装配。
     */
    @Autowired
    public AgentConnectionRegistry(ObjectMapper objectMapper,
                                   WsPresenceRegistry presenceRegistry,
                                   PodIdentity podIdentity) {
        this.objectMapper = objectMapper;
        this.presenceRegistry = presenceRegistry;
        this.podIdentity = podIdentity;
    }

    /**
     * 仅供单元测试使用（无 presence 注入）。
     * presence 相关调用在 presenceRegistry 为 null 时自动跳过。
     */
    AgentConnectionRegistry(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    /**
     * 注册新连接。同时向 Redis presence 注册本 Pod，并启动 30s 心跳刷新 TTL。
     *
     * @param agentId 座席 ID（由 AgentHandshakeInterceptor 写入 session attributes）
     * @param session WS 连接
     */
    public void register(String agentId, WebSocketSession session) {
        agentToSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionIdToAgentId.put(session.getId(), agentId);
        // 注册 presence 并启动心跳（内部处理，KICK 模式下无需外部再次调用）
        if (presenceRegistry != null) {
            presenceRegistry.registerAgent(agentId, podIdentity.get());
            ScheduledFuture<?> hb = heartbeatScheduler.scheduleAtFixedRate(
                    () -> presenceRegistry.refreshAgent(agentId),
                    30, 30, TimeUnit.SECONDS);
            heartbeats.put(session.getId(), hb);
        }
        log.debug("[AgentRegistry] 注册连接 agentId={} wsId={}", agentId, session.getId());
    }

    /**
     * 注销连接。通过反向索引查找 agentId，调用方无需传入。
     * 取消心跳；若本 Pod 上该座席已无连接，同步从 Redis presence Set 移除本 Pod。
     * 连接关闭（afterConnectionClosed）和 transport error 时均调用此方法。
     *
     * @param session 待注销的 WS 连接
     */
    public void unregister(WebSocketSession session) {
        String agentId = sessionIdToAgentId.remove(session.getId());
        sendLocks.remove(session.getId());
        // 取消心跳
        ScheduledFuture<?> hb = heartbeats.remove(session.getId());
        if (hb != null) hb.cancel(false);
        if (agentId == null) {
            return;
        }
        // 原子删除：空 Set 时同步移除 key，避免 TOCTOU 竞态；同步清理 agentLocks 防内存泄漏
        boolean[] isEmpty = {false};
        agentToSessions.computeIfPresent(agentId, (k, set) -> {
            set.remove(session);
            if (set.isEmpty()) {
                agentLocks.remove(k);  // 无在线连接时释放 per-agentId 锁对象，防止长期泄漏
                isEmpty[0] = true;
                return null;
            }
            return set;
        });
        // 本 Pod 上该 agentId 已无连接，从 Redis presence Set 移除本 Pod
        if (isEmpty[0] && presenceRegistry != null) {
            presenceRegistry.unregisterAgent(agentId, podIdentity.get());
        }
        log.debug("[AgentRegistry] 注销连接 agentId={} wsId={}", agentId, session.getId());
    }

    /**
     * 关闭该座席所有在线连接（发送 {@link CloseStatus#GOING_AWAY}）。
     *
     * <p>Spring 会在连接关闭后自动调用 {@code afterConnectionClosed} → {@link #unregister}，
     * 无需在此手动清理索引。
     *
     * @param agentId 座席 ID
     */
    public void closeAll(String agentId) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.close(CloseStatus.GOING_AWAY);
                } catch (IOException e) {
                    log.warn("[AgentRegistry] closeAll 关闭连接失败 wsId={} msg={}", session.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * 向该座席所有在线连接广播消息。
     *
     * @param agentId 座席 ID
     * @param payload 消息对象（序列化为 JSON）
     */
    public void broadcast(String agentId, Object payload) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("[AgentRegistry] broadcast 跳过：agentId={} 无在线连接", agentId);
            return;
        }
        for (WebSocketSession session : sessions) {
            sendJson(session, payload);
        }
    }

    /**
     * 向该座席除 exclude 之外的所有连接广播消息。
     * 用于 KICK 模式向旧端推送 KICKED_OUT 信令。
     *
     * @param agentId 座席 ID
     * @param exclude 排除的连接（通常为新登录的连接）
     * @param payload 消息对象
     */
    public void broadcastExcept(String agentId, WebSocketSession exclude, Object payload) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(exclude.getId())) {
                sendJson(session, payload);
            }
        }
    }

    /**
     * 关闭该座席除 keep 之外的所有连接。
     * 用于 KICK 模式踢出旧连接，调用前应先 broadcastExcept 推送 KICKED_OUT。
     *
     * @param agentId 座席 ID
     * @param keep    保留的连接（新登录连接）
     */
    public void closeAllExcept(String agentId, WebSocketSession keep) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(keep.getId()) && session.isOpen()) {
                try {
                    session.close(CloseStatus.GOING_AWAY);
                } catch (IOException e) {
                    log.warn("[AgentRegistry] 关闭旧连接失败 wsId={} msg={}", session.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * 获取指定座席的 per-agentId 粗粒度锁。
     * KICK 模式下用于将 register+broadcastExcept+closeAllExcept 三步原子化。
     *
     * @param agentId 座席 ID
     * @return 锁对象（同一 agentId 始终返回同一实例）
     */
    public Object getAgentLock(String agentId) {
        return agentLocks.computeIfAbsent(agentId, k -> new Object());
    }

    /**
     * 向指定连接发送单条消息（复用 per-session 发送锁，保证线程安全）。
     * 用于向新建立的连接推送初始信令（如 CONNECTED），确保与并发广播不产生帧竞争。
     *
     * @param session 目标 WS 连接
     * @param payload 消息对象（序列化为 JSON）
     */
    public void sendToSession(WebSocketSession session, Object payload) {
        sendJson(session, payload);
    }

    /**
     * 向指定 WS 连接发送 JSON 消息。
     * 通过 per-session 锁串行化写帧，防止并发帧损坏。
     * 发送失败时主动关闭连接，触发 afterConnectionClosed 自动清理。
     */
    private void sendJson(WebSocketSession session, Object payload) {
        Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            if (!session.isOpen()) {
                return;
            }
            try {
                String json = objectMapper.writeValueAsString(payload);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("[AgentRegistry] 发送失败 wsId={} msg={}", session.getId(), e.getMessage());
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
