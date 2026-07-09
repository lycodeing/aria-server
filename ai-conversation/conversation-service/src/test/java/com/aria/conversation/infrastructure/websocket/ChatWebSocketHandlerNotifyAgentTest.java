package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ChatWebSocketHandler#notifyAgent} 改造后行为验证。
 *
 * <p>字段顺序（@RequiredArgsConstructor 生成顺序）：
 * objectMapper, historyRepository, sessionQueueService, agentConnectionRegistry,
 * presenceRegistry, podIdentity, router
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler.notifyAgent 改造后行为")
class ChatWebSocketHandlerNotifyAgentTest {

    @Mock SessionQueueService sessionQueueService;
    @Mock AgentConnectionRegistry agentConnectionRegistry;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock WsMessageRouter router;

    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        // 字段声明顺序：objectMapper, historyRepository, sessionQueueService,
        // agentConnectionRegistry, presenceRegistry, podIdentity, router
        handler = new ChatWebSocketHandler(
                new ObjectMapper(),
                historyRepository,
                sessionQueueService,
                agentConnectionRegistry,
                null,
                null,
                router);
    }

    @Test
    @DisplayName("TYPING 消息直接跳过，不查 Redis 也不广播")
    void typing_skips_redis_lookup() {
        handler.notifyAgent("sess-1", WsTypingMessage.of("sess-1", System.currentTimeMillis() / 1000));

        verifyNoInteractions(sessionQueueService);
        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("有 agentId 时通过 router 发送消息")
    void with_agentId_routes_message() {
        when(sessionQueueService.getAgentId("sess-2")).thenReturn("agent-001");

        handler.notifyAgent("sess-2", WsChatMessage.fromVisitor("sess-2", "hello", 1L, System.currentTimeMillis() / 1000));

        verify(router).sendToAgent(eq("agent-001"), any());
    }

    @Test
    @DisplayName("agentId 为 null 时跳过广播（WAITING 状态或会话不存在）")
    void null_agentId_skips_broadcast() {
        when(sessionQueueService.getAgentId("sess-3")).thenReturn(null);

        handler.notifyAgent("sess-3", WsChatMessage.fromVisitor("sess-3", "hello", 1L, System.currentTimeMillis() / 1000));

        verifyNoInteractions(router);
    }
}
