package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisitorSessionServiceTest {

    @Mock ConversationPersistRepository persistRepository;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    VisitorSessionService service;

    @BeforeEach
    void setUp() {
        service = new VisitorSessionService(persistRepository, redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        doNothing().when(rLock).lock(anyLong(), any(TimeUnit.class));
        doNothing().when(rLock).unlock();
    }

    @Test
    void getOrCreate_existingActiveSession_returnsExistingSession() {
        ConversationEntity existing = new ConversationEntity();
        existing.setSessionId("guest-existingsess");
        existing.setStatus(SessionStatus.AI_CHAT);
        when(persistRepository.findActiveByVisitorId("v_abc")).thenReturn(Optional.of(existing));

        InitSessionResult result = service.getOrCreate("v_abc", "张三", "1.2.3.4", "Mozilla/5.0");

        assertThat(result.sessionId()).isEqualTo("guest-existingsess");
        assertThat(result.status()).isEqualTo(SessionStatus.AI_CHAT);
        assertThat(result.isNew()).isFalse();
        verify(persistRepository, never()).createAiChatSession(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getOrCreate_noActiveSession_createsNewSession() {
        when(persistRepository.findActiveByVisitorId("v_new")).thenReturn(Optional.empty());

        InitSessionResult result = service.getOrCreate("v_new", null, "1.2.3.4", "Mozilla/5.0");

        assertThat(result.sessionId()).startsWith("guest-");
        assertThat(result.status()).isEqualTo(SessionStatus.AI_CHAT);
        assertThat(result.isNew()).isTrue();
        verify(persistRepository).createAiChatSession(
                eq(result.sessionId()), eq("v_new"), eq("访客"),
                eq("1.2.3.4"), eq("Mozilla/5.0"), any());
    }

    @Test
    void getOrCreate_invalidAnonymousId_tooShort_throwsBusinessException() {
        assertThatThrownBy(() -> service.getOrCreate("short", "访客", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getOrCreate_invalidAnonymousId_illegalChars_throwsBusinessException() {
        assertThatThrownBy(() -> service.getOrCreate("invalid id!", "访客", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getOrCreate_visitorNameNull_defaultsToGuestName() {
        when(persistRepository.findActiveByVisitorId("v_noname")).thenReturn(Optional.empty());

        InitSessionResult result = service.getOrCreate("v_noname", null, null, null);

        verify(persistRepository).createAiChatSession(
                any(), eq("v_noname"), eq("访客"), isNull(), isNull(), any());
    }
}
