package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.domain.CsatChannel;
import com.aria.conversation.domain.CsatStatus;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.csat.CsatRatingMapper;
import com.aria.conversation.infrastructure.webhook.WebhookEventContext;
import com.aria.conversation.infrastructure.webhook.WebhookEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsatServiceTest {

    @Mock CsatRatingMapper mapper;
    @Mock WebhookEventPublisher webhookEventPublisher;
    @InjectMocks CsatService service;

    @Test
    void createInvitation_idempotent_returnsExisting() {
        CsatRatingDO existing = new CsatRatingDO();
        existing.setId(1L); existing.setStatus(CsatStatus.PENDING);
        when(mapper.findBySessionId("sess1")).thenReturn(Optional.of(existing));

        CsatRatingDO result = service.createInvitation("sess1", "v1", null, CsatChannel.AI);

        assertThat(result.getId()).isEqualTo(1L);
        verify(mapper, never()).insert(any(CsatRatingDO.class));
    }

    @Test
    void createInvitation_new_insertsRecord() {
        CsatRatingDO created = new CsatRatingDO();
        created.setId(2L);
        created.setSessionId("sess2");
        created.setChannel(CsatChannel.HUMAN);
        created.setAgentId(99L);
        created.setStatus(CsatStatus.PENDING);
        // 首次检查不存在走创建分支；回查返回插入后的记录
        when(mapper.findBySessionId("sess2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        when(mapper.insertIfAbsent(any(CsatRatingDO.class))).thenReturn(1);

        CsatRatingDO result = service.createInvitation("sess2", "v2", 99L, CsatChannel.HUMAN);

        assertThat(result.getId()).isEqualTo(2L);
        assertThat(result.getStatus()).isEqualTo(CsatStatus.PENDING);
        assertThat(result.getChannel()).isEqualTo(CsatChannel.HUMAN);
        assertThat(result.getAgentId()).isEqualTo(99L);
        verify(mapper).insertIfAbsent(any(CsatRatingDO.class));
    }

    @Test
    void rate_invalidScore_throwsBusinessException() {
        // score validation happens before any mapper call, so no stub needed
        assertThatThrownBy(() -> service.rate(2L, (short) 6, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("评分");
    }

    @Test
    void rate_alreadyRated_throwsBusinessException() {
        CsatRatingDO rated = new CsatRatingDO();
        rated.setId(3L); rated.setStatus(CsatStatus.RATED);
        when(mapper.selectById(3L)).thenReturn(rated);

        assertThatThrownBy(() -> service.rate(3L, (short) 5, "好"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已评价");
    }

    @Test
    void rate_success_marksRatedAndPublishesWebhook() {
        CsatRatingDO pending = new CsatRatingDO();
        pending.setId(4L);
        pending.setSessionId("sess4");
        pending.setChannel(CsatChannel.AI);
        pending.setStatus(CsatStatus.PENDING);
        when(mapper.selectById(4L)).thenReturn(pending);

        service.rate(4L, (short) 5, "很好");

        assertThat(pending.getStatus()).isEqualTo(CsatStatus.RATED);
        assertThat(pending.getScore()).isEqualTo((short) 5);
        verify(mapper).updateById(pending);
        verify(webhookEventPublisher).publish(
                eq(WebhookScope.CSAT_RATED), any(WebhookEventContext.class));
    }

    @Test
    void expirePending_callsBatchExpire() {
        CsatRatingDO r1 = new CsatRatingDO(); r1.setId(10L);
        CsatRatingDO r2 = new CsatRatingDO(); r2.setId(11L);
        when(mapper.findPendingExpired()).thenReturn(List.of(r1, r2));

        int count = service.expirePending();

        assertThat(count).isEqualTo(2);
        verify(mapper).batchExpire(List.of(10L, 11L));
    }
}
