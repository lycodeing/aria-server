package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * 座席专用 WebSocket Handler。
 *
 * <p>注册到端点 {@code /ws/agent}，负责：
 * <ul>
 *   <li>连接建立/关闭时维护 {@link AgentConnectionRegistry}</li>
 *   <li>按 {@code agent.ws.multi-login-mode} 执行 BROADCAST 或 KICK 逻辑</li>
 *   <li>处理座席发送的消息（MESSAGE 写历史转发访客，TYPING 直接转发）</li>
 *   <li>向新连接推送 CONNECTED 确认信令</li>
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
    private static final String MSG_TYPE_CONNECTED = "CONNECTED";
    private static final String MSG_TYPE_KICKED_OUT = "KICKED_OUT";
    private static final String MSG_TYPE_MESSAGE = "MESSAGE";
    private static final String MSG_TYPE_TYPING = "TYPING";

    /**
     * 单条消息最大字节数（64KB），与 ChatWebSocketHandler 保持一致
     */
    private static final int MAX_MESSAGE_BYTES = 65536;

    private final AgentConnectionRegistry registry;
    private final VisitorNotifier visitorNotifier;
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Value("${agent.ws.multi-login-mode:BROADCAST}")
    private MultiLoginMode multiLoginMode;

    /**
     * 连接建立后：按 multiLoginMode 执行注册逻辑，KICK 模式三步原子化，最后推送 CONNECTED 信令。
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
            // KICK 模式：per-agentId 锁原子化三步，防止并发双踢竞态
            synchronized (registry.getAgentLock(agentId)) {
                registry.register(agentId, session);
                registry.broadcastExcept(agentId, session, Map.of("type", MSG_TYPE_KICKED_OUT));
                registry.closeAllExcept(agentId, session);
            }
        } else {
            // BROADCAST 模式：直接注册，无锁
            registry.register(agentId, session);
        }

        // 通知新端连接成功（锁外推送）
        sendJson(session, Map.of("type", MSG_TYPE_CONNECTED));
        log.info("[AgentWS] 座席连接建立 agentId={} wsId={} mode={}", agentId, session.getId(), multiLoginMode);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            log.warn("[AgentWS] 消息超过最大长度 wsId={} size={}", session.getId(), message.getPayloadLength());
            sendJson(session, Map.of("type", "ERROR", "message", "消息长度超过限制（最大 64KB）"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        Map<String, Object> body = parseBody(message.getPayload(), session.getId());
        String type = (String) body.getOrDefault("type", MSG_TYPE_MESSAGE);
        String sessionId = (String) body.get("sessionId");
        String content = (String) body.get("content");
        long ts = Instant.now().getEpochSecond();

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[AgentWS] 消息缺少 sessionId wsId={}", session.getId());
            return;
        }

        if (MSG_TYPE_TYPING.equals(type)) {
            // TYPING 信号：ephemeral，不写历史，直接转发给访客
            visitorNotifier.notifyVisitor(sessionId,
                    Map.of("type", MSG_TYPE_TYPING, "sessionId", sessionId, "timestamp", ts));
            return;
        }

        // MESSAGE：写历史，转发给访客
        if (content == null || content.isBlank()) {
            log.warn("[AgentWS] MESSAGE 内容为空 sessionId={} wsId={}", sessionId, session.getId());
            return;
        }
        long seq = historyRepository.appendAgentMessage(sessionId, content);
        visitorNotifier.notifyVisitor(sessionId, Map.of(
                "type", MSG_TYPE_MESSAGE,
                "sessionId", sessionId,
                "role", MessageRole.AGENT.getValue(),
                "content", content,
                "seq", seq,
                "timestamp", ts));
        log.debug("[AgentWS] agent→visitor sessionId={} seq={}", sessionId, seq);
    }

    /**
     * 连接正常/异常关闭后：从注册表注销连接，不触发会话关闭逻辑（座席断线 ≠ 会话结束）。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String payload, String wsId) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("[AgentWS] payload 非 JSON wsId={}", wsId);
            return Map.of("content", payload);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("[AgentWS] 发送失败 wsId={} msg={}", session.getId(), e.getMessage());
        }
    }
}
