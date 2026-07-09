package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ChatWebSocketHandler#notifyAgent} 改造后行为验证。
 *
 * <p>字段顺序（@RequiredArgsConstructor 生成顺序）：
 * objectMapper, historyRepository, sessionQueueService, agentConnectionRegistry
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler.notifyAgent 改造后行为")
class ChatWebSocketHandlerNotifyAgentTest {

    @Mock SessionQueueService sessionQueueService;
    @Mock AgentConnectionRegistry agentConnectionRegistry;
    @Mock ConversationHistoryRepository historyRepository;

    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        // 字段声明顺序：objectMapper, historyRepository, sessionQueueService, agentConnectionRegistry
        handler = new ChatWebSocketHandler(
                new ObjectMapper(),
                historyRepository,
                sessionQueueService,
                agentConnectionRegistry);
    }

    @Test
    @DisplayName("TYPING 消息直接跳过，不查 Redis 也不广播")
    void typing_skips_redis_lookup() {
        handler.notifyAgent("sess-1", Map.of("type", "TYPING", "sessionId", "sess-1"));

        verifyNoInteractions(sessionQueueService);
        verifyNoInteractions(agentConnectionRegistry);
    }

    @Test
    @DisplayName("有 agentId 时广播消息")
    void with_agentId_broadcasts_message() {
        when(sessionQueueService.getAgentId("sess-2")).thenReturn("agent-001");

        handler.notifyAgent("sess-2", Map.of("type", "MESSAGE", "content", "hello"));

        verify(agentConnectionRegistry).broadcast(eq("agent-001"), any());
    }

    @Test
    @DisplayName("agentId 为 null 时跳过广播（WAITING 状态或会话不存在）")
    void null_agentId_skips_broadcast() {
        when(sessionQueueService.getAgentId("sess-3")).thenReturn(null);

        handler.notifyAgent("sess-3", Map.of("type", "MESSAGE", "content", "hello"));

        verifyNoInteractions(agentConnectionRegistry);
    }
}
