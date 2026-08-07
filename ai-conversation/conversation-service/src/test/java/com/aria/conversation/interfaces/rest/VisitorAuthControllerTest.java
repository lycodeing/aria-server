package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.SessionOwnershipValidator;
import com.aria.conversation.application.service.VisitorAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorAuthControllerTest {

    @Mock VisitorAuthService visitorAuthService;
    @Mock SessionOwnershipValidator sessionOwnershipValidator;
    @InjectMocks VisitorAuthController controller;

    @BeforeEach
    void stubOwnership() {
        // 归属校验默认放行，聚焦 state 状态回查逻辑；非法 sessionId 用例在校验前已短路，故用 lenient
        lenient().when(sessionOwnershipValidator.isAnonymousOwner(anyString(), any())).thenReturn(true);
    }

    // ------------------ state ------------------

    @Test
    void state_invalidSessionId_returns400() {
        R<Map<String, Object>> r = controller.state("bad session!", null);
        assertThat(r.code()).isEqualTo(400);
        verify(visitorAuthService, never()).resolveSessionPhone(anyString());
    }

    @Test
    void state_notOwner_returns403() {
        when(sessionOwnershipValidator.isAnonymousOwner("sess_other", "anon_x")).thenReturn(false);

        R<Map<String, Object>> r = controller.state("sess_other", "anon_x");

        assertThat(r.code()).isEqualTo(403);
        verify(visitorAuthService, never()).resolveSessionPhone(anyString());
    }

    @Test
    void state_authenticated_returnsMaskedPhone() {
        when(visitorAuthService.resolveSessionPhone("sess_ok"))
                .thenReturn(Optional.of("13812345678"));

        R<Map<String, Object>> r = controller.state("sess_ok", "anon_ok");

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).containsEntry("authenticated", true);
        assertThat(r.data()).containsEntry("phoneMask", "138****5678");
    }

    @Test
    void state_unauthenticated_returnsFalse() {
        when(visitorAuthService.resolveSessionPhone("sess_new"))
                .thenReturn(Optional.empty());

        R<Map<String, Object>> r = controller.state("sess_new", "anon_new");

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

        R<Map<String, String>> r = controller.verify(req, "anon_owner");

        assertThat(r.data()).containsEntry("token", "tk_abc");
        verify(visitorAuthService).verifyCode("13812345678", "123456", "sess_bind");
    }

    @Test
    void verify_bindSessionNotOwner_returns403AndSkipsService() {
        VisitorAuthController.VerifyCodeRequest req = new VisitorAuthController.VerifyCodeRequest();
        req.setPhone("13812345678");
        req.setCode("123456");
        req.setSessionId("sess_victim");
        // 攻击者用自己的 anonymousId 试图把绑定写到受害者会话
        when(sessionOwnershipValidator.isAnonymousOwner("sess_victim", "anon_attacker"))
                .thenReturn(false);

        R<Map<String, String>> r = controller.verify(req, "anon_attacker");

        assertThat(r.code()).isEqualTo(403);
        verify(visitorAuthService, never()).verifyCode(anyString(), anyString(), anyString());
    }

    @Test
    void verify_noSessionId_skipsOwnershipGuardAndIssuesToken() {
        // 纯 token 场景：不传 sessionId，不涉及会话绑定，跳过归属守卫直接签发 token
        VisitorAuthController.VerifyCodeRequest req = new VisitorAuthController.VerifyCodeRequest();
        req.setPhone("13812345678");
        req.setCode("123456");
        // sessionId 保持 null
        when(visitorAuthService.verifyCode("13812345678", "123456", null))
                .thenReturn("tk_pure");

        R<Map<String, String>> r = controller.verify(req, null);

        assertThat(r.data()).containsEntry("token", "tk_pure");
        verify(visitorAuthService).verifyCode("13812345678", "123456", null);
        // 未传 sessionId 时不应触发归属校验
        verify(sessionOwnershipValidator, never()).isAnonymousOwner(anyString(), any());
    }

    // ------------------ send ------------------

    @Test
    void send_delegatesToService() {
        VisitorAuthController.SendCodeRequest req = new VisitorAuthController.SendCodeRequest();
        req.setPhone("13812345678");
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4");

        controller.send(req, request);

        // 服务层按 (phone, clientIp) 限流；此处校验 IP 已透传
        verify(visitorAuthService).sendCode("13812345678", "1.2.3.4");
    }
}
