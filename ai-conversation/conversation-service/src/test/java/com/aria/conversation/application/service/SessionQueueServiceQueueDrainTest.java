package com.aria.conversation.application.service;

import com.aria.conversation.domain.*;
import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import com.aria.conversation.application.service.CsatService;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionQueueService — tryDispatchFromQueue")
class SessionQueueServiceQueueDrainTest {

    @Mock SessionQueueRepository        queueRepository;
    @Mock AgentOnlineRegistry           agentRegistry;
    @Mock ConversationMessagePublisher  publisher;
    @Mock RabbitTemplate                rabbitTemplate;
    @Mock ConversationPersistRepository persistRepository;
    @Mock CsatService                   csatService;
    @Mock VisitorNotifier               visitorNotifier;
    @Mock CsAgentConfigProvider         configProvider;
    @Mock RedissonClient                redissonClient;
    @Mock RLock                         rLock;

    SessionQueueService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SessionQueueService(
                queueRepository, agentRegistry, publisher, rabbitTemplate,
                "test.exchange", persistRepository, csatService, visitorNotifier,
                configProvider, redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
    }

    @Test
    @DisplayName("close 后腾出空位，WAITING 会话被自动分配")
    void close_triggersQueueDrain_assignsWaitingSession() throws Exception {
        // 准备：agent-A 有 1 个 ACTIVE（即将关闭），1 个 WAITING 等待
        SessionQueueItem active = new SessionQueueItem(
                "sess-active", "v1", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
        SessionQueueItem waiting = new SessionQueueItem(
                "sess-wait", "v2", "", "", 1000L, SessionStatus.WAITING, null);

        when(queueRepository.findById("sess-active")).thenReturn(java.util.Optional.of(active));
        // close 后 findAll 只剩 waiting
        when(queueRepository.findAll()).thenReturn(List.of(waiting));
        when(agentRegistry.isOnline("agent-A")).thenReturn(true);
        when(queueRepository.compareAndSetStatus(eq("sess-wait"), any())).thenReturn(true);

        service.close("sess-active", "agent");

        // 等待异步 tryDispatchFromQueue 执行
        Thread.sleep(200);

        // 验证排队会话被分配
        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).compareAndSetStatus(eq("sess-wait"), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(captor.getValue().agentId()).isEqualTo("agent-A");
        verify(publisher).publishSessionAccept(eq("sess-wait"), eq("agent-A"), anyLong());
    }

    @Test
    @DisplayName("close 后队列为空，不触发任何分配")
    void close_noQueueDrain_whenQueueEmpty() throws Exception {
        SessionQueueItem active = new SessionQueueItem(
                "sess-only", "v1", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
        when(queueRepository.findById("sess-only")).thenReturn(java.util.Optional.of(active));
        when(queueRepository.findAll()).thenReturn(List.of()); // 无 WAITING
        when(agentRegistry.isOnline("agent-A")).thenReturn(true);

        service.close("sess-only", "agent");
        Thread.sleep(200);

        verify(queueRepository, never()).compareAndSetStatus(any(), any());
    }

    @Test
    @DisplayName("registerAgent 后 WAITING 会话被自动分配（仅一次，其余 drain 因 CAS 失败跳过）")
    void registerAgent_triggersQueueDrain() throws Exception {
        SessionQueueItem waiting = new SessionQueueItem(
                "sess-wait", "v2", "", "", 1000L, SessionStatus.WAITING, null);
        when(queueRepository.findAll()).thenReturn(List.of(waiting));
        when(agentRegistry.isOnline("agent-new")).thenReturn(true);
        // 第一次 CAS 成功（分配给客服），后续 CAS 失败（已被分配）
        when(queueRepository.compareAndSetStatus(eq("sess-wait"), any()))
                .thenReturn(true)
                .thenReturn(false);

        service.registerAgent("agent-new", "NewAgent");
        Thread.sleep(300); // registerAgent 触发 maxSessions 次 async drain

        // 只应分配一次
        verify(publisher, times(1)).publishSessionAccept(eq("sess-wait"), eq("agent-new"), anyLong());
    }

    @Test
    @DisplayName("CAS 失败（已被手动接入）时静默跳过")
    void drain_casFailure_silentlyIgnored() throws Exception {
        SessionQueueItem waiting = new SessionQueueItem(
                "sess-race", "v", "", "", 0L, SessionStatus.WAITING, null);
        when(queueRepository.findAll()).thenReturn(List.of(waiting));
        when(agentRegistry.isOnline("agent-A")).thenReturn(true);
        // CAS 失败：模拟并发手动接入
        when(queueRepository.compareAndSetStatus(any(), any())).thenReturn(false);

        service.registerAgent("agent-A", "Alice");
        Thread.sleep(200);

        // SESSION_ACCEPT 不应发布
        verify(publisher, never()).publishSessionAccept(any(), any(), anyLong());
    }
}
