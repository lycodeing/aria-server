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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VisitorSessionRegistry")
class VisitorSessionRegistryTest {

    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private VisitorSessionRegistry registry;

    @BeforeEach
    void setUp() {
        when(podIdentity.get()).thenReturn("pod-A");
        registry = new VisitorSessionRegistry(new ObjectMapper(), presenceRegistry, podIdentity);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    @Test
    @DisplayName("register 时调用 presenceRegistry.registerVisitor")
    void register_calls_presence() {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        verify(presenceRegistry).registerVisitor("sess-1", "pod-A");
    }

    @Test
    @DisplayName("unregister：本 session 是活跃连接时清理 presence")
    void unregister_removes_presence_when_active() {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        registry.unregister("sess-1", session);
        verify(presenceRegistry).unregisterVisitor("sess-1");
    }

    @Test
    @DisplayName("unregister：本 session 已被新连接替换时不清理 presence（防重连竞态）")
    void unregister_skips_presence_when_replaced() {
        WebSocketSession oldSession = openSession("ws-old");
        WebSocketSession newSession = openSession("ws-new");
        registry.register("sess-1", oldSession);
        registry.register("sess-1", newSession);
        registry.unregister("sess-1", oldSession);
        verify(presenceRegistry, never()).unregisterVisitor("sess-1");
    }

    @Test
    @DisplayName("notifyVisitor 向 session 发送 JSON 消息")
    void notifyVisitor_sends_message() throws Exception {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        registry.notifyVisitor("sess-1", java.util.Map.of("type", "MESSAGE"));
        verify(session).sendMessage(any(TextMessage.class));
    }
}
