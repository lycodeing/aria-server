package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.VisitorHistoryDTO;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorHistoryServiceTest {

    @Mock ConversationPersistRepository persistRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks VisitorHistoryService service;

    @Test
    void getVisitorHistory_hasHistory_returnsDtoList() {
        ConversationEntity e = new ConversationEntity();
        e.setSessionId("sess_old");
        e.setStatus(SessionStatus.CLOSED);
        e.setTag("咨询");
        e.setStartedAt(OffsetDateTime.now().minusDays(1));
        when(persistRepository.getVisitorHistoryByVisitorId("v_abc", "sess_cur", 20))
                .thenReturn(List.of(e));
        when(persistRepository.batchGetMessageCount(List.of("sess_old")))
                .thenReturn(java.util.Map.of("sess_old", 5L));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyList())).thenReturn(List.of("AI 摘要内容"));

        List<VisitorHistoryDTO> result = service.getVisitorHistory("v_abc", "sess_cur");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sessionId()).isEqualTo("sess_old");
        assertThat(result.get(0).msgCount()).isEqualTo(5);
    }

    @Test
    void getVisitorHistory_noHistory_returnsEmptyList() {
        when(persistRepository.getVisitorHistoryByVisitorId("v_new", null, 20))
                .thenReturn(List.of());

        assertThat(service.getVisitorHistory("v_new", null)).isEmpty();
    }
}
