package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CsatService;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsatControllerPendingTest {

    @Mock CsatService csatService;
    @InjectMocks CsatController controller;

    @Test
    void pending_invalidSessionId_returns400() {
        R<Map<String, Object>> r = controller.pending("bad session!");
        assertThat(r.code()).isEqualTo(400);
        verify(csatService, never()).findPending(anyString());
    }

    @Test
    void pending_noRecord_returnsNullData() {
        when(csatService.findPending("sess_new")).thenReturn(Optional.empty());

        R<Map<String, Object>> r = controller.pending("sess_new");

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).isNull();
    }

    @Test
    void pending_activeInvite_returnsPayloadMatchingSseContract() {
        CsatRatingDO invite = new CsatRatingDO();
        invite.setId(101L);
        invite.setSessionId("sess_hit");
        invite.setStatus("PENDING");
        OffsetDateTime expiredAt = OffsetDateTime.now().plusHours(20);
        invite.setExpiredAt(expiredAt);
        when(csatService.findPending("sess_hit")).thenReturn(Optional.of(invite));

        R<Map<String, Object>> r = controller.pending("sess_hit");

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).isNotNull();
        // 关键契约：字段名与 SSE csat_request 事件 payload 完全一致
        // （由 CsatInvites 集中构造，见 FaqChatAppService / SessionQueueService 使用）
        assertThat(r.data())
                .containsEntry("csatId", 101L)
                .containsEntry("sessionId", "sess_hit")
                .containsEntry("message", "请对本次服务进行评价")
                .containsEntry("expiresAt", expiredAt.toString());
    }
}
