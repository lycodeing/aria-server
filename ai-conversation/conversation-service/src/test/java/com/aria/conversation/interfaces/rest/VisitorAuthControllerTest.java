package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.VisitorAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorAuthControllerTest {

    @Mock VisitorAuthService visitorAuthService;
    @InjectMocks VisitorAuthController controller;

    // ------------------ state ------------------

    @Test
    void state_invalidSessionId_returns400() {
        R<Map<String, Object>> r = controller.state("bad session!");
        assertThat(r.code()).isEqualTo(400);
        verify(visitorAuthService, never()).resolveSessionPhone(anyString());
    }

    @Test
    void state_authenticated_returnsMaskedPhone() {
        when(visitorAuthService.resolveSessionPhone("sess_ok"))
                .thenReturn(Optional.of("13812345678"));

        R<Map<String, Object>> r = controller.state("sess_ok");

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).containsEntry("authenticated", true);
        assertThat(r.data()).containsEntry("phoneMask", "138****5678");
    }

    @Test
    void state_unauthenticated_returnsFalse() {
        when(visitorAuthService.resolveSessionPhone("sess_new"))
                .thenReturn(Optional.empty());

        R<Map<String, Object>> r = controller.state("sess_new");

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).containsEntry("authenticated", false);
        assertThat(r.data()).doesNotContainKey("phoneMask");
    }

    // ------------------ verify ------------------

    @Test
    void verify_passesSessionIdToService() {
        VisitorAuthController.VerifyCodeRequest req = new VisitorAuthController.VerifyCodeRequest();
        req.setPhone("13812345678");
        req.setCode("123456");
        req.setSessionId("sess_bind");
        when(visitorAuthService.verifyCode("13812345678", "123456", "sess_bind"))
                .thenReturn("tk_abc");

        R<Map<String, String>> r = controller.verify(req);

        assertThat(r.data()).containsEntry("token", "tk_abc");
        verify(visitorAuthService).verifyCode("13812345678", "123456", "sess_bind");
    }

    // ------------------ send ------------------

    @Test
    void send_delegatesToService() {
        VisitorAuthController.SendCodeRequest req = new VisitorAuthController.SendCodeRequest();
        req.setPhone("13812345678");

        controller.send(req);

        verify(visitorAuthService).sendCode("13812345678");
    }
}
