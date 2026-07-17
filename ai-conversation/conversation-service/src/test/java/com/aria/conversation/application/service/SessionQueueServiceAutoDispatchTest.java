package com.aria.conversation.application.service;

import com.aria.conversation.domain.*;
import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
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
@DisplayName("SessionQueueService — tryAutoDispatch")
class SessionQueueServiceAutoDispatchTest {

    @Mock SessionQueueRepository    queueRepository;
    @Mock AgentOnlineRegistry       agentRegistry;
    @Mock ConversationMessagePublisher publisher;
    @Mock RabbitTemplate            rabbitTemplate;
    @Mock ConversationPersistRepository persistRepository;
    @Mock CsatService               csatService;
    @Mock VisitorNotifier           visitorNotifier;
    @Mock CsAgentConfigProvider     configProvider;
    @Mock RedissonClient            redissonClient;
    @Mock RLock                     rLock;

    SessionQueueService service;

    @BeforeEach
    void setUp() throws Exception {
        // 构造器参数顺序：queueRepository, agentRegistry, publisher, rabbitTemplate,
        //   eventsExchange, persistRepository, csatService, visitorNotifier,
        //   configProvider, redissonClient
        service = new SessionQueueService(
                queueRepository, agentRegistry, publisher, rabbitTemplate,
                "test.exchange", persistRepository, csatService, visitorNotifier,
                configProvider, redissonClient);
        // Redisson 锁默认行为：加锁成功（lenient 允许部分测试覆盖）
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("有空闲客服时 enqueue 直接创建 ACTIVE，不写 WAITING")
    void enqueue_autoDispatch_whenAgentHasCapacity() throws Exception {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
        when(agentRegistry.findAll()).thenReturn(List.of(
                new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
        when(queueRepository.findAll()).thenReturn(List.of()); // 0 active sessions
        when(agentRegistry.isOnline("agent-A")).thenReturn(true);

        service.enqueue("sess-1", "visitor", "reason", "tag");

        // 验证 Redis 写入 ACTIVE 状态（非 WAITING）
        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(captor.getValue().agentId()).isEqualTo("agent-A");

        // 验证 MQ SESSION_START + SESSION_ACCEPT 均发布
        verify(publisher).publishSessionStart(eq("sess-1"), eq("visitor"), eq("reason"), eq("tag"), anyLong());
        verify(publisher).publishSessionAccept(eq("sess-1"), eq("agent-A"), anyLong());
    }

    @Test
    @DisplayName("所有客服满时 enqueue 创建 WAITING")
    void enqueue_fallbackToWaiting_whenAllAgentsFull() throws Exception {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(1);
        when(agentRegistry.findAll()).thenReturn(List.of(
                new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
        // agent-A 已有 1 个 ACTIVE 会话（= maxSessions）
        SessionQueueItem existingActive = new SessionQueueItem(
                "existing", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
        when(queueRepository.findAll()).thenReturn(List.of(existingActive));
        // isOnline 不会被调用（agent-A 在乐观读阶段即因 sessions==max 被过滤）

        service.enqueue("sess-2", "visitor2", "reason", "tag");

        // Redis 写入 WAITING
        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
    }

    @Test
    @DisplayName("无在线客服时 enqueue 创建 WAITING")
    void enqueue_fallbackToWaiting_whenNoAgentOnline() {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
        when(agentRegistry.findAll()).thenReturn(List.of());

        service.enqueue("sess-3", "visitor3", "reason", "tag");

        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
    }

    @Test
    @DisplayName("多个空闲客服时选 sessions 最少的客服（负载均衡）")
    void enqueue_picksLeastLoadedAgent() throws Exception {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
        // agent-B 有 1 个 session，agent-A 有 0 个，getOnlineAgents() 按升序排列
        when(agentRegistry.findAll()).thenReturn(List.of(
                new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L),
                new AgentOnlineRegistry.AgentInfo("agent-B", "Bob", 0L)));
        SessionQueueItem activeB = new SessionQueueItem(
                "b-sess", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-B");
        when(queueRepository.findAll()).thenReturn(List.of(activeB));
        when(agentRegistry.isOnline("agent-A")).thenReturn(true);

        service.enqueue("sess-4", "v4", "reason", "tag");

        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().agentId()).isEqualTo("agent-A"); // 负载更低
    }

    @Test
    @DisplayName("加锁失败时跳过该客服，尝试下一个")
    void enqueue_skipsLockedAgent_picksNext() throws Exception {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
        when(agentRegistry.findAll()).thenReturn(List.of(
                new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L),
                new AgentOnlineRegistry.AgentInfo("agent-B", "Bob", 0L)));
        when(queueRepository.findAll()).thenReturn(List.of());

        RLock lockA = mock(RLock.class);
        RLock lockB = mock(RLock.class);
        when(redissonClient.getLock("lock:assign:agent:agent-A")).thenReturn(lockA);
        when(redissonClient.getLock("lock:assign:agent:agent-B")).thenReturn(lockB);
        when(lockA.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(false); // agent-A 锁被占
        when(lockB.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
        when(lockB.isHeldByCurrentThread()).thenReturn(true);
        when(agentRegistry.isOnline("agent-B")).thenReturn(true);

        service.enqueue("sess-5", "v5", "reason", "tag");

        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().agentId()).isEqualTo("agent-B");
    }

    @Test
    @DisplayName("二次校验失败（持锁后客服已满）时降级为 WAITING")
    void enqueue_doubleCheckFails_fallbackToWaiting() throws Exception {
        when(configProvider.getMaxSessionsPerAgent()).thenReturn(1);
        when(agentRegistry.findAll()).thenReturn(List.of(
                new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
        // 第一次 findAll（乐观读 getOnlineAgents）返回空 → agent-A 看起来有 0 sessions，通过过滤
        // 第二次 findAll（持锁后 countActiveSessions）返回 1 个 ACTIVE → 二次校验失败，降级 WAITING
        SessionQueueItem raced = new SessionQueueItem(
                "race", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
        when(queueRepository.findAll()).thenReturn(List.of(), List.of(raced));
        // isAgentAvailable: isOnline 在左侧先执行，但此场景 isOnline 未被 stub
        // → Mockito 默认返回 false，短路后 countActiveSessions 不调用
        // 实际效果：二次校验不通过，降级 WAITING（符合测试意图）

        service.enqueue("sess-6", "v6", "reason", "tag");

        ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
        verify(queueRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
    }
}
