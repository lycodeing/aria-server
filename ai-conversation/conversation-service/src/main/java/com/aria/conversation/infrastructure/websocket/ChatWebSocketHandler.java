package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsErrorMessage;
import com.aria.conversation.infrastructure.websocket.message.WsInboundMessage;
import com.aria.conversation.infrastructure.websocket.message.WsMessageType;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 客服实时对话 WebSocket Handler（访客端）。
 *
 * <p>路径规则（由 WebSocketConfig 注册）：
 * {@code /ws/chat/{sessionId}} → 访客端
 *
 * <p>消息格式（JSON）：
 * <pre>
 * 入站：{ "type":"MESSAGE"|"TYPING", "content":"..." }
 * 出站：{@link WsConnectedMessage}、{@link WsChatMessage}、{@link WsTypingMessage}、{@link WsErrorMessage}
 * </pre>
 *
 * <p>纯协议适配器：连接生命周期管理（session 注册/注销/推送/心跳/presence）
 * 完全委托给 {@link VisitorSessionRegistry}，本类只负责协议解析与消息路由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

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
     * S-03 合法 role 白名单，仅允许 "chat"（访客）
     */
    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("chat");

    private final ObjectMapper objectMapper;
    private final ConversationHistoryRepository historyRepository;
    private final SessionQueueService sessionQueueService;
    private final AgentConnectionRegistry agentConnectionRegistry;
    private final VisitorSessionRegistry visitorSessionRegistry;
    private final WsMessageRouter router;

    // ----------------------------------------------------------------
    // 连接生命周期
    // ----------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
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
            visitorSessionRegistry.register(sessionId, session);
            log.info("[WS] visitor connected sessionId={}", sessionId);
            visitorSessionRegistry.notifyVisitor(sessionId, WsConnectedMessage.forVisitor(sessionId, visitorSessionRegistry.getPodId()));
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        // S3：消息长度检查，超过 64KB 时拒绝并关闭连接
        if (!checkMessageLength(session, message)) {
            return;
        }

        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        long ts = Instant.now().getEpochSecond();

        WsInboundMessage body = parseBody(message.getPayload(), sessionId);

        // TYPING 信号：仅访客的 typing 信号需要转发给座席，不写历史
        if (body.isType(WsMessageType.TYPING)) {
            if (PATH_SEGMENT_CHAT.equals(role)) {
                notifyAgent(sessionId, WsTypingMessage.of(sessionId, ts));
            }
            return;
        }

        if (PATH_SEGMENT_CHAT.equals(role)) {
            handleVisitorMessage(sessionId, body.content() != null ? body.content() : message.getPayload(), ts);
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
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                WsErrorMessage.of("消息长度超过限制（最大 64KB）"))));
        session.close(CloseStatus.NOT_ACCEPTABLE);
        return false;
    }

    /**
     * 将 JSON 字符串反序列化为 {@link WsInboundMessage}。
     * 非 JSON 时降级封装为 content=rawPayload 的 MESSAGE 类型。
     *
     * @param payload   原始消息文本
     * @param sessionId 会话 ID（仅用于日志）
     * @return 解析结果
     */
    private WsInboundMessage parseBody(String payload, String sessionId) {
        try {
            return objectMapper.readValue(payload, WsInboundMessage.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("[WS] message payload is not JSON, treat as plain text sessionId={}", sessionId);
            return WsInboundMessage.ofPlainText(payload);
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
        notifyAgent(sessionId, WsChatMessage.fromVisitor(sessionId, content, seq, timestamp));
        log.debug("[WS] user→agent sessionId={} seq={}", sessionId, seq);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String role = (String) session.getAttributes().get(ATTR_ROLE);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);

        // 非法 sessionId 或握手阶段就被拒绝的连接，attributes 未写入，直接忽略
        if (role == null || sessionId == null) {
            return;
        }

        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessionRegistry.unregister(sessionId, session);
            log.info("[WS] visitor disconnected sessionId={}", sessionId);
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
        // I4 修复：transport error 后 Spring 不保证一定触发 afterConnectionClosed，
        // 这里主动清理，防止僵尸 session 积累
        if (PATH_SEGMENT_CHAT.equals(role)) {
            visitorSessionRegistry.unregister(sessionId, session);
        }
    }

    /**
     * 通知座席。通过应用层查询 agentId，再由 {@link AgentConnectionRegistry} 广播。
     *
     * <p>TYPING 信号在入口处直接跳过（ephemeral 信号允许丢失，跳过 Redis 查询），
     * 业务消息才走 SessionQueueService 查 agentId + registry.broadcast。
     *
     * @param sessionId 会话 ID
     * @param payload   消息对象
     */
    public void notifyAgent(String sessionId, Object payload) {
        // TYPING 信号为 ephemeral，直接跳过 Redis 查询和广播（避免不必要的 Redis I/O）
        if (payload instanceof WsTypingMessage) {
            return;
        }
        String agentId = sessionQueueService.getAgentId(sessionId);
        if (agentId == null) {
            log.warn("[WS] notifyAgent 跳过：sessionId={} 尚未分配座席或会话已关闭", sessionId);
            return;
        }
        router.sendToAgent(agentId, payload);
    }

    // ----------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------

    /**
     * 从 URI 中解析 role 和 sessionId。
     * {@code /ws/chat/{sessionId}} → ["chat", sessionId]
     *
     * <p>S2：对解析出的 sessionId 做格式校验，不合法时向客户端发送错误消息并关闭连接。
     * <p>S3：对 role 做白名单校验，非 "chat" 均拒绝，防止任意路径前缀绕过鉴权。
     *
     * @return [role, sessionId] 数组；非法时关闭连接并返回 null
     */
    private String[] parsePath(WebSocketSession session) throws IOException {
        String path = session.getUri() != null ? session.getUri().getPath() : "/ws/chat/unknown";
        String[] segs = path.split("/");
        // segs: ["", "ws", "chat", sessionId]
        String role = segs.length > 2 ? segs[2] : PATH_SEGMENT_CHAT;
        String sessionId = segs.length > 3 ? segs[3] : DEFAULT_SESSION_ID;

        // S3：role 白名单校验，非法 role 拒绝连接
        if (!VALID_ROLES.contains(role)) {
            log.warn("[WS] 非法 role 路径，拒绝连接 role={} path={}", role, path);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    WsErrorMessage.of("非法的连接路径"))));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return null;
        }

        // S2：sessionId 格式校验，只允许字母、数字、下划线、连字符，长度 1~64
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            log.warn("[WS] 非法 sessionId 格式，拒绝连接 sessionId={}", sessionId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    WsErrorMessage.of("非法的 sessionId 格式"))));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return null;
        }

        return new String[]{role, sessionId};
    }
}
