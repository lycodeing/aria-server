package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentConnectionRegistry presence 集成")
class AgentConnectionRegistryPresenceTest {

    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private AgentConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        when(podIdentity.get()).thenReturn("pod-A");
        registry = new AgentConnectionRegistry(new ObjectMapper(), presenceRegistry, podIdentity);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        return s;
    }

    @Test
    @DisplayName("register 时调用 presenceRegistry.registerAgent")
    void register_calls_presence_register() {
        WebSocketSession session = mockSession("ws-1");
        registry.register("agent-1", session);
        verify(presenceRegistry).registerAgent("agent-1", "pod-A");
    }

    @Test
    @DisplayName("unregister 后无其他连接时调用 presenceRegistry.unregisterAgent")
    void unregister_calls_presence_unregister_when_no_more_sessions() {
        WebSocketSession session = mockSession("ws-1");
        registry.register("agent-1", session);
        registry.unregister(session);
        verify(presenceRegistry).unregisterAgent("agent-1", "pod-A");
    }

    @Test
    @DisplayName("同一 agentId 有多个连接时 unregister 不移除 presence")
    void unregister_keeps_presence_when_other_sessions_remain() {
        WebSocketSession s1 = mockSession("ws-1");
        WebSocketSession s2 = mockSession("ws-2");
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);
        registry.unregister(s1);
        verify(presenceRegistry, never()).unregisterAgent(any(), any());
    }
}
