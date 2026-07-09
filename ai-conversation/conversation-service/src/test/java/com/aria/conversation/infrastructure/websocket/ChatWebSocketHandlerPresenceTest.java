package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler presence 集成")
class ChatWebSocketHandlerPresenceTest {

    @Mock SessionQueueService sessionQueueService;
    @Mock AgentConnectionRegistry agentConnectionRegistry;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private ChatWebSocketHandler buildHandler() {
        when(podIdentity.get()).thenReturn("pod-A");
        return new ChatWebSocketHandler(
                new ObjectMapper(), historyRepository, sessionQueueService,
                agentConnectionRegistry, presenceRegistry, podIdentity);
    }

    private WebSocketSession visitorSession(String sessionId) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn("ws-" + sessionId);
        when(s.getUri()).thenReturn(new URI("/ws/chat/" + sessionId));
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    @Test
    @DisplayName("访客连接建立时调用 presenceRegistry.registerVisitor")
    void connection_established_registers_visitor_presence() throws Exception {
        ChatWebSocketHandler handler = buildHandler();
        WebSocketSession session = visitorSession("sess-001");
        handler.afterConnectionEstablished(session);
        verify(presenceRegistry).registerVisitor("sess-001", "pod-A");
    }
}
