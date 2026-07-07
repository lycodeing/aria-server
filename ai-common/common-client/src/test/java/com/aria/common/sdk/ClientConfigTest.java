package com.aria.common.sdk;

import com.aria.common.sdk.auth.AuthMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClientConfig} 构建与鉴权模式校验测试。
 *
 * @author lycodeing
 * @since 2026-07
 */
class ClientConfigTest {

    @Test
    @DisplayName("默认鉴权模式为 AK_SK")
    void shouldDefaultToAkSk() {
        ClientConfig cfg = ClientConfig.builder()
                .baseUrl("http://svc")
                .accessKey("ak", "sk")
                .build();
        assertThat(cfg.getAuthMode()).isEqualTo(AuthMode.AK_SK);
        assertThat(cfg.getAccessKey()).isEqualTo("ak");
        assertThat(cfg.getSecretKey()).isEqualTo("sk");
    }

    @Test
    @DisplayName("sharedSecret 便捷方法自动切换到 SHARED_SECRET 模式")
    void shouldSwitchModeWhenSharedSecretSet() {
        ClientConfig cfg = ClientConfig.builder()
                .baseUrl("http://svc")
                .sharedSecret("top-secret")
                .build();
        assertThat(cfg.getAuthMode()).isEqualTo(AuthMode.SHARED_SECRET);
        assertThat(cfg.getSharedSecret()).isEqualTo("top-secret");
    }

    @Test
    @DisplayName("NONE 模式无需鉴权凭证")
    void shouldAllowNoneWithoutCredentials() {
        ClientConfig cfg = ClientConfig.builder()
                .baseUrl("http://svc")
                .authMode(AuthMode.NONE)
                .build();
        assertThat(cfg.getAuthMode()).isEqualTo(AuthMode.NONE);
        assertThat(cfg.getAccessKey()).isNull();
        assertThat(cfg.getSharedSecret()).isNull();
    }

    @Test
    @DisplayName("baseUrl 为空应立即抛异常")
    void shouldRejectBlankBaseUrl() {
        assertThatThrownBy(() -> ClientConfig.builder().sharedSecret("x").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    @DisplayName("AK_SK 模式缺失凭证应抛异常")
    void shouldRejectMissingAkSk() {
        assertThatThrownBy(() -> ClientConfig.builder().baseUrl("http://svc").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessKey/secretKey");
    }

    @Test
    @DisplayName("SHARED_SECRET 模式缺失密钥应抛异常")
    void shouldRejectMissingSharedSecret() {
        assertThatThrownBy(() -> ClientConfig.builder()
                .baseUrl("http://svc")
                .authMode(AuthMode.SHARED_SECRET)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharedSecret");
    }

    @Test
    @DisplayName("url() 拼接路径边界组合")
    void shouldNormalizeUrlEdgeCases() {
        ClientConfig cfg = ClientConfig.builder()
                .baseUrl("http://svc")
                .sharedSecret("x")
                .build();
        assertThat(cfg.url("/api")).isEqualTo("http://svc/api");
        assertThat(cfg.url("api")).isEqualTo("http://svc/api");

        ClientConfig cfg2 = ClientConfig.builder()
                .baseUrl("http://svc/")
                .sharedSecret("x")
                .build();
        assertThat(cfg2.url("/api")).isEqualTo("http://svc/api");
        assertThat(cfg2.url("api")).isEqualTo("http://svc/api");
    }
}
