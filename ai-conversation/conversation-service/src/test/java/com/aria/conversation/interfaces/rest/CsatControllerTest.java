package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.service.CsatService;
import com.aria.conversation.application.service.SessionOwnershipValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsatControllerTest {

    @Mock CsatService service;
    @Mock SessionOwnershipValidator sessionOwnershipValidator;
    @InjectMocks CsatController controller;

    @Test
    void rate_delegatesToService() {
        CsatController.RateRequest req = new CsatController.RateRequest();
        req.setScore((short) 5);
        req.setComment("很满意");
        // 归属校验：csatId 42 → sess_a，且当前访客为归属者
        when(service.findSessionIdByCsatId(42L)).thenReturn(Optional.of("sess_a"));
        when(sessionOwnershipValidator.isOwner("sess_a", null, "anon_a")).thenReturn(true);

        controller.rate(42L, req, null, "anon_a");

        verify(service).rate(42L, (short) 5, "很满意");
    }

    @Test
    void skip_delegatesToService() {
        when(service.findSessionIdByCsatId(7L)).thenReturn(Optional.of("sess_a"));
        when(sessionOwnershipValidator.isOwner("sess_a", null, "anon_a")).thenReturn(true);

        controller.skip(7L, null, "anon_a");

        verify(service).skip(7L);
    }

    @Test
    void rate_notOwner_returns403AndSkipsService() {
        CsatController.RateRequest req = new CsatController.RateRequest();
        req.setScore((short) 5);
        // csatId 归属他人：反查到 sessionId 但归属校验失败
        when(service.findSessionIdByCsatId(99L)).thenReturn(Optional.of("sess_other"));
        when(sessionOwnershipValidator.isOwner("sess_other", null, "anon_attacker")).thenReturn(false);

        var r = controller.rate(99L, req, null, "anon_attacker");

        assertThat(r.code()).isEqualTo(403);
        verify(service, never()).rate(anyLong(), any(), any());
    }
}
