package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsKickedOutMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChannelWsHandler")
class AgentChannelWsHandlerTest {

    @Mock AgentConnectionRegistry registry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock ConversationHistoryRepository historyRepository;

    private AgentChannelWsHandler handler;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        handler = new AgentChannelWsHandler(registry, visitorNotifier, historyRepository, objectMapper);
        // 设置默认模式为 BROADCAST
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.BROADCAST);
    }

    private WebSocketSession sessionWithAgentId(String wsId, String agentId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("agentId", agentId);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    @DisplayName("BROADCAST 模式：连接建立时 register 并推送 CONNECTED")
    void broadcast_mode_registers_and_sends_connected() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-1", "agent-1");

        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-1", session);
        // CONNECTED 信令以 WsConnectedMessage 类型传入 sendToSession
        verify(registry).sendToSession(eq(session), any(WsConnectedMessage.class));
        // BROADCAST 模式不调用 broadcastExcept 和 closeAllExcept
        verify(registry, never()).broadcastExcept(any(), any(), any());
        verify(registry, never()).closeAllExcept(any(), any());
    }

    @Test
    @DisplayName("KICK 模式：连接建立时踢出旧端")
    void kick_mode_kicks_old_sessions() throws Exception {
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.KICK);

        WebSocketSession session = sessionWithAgentId("ws-2", "agent-2");
        when(registry.getAgentLock("agent-2")).thenReturn(new Object());

        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-2", session);
        verify(registry).broadcastExcept(eq("agent-2"), eq(session), any(WsKickedOutMessage.class));
        verify(registry).closeAllExcept("agent-2", session);
    }

    @Test
    @DisplayName("MESSAGE 类型消息：写历史并转发给访客")
    void message_type_stores_history_and_notifies_visitor() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(historyRepository.appendAgentMessage("sess-1", "你好")).thenReturn(1L);

        String json = objectMapper.writeValueAsString(
                Map.of("type", "MESSAGE", "sessionId", "sess-1", "content", "你好"));
        handler.handleTextMessage(session, new TextMessage(json));

        verify(historyRepository).appendAgentMessage("sess-1", "你好");
        verify(visitorNotifier).notifyVisitor(eq("sess-1"), any());
    }

    @Test
    @DisplayName("TYPING 类型消息：直接转发给访客，不写历史")
    void typing_type_notifies_visitor_without_history() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);

        String json = objectMapper.writeValueAsString(
                Map.of("type", "TYPING", "sessionId", "sess-2", "timestamp", 1000L));
        handler.handleTextMessage(session, new TextMessage(json));

        verify(visitorNotifier).notifyVisitor(eq("sess-2"), any());
        verify(historyRepository, never()).appendAgentMessage(any(), any());
    }

    @Test
    @DisplayName("连接关闭时调用 unregister")
    void connection_closed_calls_unregister() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-5", "agent-5");
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(registry).unregister(session);
    }
}
