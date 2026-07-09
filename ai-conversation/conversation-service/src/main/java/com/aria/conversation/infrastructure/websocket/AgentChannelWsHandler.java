package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsClusterConstants;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.aria.conversation.infrastructure.websocket.message.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 座席专用 WebSocket Handler。
 *
 * <p>注册到端点 {@code /ws/agent}，负责：
 * <ul>
 *   <li>连接建立/关闭时维护 {@link AgentConnectionRegistry}</li>
 *   <li>按 {@code agent.ws.multi-login-mode} 执行 BROADCAST 或 KICK 逻辑</li>
 *   <li>处理座席发送的消息（MESSAGE 写历史转发访客，TYPING 直接转发）</li>
 *   <li>向新连接推送 {@link WsConnectedMessage} 确认信令</li>
 * </ul>
 *
 * <p>握手鉴权由 {@link AgentHandshakeInterceptor} 完成，token 无效时返回 HTTP 401，
 * 本 Handler 不会被调用。agentId 由 Interceptor 写入 {@code session.attributes["agentId"]}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChannelWsHandler extends TextWebSocketHandler {

    private static final String ATTR_AGENT_ID = "agentId";

    /** 单条消息最大字节数（64KB），与 ChatWebSocketHandler 保持一致 */
    private static final int MAX_MESSAGE_BYTES = 65536;

    private final AgentConnectionRegistry registry;
    private final VisitorNotifier          visitorNotifier;
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper             objectMapper;
    private final WsPresenceRegistry       presenceRegistry;
    private final PodIdentity              podIdentity;
    private final WsMessageRouter          router;
    private final RedissonClient           redissonClient;

    @Value("${agent.ws.multi-login-mode:BROADCAST}")
    private MultiLoginMode multiLoginMode;

    /**
     * 连接建立后：按 multiLoginMode 执行注册逻辑，KICK 模式使用 Redisson 分布式锁原子化，
     * 并向其他 Pod 发送 KICK 命令，最后推送 CONNECTED 信令。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String agentId = (String) session.getAttributes().get(ATTR_AGENT_ID);
        if (agentId == null) {
            log.warn("[AgentWS] agentId 缺失，关闭连接 wsId={}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (MultiLoginMode.KICK == multiLoginMode) {
            RLock lock = redissonClient.getLock(WsClusterConstants.KICK_LOCK_KEY_PREFIX + agentId);
            boolean acquired;
            try {
                acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[AgentWS] KICK 锁等待被中断 agentId={}", agentId);
                session.close(CloseStatus.SERVICE_OVERLOAD);
                return;
            }
            if (!acquired) {
                log.warn("[AgentWS] KICK 锁获取超时，拒绝连接 agentId={}", agentId);
                session.close(CloseStatus.SERVICE_OVERLOAD);
                return;
            }
            try {
                // registry.register() 内部已调用 presenceRegistry.registerAgent()，无需再次显式调用
                registry.register(agentId, session);
                // 本 Pod：向旧连接推 KICKED_OUT，关闭旧连接
                registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
                registry.closeAllExcept(agentId, session);
                // 跨 Pod：向其他 Pod 发 KICK 命令
                Set<String> allPods = presenceRegistry.getAgentPods(agentId);
                for (String pod : allPods) {
                    if (!podIdentity.isLocal(pod)) {
                        router.sendKick(pod, agentId, session.getId());
                    }
                }
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } else {
            // BROADCAST 模式：直接注册，无锁
            registry.register(agentId, session);
        }

        // 通知新端连接成功，通过 Registry 锁发送，避免与并发广播产生帧竞态
        registry.sendToSession(session, WsConnectedMessage.forAgent());
        log.info("[AgentWS] 座席连接建立 agentId={} wsId={} mode={}", agentId, session.getId(), multiLoginMode);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            log.warn("[AgentWS] 消息超过最大长度 wsId={} size={}", session.getId(), message.getPayloadLength());
            registry.sendToSession(session, WsErrorMessage.of("消息长度超过限制（最大 64KB）"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        WsInboundMessage body = parseBody(message.getPayload(), session.getId());
        String sessionId = body.sessionId();
        long ts = Instant.now().getEpochSecond();

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[AgentWS] 消息缺少 sessionId wsId={}", session.getId());
            return;
        }

        if (body.isType(WsMessageType.TYPING)) {
            // TYPING 信号：ephemeral，不写历史，走路由层转发给访客
            router.sendToVisitor(sessionId, WsTypingMessage.of(sessionId, ts));
            return;
        }

        // MESSAGE：写历史，走路由层转发给访客
        String content = body.content();
        if (content == null || content.isBlank()) {
            log.warn("[AgentWS] MESSAGE 内容为空 sessionId={} wsId={}", sessionId, session.getId());
            return;
        }
        long seq = historyRepository.appendAgentMessage(sessionId, content);
        router.sendToVisitor(sessionId, WsChatMessage.fromAgent(sessionId, content, seq, ts));
        log.debug("[AgentWS] agent→visitor sessionId={} seq={}", sessionId, seq);
    }

    /**
     * 连接正常/异常关闭后：从注册表注销连接，不触发会话关闭逻辑（座席断线 ≠ 会话结束）。
     */
    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        registry.unregister(session);
        log.info("[AgentWS] 座席连接关闭 agentId={} wsId={} status={}",
                session.getAttributes().get(ATTR_AGENT_ID), session.getId(), status);
    }

    /**
     * 传输层异常：记录警告并注销连接，由客户端负责重连。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("[AgentWS] 传输异常 agentId={} wsId={} msg={}",
                session.getAttributes().get(ATTR_AGENT_ID), session.getId(), ex.getMessage());
        registry.unregister(session);
    }

    /**
     * 将 JSON 字符串反序列化为 {@link WsInboundMessage}。
     * 非 JSON 时降级封装为 content=rawPayload 的 MESSAGE 类型。
     *
     * @param payload 原始消息文本
     * @param wsId    WebSocket 连接 ID（仅用于日志）
     * @return 解析结果
     */
    private WsInboundMessage parseBody(String payload, String wsId) {
        try {
            return objectMapper.readValue(payload, WsInboundMessage.class);
        } catch (Exception e) {
            log.debug("[AgentWS] payload 非 JSON wsId={}", wsId);
            return WsInboundMessage.ofPlainText(payload);
        }
    }
}
