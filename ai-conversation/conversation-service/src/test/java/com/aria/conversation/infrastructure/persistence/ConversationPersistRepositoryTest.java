package com.aria.conversation.infrastructure.persistence;

import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationPersistRepositoryTest {

    @Mock ConversationMapper conversationMapper;
    @Mock ConversationMessageMapper conversationMessageMapper;
    @InjectMocks ConversationPersistRepository repository;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus lambda cache must be initialized before LambdaUpdateWrapper.set() is called
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            ConversationEntity.class
        );
    }

    @Test
    void findActiveByVisitorId_found_returnsEntity() {
        ConversationEntity entity = new ConversationEntity();
        entity.setSessionId("sess_1");
        entity.setStatus(SessionStatus.AI_CHAT);
        when(conversationMapper.selectActiveByVisitorId("v_abc")).thenReturn(List.of(entity));

        Optional<ConversationEntity> result = repository.findActiveByVisitorId("v_abc");

        assertThat(result).isPresent();
        assertThat(result.get().getSessionId()).isEqualTo("sess_1");
    }

    @Test
    void findActiveByVisitorId_notFound_returnsEmpty() {
        when(conversationMapper.selectActiveByVisitorId("v_new")).thenReturn(List.of());

        assertThat(repository.findActiveByVisitorId("v_new")).isEmpty();
    }

    @Test
    void createAiChatSession_insertsCorrectEntity() {
        OffsetDateTime now = OffsetDateTime.now();

        repository.createAiChatSession("sess_1", "v_abc", "张三", "1.2.3.4",
                "Mozilla/5.0", now);

        ArgumentCaptor<ConversationEntity> cap = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).insert(cap.capture());
        ConversationEntity saved = cap.getValue();
        assertThat(saved.getSessionId()).isEqualTo("sess_1");
        assertThat(saved.getVisitorId()).isEqualTo("v_abc");
        assertThat(saved.getVisitorIp()).isEqualTo("1.2.3.4");
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.AI_CHAT);
    }

    @Test
    void upgradeToWaiting_rowsUpdated_returnsCount() {
        when(conversationMapper.update(any())).thenReturn(1);

        int rows = repository.upgradeToWaiting("sess_1", "访客", "需要帮助", "咨询",
                OffsetDateTime.now());

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void upgradeToWaiting_noRowsUpdated_logsWarnAndReturnsZero() {
        when(conversationMapper.update(any())).thenReturn(0);

        int rows = repository.upgradeToWaiting("sess_missing", "访客", "", "咨询",
                OffsetDateTime.now());

        assertThat(rows).isZero();
        // warn 日志由 Slf4j 打印，此处只验证返回值不抛异常
    }

    @Test
    void existsBySessionId_exists_returnsTrue() {
        when(conversationMapper.exists(any())).thenReturn(true);

        assertThat(repository.existsBySessionId("sess_1")).isTrue();
    }
}
