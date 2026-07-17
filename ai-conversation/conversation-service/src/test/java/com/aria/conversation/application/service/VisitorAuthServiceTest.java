package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.repository.VisitorCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorAuthServiceTest {

    @Mock VisitorCodeRepository codeRepository;
    @InjectMocks VisitorAuthService service;

    @BeforeEach
    void setup() {
        // @Value 字段在纯 Mockito 上下文中默认为 0，需要手动注入
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 5L);
        ReflectionTestUtils.setField(service, "rateLimitSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
    }

    // ------------------ verifyCode(phone, code) ------------------

    @Test
    void verifyCode_withValidSessionId_savesSessionAuth() {
        String phone = "13812345678";
        String sid = "sess_abc-123";
        when(codeRepository.getAttempts(phone)).thenReturn(0L);
        when(codeRepository.getCode(phone)).thenReturn(Optional.of("123456"));

        String token = service.verifyCode(phone, "123456", sid);

        assertThat(token).isNotBlank();
        verify(codeRepository).saveToken(eq(token), eq(phone), any(Duration.class));
        verify(codeRepository).saveSessionAuth(eq(sid), eq(phone), any(Duration.class));
    }

    @Test
    void verifyCode_withoutSessionId_doesNotBindSession() {
        String phone = "13812345678";
        when(codeRepository.getAttempts(phone)).thenReturn(0L);
        when(codeRepository.getCode(phone)).thenReturn(Optional.of("123456"));

        service.verifyCode(phone, "123456");  // 无 sessionId 兼容重载

        verify(codeRepository).saveToken(anyString(), eq(phone), any(Duration.class));
        verify(codeRepository, never()).saveSessionAuth(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void verifyCode_withInvalidSessionId_throwsBusinessException() {
        assertThatThrownBy(() -> service.verifyCode("13812345678", "123456", "bad session!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sessionId");
        verify(codeRepository, never()).saveToken(anyString(), anyString(), any());
    }

    @Test
    void verifyCode_wrongCode_incrementAttempts() {
        String phone = "13800000001";
        when(codeRepository.getAttempts(phone)).thenReturn(1L);
        when(codeRepository.getCode(phone)).thenReturn(Optional.of("999999"));
        when(codeRepository.incrementAttempts(eq(phone), any(Duration.class))).thenReturn(2L);

        assertThatThrownBy(() -> service.verifyCode(phone, "111111", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码错误");
        verify(codeRepository).incrementAttempts(eq(phone), any(Duration.class));
        verify(codeRepository, never()).saveToken(anyString(), anyString(), any());
    }

    @Test
    void verifyCode_locked_rejectsImmediately() {
        String phone = "13800000002";
        when(codeRepository.getAttempts(phone)).thenReturn(5L);

        assertThatThrownBy(() -> service.verifyCode(phone, "123456", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("锁定");
        verify(codeRepository, never()).getCode(anyString());
    }

    // ------------------ resolveSessionPhone ------------------

    @Test
    void resolveSessionPhone_valid_returnsPhone() {
        when(codeRepository.resolveSessionAuth("sess_ok")).thenReturn(Optional.of("13812345678"));
        assertThat(service.resolveSessionPhone("sess_ok")).contains("13812345678");
    }

    @Test
    void resolveSessionPhone_invalidFormat_returnsEmpty() {
        assertThat(service.resolveSessionPhone("bad session!")).isEmpty();
        verify(codeRepository, never()).resolveSessionAuth(anyString());
    }

    @Test
    void resolveSessionPhone_missing_returnsEmpty() {
        when(codeRepository.resolveSessionAuth("sess_missing")).thenReturn(Optional.empty());
        assertThat(service.resolveSessionPhone("sess_missing")).isEmpty();
    }

    @Test
    void resolveSessionPhone_nullOrBlank_returnsEmpty() {
        assertThat(service.resolveSessionPhone(null)).isEmpty();
        assertThat(service.resolveSessionPhone("")).isEmpty();
        assertThat(service.resolveSessionPhone("   ")).isEmpty();
    }

    // ------------------ maskPhone ------------------

    @Test
    void maskPhone_validPhone_returnsMasked() {
        assertThat(VisitorAuthService.maskPhone("13812345678")).isEqualTo("138****5678");
    }

    @Test
    void maskPhone_shortOrNull_returnsAsIs() {
        assertThat(VisitorAuthService.maskPhone(null)).isNull();
        assertThat(VisitorAuthService.maskPhone("123")).isEqualTo("123");
    }

    // ------------------ sendCode 提前校验：确保 verifyCode 逻辑改动不影响原路径 ------------------

    @Test
    void sendCode_rateLimited_throws() {
        when(codeRepository.tryAcquireRateLimit(eq("13899999999"), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.sendCode("13899999999"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发送过于频繁");
    }

    @Test
    void sendCode_success_savesCode() {
        String phone = "13800000009";
        when(codeRepository.tryAcquireRateLimit(eq(phone), anyLong())).thenReturn(true);

        service.sendCode(phone);

        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        verify(codeRepository).saveCode(eq(phone), codeCap.capture(), eq(5L));
        assertThat(codeCap.getValue()).matches("^\\d{6}$");
        verify(codeRepository).resetAttempts(phone);
    }
}
