package com.aria.conversation.application.service;

import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionQueueService.getAgentId()")
class SessionQueueServiceGetAgentIdTest {

    @Mock SessionQueueRepository queueRepository;

    // SessionQueueService 依赖较多，使用反射注入最小化 Mock
    private SessionQueueService service;

    @BeforeEach
    void setUp() throws Exception {
        // 只注入 queueRepository，其余依赖传 null（getAgentId 只用 queueRepository）
        // 构造器参数顺序：queueRepository, agentRegistry, publisher, rabbitTemplate, eventsExchange, persistRepository, csatService, visitorNotifier
        service = new SessionQueueService(
                queueRepository, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("ACTIVE 会话返回 agentId")
    void active_session_returns_agentId() {
        SessionQueueItem item = new SessionQueueItem(
                "sess-001", "Alice", "咨询", "产品", 0L, SessionStatus.ACTIVE, "agent-001");
        when(queueRepository.findById("sess-001")).thenReturn(Optional.of(item));

        assertThat(service.getAgentId("sess-001")).isEqualTo("agent-001");
    }

    @Test
    @DisplayName("WAITING 会话（agentId 为 null）返回 null")
    void waiting_session_returns_null() {
        SessionQueueItem item = new SessionQueueItem(
                "sess-002", "Bob", "咨询", "产品", 0L, SessionStatus.WAITING, null);
        when(queueRepository.findById("sess-002")).thenReturn(Optional.of(item));

        assertThat(service.getAgentId("sess-002")).isNull();
    }

    @Test
    @DisplayName("会话不存在返回 null")
    void missing_session_returns_null() {
        when(queueRepository.findById("sess-999")).thenReturn(Optional.empty());

        assertThat(service.getAgentId("sess-999")).isNull();
    }
}
