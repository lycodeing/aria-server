package com.aidevplatform.conversation.infrastructure.websocket;

import com.aidevplatform.conversation.application.service.SessionQueueService;
import com.aidevplatform.conversation.domain.MessageRole;
import com.aidevplatform.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服实时对话 WebSocket Handler。
 * <p>
 * 路径规则（由 WebSocketConfig 注册）：
 * /ws/chat/{sessionId}   → 访客端
 * /ws/agent/{sessionId}  → 座席端
 * <p>
 * 消息格式（JSON）：
 * { "type":"MESSAGE", "sessionId":"xxx", "role":"user|agent", "content":"...", "timestamp":1234 }
 * { "type":"CONNECTED", "sessionId":"xxx", "role":"user|agent" }
 * <p>
 * 历史存储统一委托给 ConversationHistoryRepository，不再直接操作 Redis。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // ---- 消息类型常量 ----
    private static final String MSG_TYPE_CONNECTED   = "CONNECTED";
    private static final String MSG_TYPE_MESSAGE     = "MESSAGE";
    private static final String MSG_TYPE_AGENT_JOINED = "AGENT_JOINED";

    // ---- 路径常量 ----
    private static final String PATH_SEGMENT_CHAT = "chat";
    private static final String DEFAULT_SESSION_ID = "unknown";

    // ---- 属性 key ----
    private static final String ATTR_ROLE = "role";
    private static final String ATTR_SESSION_ID = "sessionId";

    /**
     * 访客 WS 会话注册表: sessionId → WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> visitorSessions = new ConcurrentHashMap<>();
    /**
     * 座席 WS 会话注册表: sessionId → WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final SessionQueueService sessionQueueService;
    private final ConversationHistoryRepository historyRepository;

    // ----------------------------------------------------------------
    // 连接生命周期
    // ----------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String[] parts = parsePath(session);
        String role = parts[0];
        String sessionId = parts[1];

        session.getAttributes().put(ATTR_ROLE, role);
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);

        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.put(sessionId, session);
            log.info("[WS] visitor connected sessionId={}", sessionId);
            sendJson(session, Map.of("type", MSG_TYPE_CONNECTED, "sessionId", sessionId, "role", MessageRole.USER.getValue()));
        } else {
            agentSessions.put(sessionId, session);
            log.info("[WS] agent connected sessionId={}", sessionId);
            sendJson(session, Map.of("type", MSG_TYPE_CONNECTED, "sessionId", sessionId, "role", MessageRole.AGENT.getValue()));
            // 通知访客人工已接入
            notifyVisitor(sessionId,
                    Map.of("type", MSG_TYPE_AGENT_JOINED, "sessionId", sessionId, "content", "人工客服已接入，请稍候"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);

        // 解析消息体，取 content 字段；如果是纯文本则整体视为 content
        String content;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(message.getPayload(), Map.class);
            content = (String) body.getOrDefault("content", message.getPayload());
        } catch (Exception e) {
            log.debug("[WS] message payload is not JSON, treat as plain text sessionId={}", sessionId);
            content = message.getPayload();
        }

        long ts = Instant.now().getEpochSecond();

        if (PATH_SEGMENT_CHAT.equals(role)) {
            // 访客 → 存历史 → 转发给座席
            historyRepository.append(sessionId, MessageRole.USER.getValue(), content);
            Map<String, Object> msg = Map.of(
                    "type", MSG_TYPE_MESSAGE, "sessionId", sessionId,
                    "role", MessageRole.USER.getValue(), "content", content, "timestamp", ts
            );
            notifyAgent(sessionId, msg);
            log.debug("[WS] user→agent sessionId={}", sessionId);
        } else {
            // 座席 → appendAgentMessage（Redis List 写 assistant，DB 写 agent，便于质检分析）→ 转发给访客
            historyRepository.appendAgentMessage(sessionId, content);
            Map<String, Object> msg = Map.of(
                    "type", MSG_TYPE_MESSAGE, "sessionId", sessionId,
                    "role", MessageRole.AGENT.getValue(), "content", content, "timestamp", ts
            );
            notifyVisitor(sessionId, msg);
            log.debug("[WS] agent→user sessionId={}", sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);

        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.remove(sessionId);
            log.info("[WS] visitor disconnected sessionId={}", sessionId);
        } else {
            agentSessions.remove(sessionId);
            // 座席断开时关闭 Redis 中的会话，避免访客消息永远进入死会话
            try {
                sessionQueueService.close(sessionId);
            } catch (Exception e) {
                log.warn("[WS] close session on agent disconnect failed sessionId={}", sessionId, e);
            }
            log.info("[WS] agent disconnected sessionId={}", sessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        String role      = (String) session.getAttributes().get(ATTR_ROLE);
        log.warn("[WS] transport error sessionId={} role={}", sessionId, ex.getMessage());
        // I4 修复：transport error 后 Spring 不保证一定触发 afterConnectionClosed，
        // 这里主动清理 map，防止僵尸 session 积累
        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.remove(sessionId);
        } else {
            agentSessions.remove(sessionId);
        }
    }

    // ----------------------------------------------------------------
    // 路由工具
    // ----------------------------------------------------------------

    /**
     * 通知访客
     */
    public void notifyVisitor(String sessionId, Object payload) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs != null && vs.isOpen()) {
            sendJson(vs, payload);
        }
    }

    /**
     * 通知座席
     */
    public void notifyAgent(String sessionId, Object payload) {
        WebSocketSession as = agentSessions.get(sessionId);
        if (as != null && as.isOpen()) {
            sendJson(as, payload);
        }
    }

    // ----------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------

    private void sendJson(WebSocketSession session, Object payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("[WS] send failed sessionId={}", session.getAttributes().get(ATTR_SESSION_ID), e);
        }
    }

    /**
     * 从 URI 中解析 role 和 sessionId。
     * /ws/chat/{sessionId}  → ["chat", sessionId]
     * /ws/agent/{sessionId} → ["agent", sessionId]
     */
    private String[] parsePath(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "/ws/chat/unknown";
        String[] segs = path.split("/");
        // segs: ["", "ws", "chat"|"agent", sessionId]
        String role = segs.length > 2 ? segs[2] : PATH_SEGMENT_CHAT;
        String sessionId = segs.length > 3 ? segs[3] : DEFAULT_SESSION_ID;
        return new String[]{role, sessionId};
    }
}
