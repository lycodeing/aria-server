package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.csat.CsatRatingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsatServiceTest {

    @Mock CsatRatingMapper mapper;
    @InjectMocks CsatService service;

    @Test
    void createInvitation_idempotent_returnsExisting() {
        CsatRatingDO existing = new CsatRatingDO();
        existing.setId(1L); existing.setStatus("PENDING");
        when(mapper.findBySessionId("sess1")).thenReturn(Optional.of(existing));

        CsatRatingDO result = service.createInvitation("sess1", "v1", null, "AI");

        assertThat(result.getId()).isEqualTo(1L);
        verify(mapper, never()).insert(any(CsatRatingDO.class));
    }

    @Test
    void createInvitation_new_insertsRecord() {
        when(mapper.findBySessionId("sess2")).thenReturn(Optional.empty());
        when(mapper.insert(any(CsatRatingDO.class))).thenReturn(1);

        CsatRatingDO result = service.createInvitation("sess2", "v2", 99L, "HUMAN");

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getChannel()).isEqualTo("HUMAN");
        assertThat(result.getAgentId()).isEqualTo(99L);
        verify(mapper).insert(any(CsatRatingDO.class));
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
        rated.setId(3L); rated.setStatus("RATED");
        when(mapper.selectById(3L)).thenReturn(rated);

        assertThatThrownBy(() -> service.rate(3L, (short) 5, "好"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已评价");
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
