package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.csat.CsatRatingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsatServicePendingTest {

    @Mock CsatRatingMapper mapper;
    @InjectMocks CsatService service;

    @Test
    void findPending_noRecord_returnsEmpty() {
        when(mapper.findBySessionId("sess_none")).thenReturn(Optional.empty());
        assertThat(service.findPending("sess_none")).isEmpty();
    }

    @Test
    void findPending_ratedRecord_returnsEmpty() {
        CsatRatingDO rated = new CsatRatingDO();
        rated.setStatus("RATED");
        rated.setExpiredAt(OffsetDateTime.now().plusHours(1));
        when(mapper.findBySessionId("sess_rated")).thenReturn(Optional.of(rated));
        assertThat(service.findPending("sess_rated")).isEmpty();
    }

    @Test
    void findPending_pendingButExpired_returnsEmpty() {
        CsatRatingDO expired = new CsatRatingDO();
        expired.setStatus("PENDING");
        expired.setExpiredAt(OffsetDateTime.now().minusMinutes(1));
        when(mapper.findBySessionId("sess_exp")).thenReturn(Optional.of(expired));
        assertThat(service.findPending("sess_exp")).isEmpty();
    }

    @Test
    void findPending_pendingActive_returnsRecord() {
        CsatRatingDO active = new CsatRatingDO();
        active.setId(7L);
        active.setStatus("PENDING");
        active.setExpiredAt(OffsetDateTime.now().plusHours(23));
        when(mapper.findBySessionId("sess_ok")).thenReturn(Optional.of(active));

        Optional<CsatRatingDO> result = service.findPending("sess_ok");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(7L);
    }
}
