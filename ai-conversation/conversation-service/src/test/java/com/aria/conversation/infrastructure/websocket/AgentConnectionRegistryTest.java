package com.aria.conversation.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AgentConnectionRegistry")
class AgentConnectionRegistryTest {

    private AgentConnectionRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new AgentConnectionRegistry(objectMapper);
    }

    private WebSocketSession openSession(String id) throws IOException {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("register 后 broadcast 能发送消息")
    void broadcast_sends_to_registered_session() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register("agent-1", session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("unregister 后 broadcast 不再发送")
    void unregister_stops_broadcast() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register("agent-1", session);
        registry.unregister(session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("broadcastExcept 排除指定 session")
    void broadcastExcept_skips_excluded_session() throws IOException {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);

        registry.broadcastExcept("agent-1", s2, java.util.Map.of("type", "KICKED_OUT"));

        verify(s1, times(1)).sendMessage(any(TextMessage.class));
        verify(s2, never()).sendMessage(any());
    }

    @Test
    @DisplayName("closeAllExcept 关闭除 keep 以外的连接")
    void closeAllExcept_closes_old_sessions() throws IOException {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");  // keep
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);

        registry.closeAllExcept("agent-1", s2);

        verify(s1, times(1)).close(any());
        verify(s2, never()).close(any());
    }

    @Test
    @DisplayName("同一 agentId 的锁对象每次返回相同实例")
    void getAgentLock_returns_same_instance() {
        Object lock1 = registry.getAgentLock("agent-1");
        Object lock2 = registry.getAgentLock("agent-1");
        assertThat(lock1).isSameAs(lock2);
    }

    @Test
    @DisplayName("broadcast 时 session 已关闭则跳过")
    void broadcast_skips_closed_session() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(false);
        registry.register("agent-1", session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, never()).sendMessage(any());
    }
}
