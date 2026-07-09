package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChannelWsHandler KICK 分布式锁")
class AgentChannelWsHandlerKickDistributedTest {

    @Mock AgentConnectionRegistry registry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;
    @Mock WsMessageRouter router;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    private AgentChannelWsHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(podIdentity.get()).thenReturn("pod-A");
        handler = new AgentChannelWsHandler(registry, visitorNotifier, historyRepository,
                new ObjectMapper(), presenceRegistry, podIdentity, router, redissonClient);
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.KICK);
    }

    private WebSocketSession kickSession(String agentId) {
        WebSocketSession s = mock(WebSocketSession.class);
        lenient().when(s.getId()).thenReturn("ws-new");
        lenient().when(s.isOpen()).thenReturn(true);
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put("agentId", agentId);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    @DisplayName("KICK 模式：获取 Redisson 锁，注册并向旧 Pod 发 KICK 命令")
    void kick_mode_acquires_redisson_lock_and_sends_kick() throws Exception {
        when(redissonClient.getLock(startsWith("ws:kick:agent:"))).thenReturn(rLock);
        when(rLock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(java.util.Set.of("pod-B"));
        when(podIdentity.isLocal("pod-B")).thenReturn(false);

        WebSocketSession session = kickSession("agent-1");
        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-1", session);
        verify(router).sendKick("pod-B", "agent-1", "ws-new");
        verify(registry).broadcastExcept(eq("agent-1"), eq(session), any());
        verify(registry).closeAllExcept("agent-1", session);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("KICK 锁获取失败：关闭连接并返回")
    void kick_lock_failed_closes_session() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(false);

        WebSocketSession session = kickSession("agent-2");
        handler.afterConnectionEstablished(session);

        verify(session).close(any());
        verifyNoInteractions(registry);
    }
}
