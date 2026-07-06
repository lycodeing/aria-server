package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
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
    private static final String MSG_TYPE_CONNECTED = "CONNECTED";
    private static final String MSG_TYPE_MESSAGE = "MESSAGE";
    private static final String MSG_TYPE_AGENT_JOINED = "AGENT_JOINED";
    private static final String MSG_TYPE_ERROR = "ERROR";

    // ---- 路径常量 ----
    private static final String PATH_SEGMENT_CHAT = "chat";
    private static final String DEFAULT_SESSION_ID = "";

    // ---- 属性 key ----
    private static final String ATTR_ROLE = "role";
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
     * S-03 合法 role 白名单，仅允许 "chat"（访客）和 "agent"（座席）
     */
    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("chat", "agent");
    /**
     * 访客 WS 会话注册表: sessionId → WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> visitorSessions = new ConcurrentHashMap<>();
    /**
     * 座席 WS 会话注册表: sessionId → WebSocketSession
     */
    private final ConcurrentHashMap<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    /**
     * S-02 线程安全：每个 sessionId 对应一把发送锁，串行化 sendMessage 调用。
     * {@link org.springframework.web.socket.WebSocketSession#sendMessage} 非线程安全；
     * RabbitMQ 监听线程（notifyVisitor/notifyAgent）与 WS IO 线程（handleTextMessage）
     * 可能并发写同一 session，若不加锁会导致帧损坏或 {@link IllegalStateException}。
     * 连接关闭时在 {@link #afterConnectionClosed} / {@link #handleTransportError} 中一并清理。
     */
    private final ConcurrentHashMap<String, Object> sendLocks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
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
            // 重连时关闭旧连接并清理其 sendLock，防止旧 TCP 半开和锁 map 无限增长
            WebSocketSession oldVisitor = visitorSessions.put(sessionId, session);
            closeStaleSession(oldVisitor);
            log.info("[WS] visitor connected sessionId={}", sessionId);
            sendJson(session, Map.of("type", MSG_TYPE_CONNECTED, "sessionId", sessionId, "role", MessageRole.USER.getValue()));
        } else {
            WebSocketSession oldAgent = agentSessions.put(sessionId, session);
            closeStaleSession(oldAgent);
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
        if (!checkMessageLength(session, message)) {
            return;
        }

        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        String content = parseMessageContent(session, message, sessionId);
        long ts = Instant.now().getEpochSecond();

        if (PATH_SEGMENT_CHAT.equals(role)) {
            handleVisitorMessage(sessionId, content, ts);
        } else {
            handleAgentMessage(sessionId, content, ts);
        }
    }

    /**
     * 检查消息长度，超过限制时发送错误并关闭连接。
     *
     * @param session WebSocket 会话
     * @param message 接收到的消息
     * @return true 表示长度合法，false 表示已处理超长消息
     */
    private boolean checkMessageLength(WebSocketSession session, TextMessage message) throws IOException {
        if (message.getPayloadLength() <= MAX_MESSAGE_BYTES) {
            return true;
        }
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        log.warn("[WS] 消息超过最大长度限制 sessionId={} size={}", sessionId, message.getPayloadLength());
        sendJson(session, Map.of("type", MSG_TYPE_ERROR, "message", "消息长度超过限制（最大 64KB）"));
        session.close(CloseStatus.NOT_ACCEPTABLE);
        return false;
    }

    /**
     * 解析消息内容：尝试解析 JSON 取 content 字段，失败时降级为纯文本。
     *
     * @param session   WebSocket 会话
     * @param message   接收到的消息
     * @param sessionId 会话 ID（用于日志）
     * @return 消息正文内容
     */
    private String parseMessageContent(WebSocketSession session, TextMessage message, String sessionId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(message.getPayload(), Map.class);
            return (String) body.getOrDefault("content", message.getPayload());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 客户端可能直接发送纯文本而非 JSON，降级处理
            log.debug("[WS] message payload is not JSON, treat as plain text sessionId={}", sessionId);
            return message.getPayload();
        }
    }

    /**
     * 处理访客消息：存储历史并转发给座席。
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param timestamp 时间戳
     */
    private void handleVisitorMessage(String sessionId, String content, long timestamp) {
        // 访客 → 存历史 → 转发给座席（payload 携带 seq 支持客户端断线重连后的 sinceSeq 增量同步）
        long seq = historyRepository.append(sessionId, MessageRole.USER.getValue(), content);
        Map<String, Object> msg = Map.of(
                "type", MSG_TYPE_MESSAGE, "sessionId", sessionId,
                "role", MessageRole.USER.getValue(),
                "content", content,
                "seq", seq,
                "timestamp", timestamp
        );
        notifyAgent(sessionId, msg);
        log.debug("[WS] user→agent sessionId={} seq={}", sessionId, seq);
    }

    /**
     * 处理座席消息：存储历史并转发给访客。
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param timestamp 时间戳
     */
    private void handleAgentMessage(String sessionId, String content, long timestamp) {
        // 座席 → appendAgentMessage（Redis List 写 assistant，DB 写 agent，便于质检分析）→ 转发给访客
        long seq = historyRepository.appendAgentMessage(sessionId, content);
        Map<String, Object> msg = Map.of(
                "type", MSG_TYPE_MESSAGE, "sessionId", sessionId,
                "role", MessageRole.AGENT.getValue(),
                "content", content,
                "seq", seq,
                "timestamp", timestamp
        );
        notifyVisitor(sessionId, msg);
        log.debug("[WS] agent→user sessionId={} seq={}", sessionId, seq);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);

        // 非法 sessionId 或握手阶段就被拒绝的连接，attributes 未写入，直接忽略
        if (role == null || sessionId == null) {
            return;
        }

        // S-02：连接关闭时释放 sendLock，避免 lock map 无限增长
        sendLocks.remove(session.getId());

        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.remove(sessionId);
            log.info("[WS] visitor disconnected sessionId={}", sessionId);
        } else {
            agentSessions.remove(sessionId);
            // 注意：座席 WS 断开（刷新页面、网络抖动）不等于会话结束，不在此处关闭会话。
            // 会话关闭只由座席主动调用 /sessions/{id}/close 接口触发，
            // 防止座席临时断线导致进行中的会话被意外终止。
            log.info("[WS] agent disconnected sessionId={}", sessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        String role = (String) session.getAttributes().get(ATTR_ROLE);

        // 非法 sessionId 或握手阶段就被拒绝的连接，attributes 未写入，直接忽略
        if (role == null || sessionId == null) {
            return;
        }

        log.warn("[WS] transport error sessionId={} role={}", sessionId, ex.getMessage());
        // S-02：transport error 后同步释放 sendLock
        sendLocks.remove(session.getId());
        // I4 修复：transport error 后 Spring 不保证一定触发 afterConnectionClosed，
        // 这里主动清理 map，防止僵尸 session 积累
        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessions.remove(sessionId);
        } else {
            agentSessions.remove(sessionId);
            // 注意：transport error（网络抖动/超时）不等于会话结束，不在此处关闭会话。
            // 会话关闭只由座席主动调用 /sessions/{id}/close 接口触发。
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
        if (vs == null || !vs.isOpen()) {
            log.warn("[WS] notifyVisitor sessionId={} visitor not connected", sessionId);
            return;
        }
        sendJson(vs, payload);
    }

    /**
     * 主动以正常状态（code=1000 NORMAL）关闭访客端 WS。
     * <p>用途：座席端点击「结束会话」时由 SessionQueueService.close 调用，
     * 前端 chat-widget 监听到 code=1000 会显示"会话已结束"提示并清理 transferred 状态。
     */
    public void closeVisitorSessionNormal(String sessionId) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs != null && vs.isOpen()) {
            try {
                vs.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("[WS] closeVisitorSessionNormal IO 异常 sessionId={} msg={}", sessionId, e.getMessage());
            } finally {
                visitorSessions.remove(sessionId);
            }
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

    /**
     * 关闭已被替换的旧 WebSocket 连接，并清理其 sendLock。
     * 防止同一 sessionId 重连时旧 TCP 连接半开及锁 map 无限增长。
     */
    private void closeStaleSession(WebSocketSession stale) {
        if (stale == null) return;
        sendLocks.remove(stale.getId());
        if (stale.isOpen()) {
            try {
                stale.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("[WS] 关闭旧连接失败 staleId={} msg={}", stale.getId(), e.getMessage());
            }
        }
    }

    /**
     * 向 WebSocket 发送 JSON 消息。
     *
     * <p>失败处理：
     * <ul>
     *   <li>序列化失败（编码异常）→ ERROR 日志，session 状态不变</li>
     *   <li>IOException（TCP 半关闭/瞬时网络异常）→ 主动关闭 session（SERVER_ERROR），
     *       触发客户端 onclose 钩子走指数退避重连，重连后用 lastSeq 调 history 拉增量；
     *       消息本身已通过 historyRepository.append 写入 DB（MQ 异步持久化），不会丢失</li>
     * </ul>
     *
     * <p>S-02 线程安全：每个 session 对应一把锁（sendLocks），串行化 sendMessage 调用，
     * 避免 MQ 监听线程和 WS IO 线程并发写同一 session 帧损坏 / IllegalStateException。
     */
    private void sendJson(WebSocketSession session, Object payload) {
        if (!session.isOpen()) {
            return;
        }
        Object sessionId = session.getAttributes().get(ATTR_SESSION_ID);
        // S-02：每个 session 独立锁，防止并发写帧损坏
        Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            if (!session.isOpen()) return; // double-check in critical section
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[WS] payload 序列化失败 sessionId={}", sessionId, e);
            } catch (IOException e) {
                // 主动关闭以触发客户端重连，重连后凭 lastSeq 拉增量补齐空窗消息（消息已在 DB）
                log.warn("[WS] send failed, closing session for client reconnect sessionId={}", sessionId, e);
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                    // session 已不可用，afterConnectionClosed/handleTransportError 会清理 map
                }
            }
        }
    }

    /**
     * 从 URI 中解析 role 和 sessionId。
     * /ws/chat/{sessionId}  → ["chat", sessionId]
     * /ws/agent/{sessionId} → ["agent", sessionId]
     * <p>
     * S2：对解析出的 sessionId 做格式校验，不合法时向客户端发送错误消息并关闭连接。
     * S3：对 role 做白名单校验，非 "chat"/"agent" 均拒绝，防止任意路径前缀绕过鉴权。
     *
     * @return [role, sessionId] 数组；非法时关闭连接并返回 null
     */
    private String[] parsePath(WebSocketSession session) throws IOException {
        String path = session.getUri() != null ? session.getUri().getPath() : "/ws/chat/unknown";
        String[] segs = path.split("/");
        // segs: ["", "ws", "chat"|"agent", sessionId]
        String role = segs.length > 2 ? segs[2] : PATH_SEGMENT_CHAT;
        String sessionId = segs.length > 3 ? segs[3] : DEFAULT_SESSION_ID;

        // S3：role 白名单校验，非法 role 拒绝连接
        if (!VALID_ROLES.contains(role)) {
            log.warn("[WS] 非法 role 路径，拒绝连接 role={} path={}", role, path);
            sendJson(session, Map.of("type", MSG_TYPE_ERROR, "message", "非法的连接路径"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return null;
        }

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
