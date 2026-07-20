package com.aria.common.sdk;

import com.aria.common.sdk.auth.AuthMode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link BaseClient} 按 {@link AuthMode} 装配鉴权拦截器的正确性。
 *
 * @author lycodeing
 * @since 2026-07
 */
class BaseClientAuthModeTest {

    private MockWebServer server;

    /** 测试专用最小 BaseClient 子类，仅暴露 GET 方法。 */
    static class TestClient extends BaseClient {
        TestClient(ClientConfig config) { super(config); }
        String hit(String path) { return get(path, String.class); }
        int callTimeoutMillis() { return httpClient.callTimeoutMillis(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("callTimeout 限制整个 HTTP 调用时长")
    void callTimeoutLimitsEntireHttpCall() {
        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .authMode(AuthMode.NONE)
                .callTimeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();

        assertThat(new TestClient(cfg).callTimeoutMillis()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("SHARED_SECRET 模式仅附加共享密钥头")
    void sharedSecretModeAttachesOnlyInternalSecretHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));

        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .sharedSecret("shared-x")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        new TestClient(cfg).hit("/ping");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getHeader("X-Internal-Secret")).isEqualTo("shared-x");
        assertThat(req.getHeader("X-Access-Key")).isNull();
        assertThat(req.getHeader("X-Signature")).isNull();
    }

    @Test
    @DisplayName("AK_SK 模式装配签名拦截器且不带内部密钥头")
    void akSkModeAttachesSigningHeadersOnly() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));

        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .accessKey("ak-1", "sk-1")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        new TestClient(cfg).hit("/ping");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getHeader("X-Access-Key")).isEqualTo("ak-1");
        assertThat(req.getHeader("X-Signature")).isNotBlank();
        assertThat(req.getHeader("X-Internal-Secret")).isNull();
    }

    @Test
    @DisplayName("NONE 模式不装配任何鉴权头")
    void noneModeAttachesNoAuthHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));

        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .authMode(AuthMode.NONE)
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        new TestClient(cfg).hit("/ping");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getHeader("X-Access-Key")).isNull();
        assertThat(req.getHeader("X-Signature")).isNull();
        assertThat(req.getHeader("X-Internal-Secret")).isNull();
    }
}
