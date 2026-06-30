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
import java.util.regex.Pattern;

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
    private static final String MSG_TYPE_CONNECTED    = "CONNECTED";
    private static final String MSG_TYPE_MESSAGE      = "MESSAGE";
    private static final String MSG_TYPE_AGENT_JOINED = "AGENT_JOINED";
    private static final String MSG_TYPE_ERROR        = "ERROR";

    // ---- 路径常量 ----
    private static final String PATH_SEGMENT_CHAT = "chat";
    private static final String DEFAULT_SESSION_ID = "";

    // ---- 属性 key ----
    private static final String ATTR_ROLE       = "role";
    private static final String ATTR_SESSION_ID = "sessionId";

    /**
     * S2：sessionId 格式校验正则。
     * 只允许字母、数字、下划线、连字符，长度 1~64。
     */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    /**
     * S3：单条 WebSocket 文本消息最大字节数（64KB）。
     * 超出时关闭连接并返回 NOT_ACCEPTABLE 状态。
     */
    private static final int MAX_MESSAGE_BYTES = 65536;

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
        // S2：parsePath 已完成格式校验，非法 sessionId 时返回 null 并关闭连接
        String[] parts = parsePath(session);
        if (parts == null) {
            return;
        }
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
        // S3：消息长度检查，超过 64KB 时拒绝并关闭连接
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
            log.warn("[WS] 消息超过最大长度限制 sessionId={} size={}", sessionId, message.getPayloadLength());
            sendJson(session, Map.of("type", MSG_TYPE_ERROR, "message", "消息长度超过限制（最大 64KB）"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

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

        // 非法 sessionId 或握手阶段就被拒绝的连接，attributes 未写入，直接忽略
        if (role == null || sessionId == null) return;

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

        // 非法 sessionId 或握手阶段就被拒绝的连接，attributes 未写入，直接忽略
        if (role == null || sessionId == null) return;

        log.warn("[WS] transport error sessionId={} role={}", sessionId, ex.getMessage());
        // I4 修复：transport error 后 Spring 不保证一定触发 afterConnectionClosed，
        // 这里主动清理 map，防止僵尸 session 积累
        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.remove(sessionId);
        } else {
            agentSessions.remove(sessionId);
            // 座席端断线时同步关闭会话状态
            try {
                sessionQueueService.close(sessionId);
            } catch (Exception closeEx) {
                log.warn("座席传输错误后关闭会话失败，sessionId={}", sessionId, closeEx);
            }
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
     * <p>
     * S2：对解析出的 sessionId 做格式校验，不合法时向客户端发送错误消息并关闭连接，
     * 返回 null 通知调用方终止后续处理。
     *
     * @return [role, sessionId] 数组；sessionId 格式非法时关闭连接并返回 null
     */
    private String[] parsePath(WebSocketSession session) throws IOException {
        String path = session.getUri() != null ? session.getUri().getPath() : "/ws/chat/unknown";
        String[] segs = path.split("/");
        // segs: ["", "ws", "chat"|"agent", sessionId]
        String role = segs.length > 2 ? segs[2] : PATH_SEGMENT_CHAT;
        String sessionId = segs.length > 3 ? segs[3] : DEFAULT_SESSION_ID;

        // S2：sessionId 格式校验，只允许字母、数字、下划线、连字符，长度 1~64
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            log.warn("[WS] 非法 sessionId 格式，拒绝连接 sessionId={}", sessionId);
            sendJson(session, Map.of("type", MSG_TYPE_ERROR, "message", "非法的 sessionId 格式"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return null;
        }

        return new String[]{role, sessionId};
    }
}
