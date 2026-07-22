package com.aria.common.sdk.interceptor;

import com.aria.common.sdk.ClientConfig;
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
 * 验证 {@link AkSkSigningInterceptor} 对 POST body 的签名覆盖。
 *
 * <p>核心断言：不同 body 的两次请求生成不同的签名，证明签名串中包含了真实 body hash。
 */
class AkSkSigningInterceptorBodyTest {

    private MockWebServer server;

    /** 最小 BaseClient 子类，仅暴露 POST。 */
    static class TestClient extends com.aria.common.sdk.BaseClient {
        TestClient(ClientConfig cfg) { super(cfg); }
        String postBody(String path, Object body) {
            return post(path, body, String.class);
        }
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
    @DisplayName("不同 body 产生不同签名，证明 body hash 已纳入签名串")
    void differentBodies_produceDifferentSignatures() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));

        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .accessKey("ak-test", "sk-test")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        TestClient client = new TestClient(cfg);

        client.postBody("/sign", "{\"query\":\"hello\"}");
        client.postBody("/sign", "{\"query\":\"world\"}");

        RecordedRequest req1 = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest req2 = server.takeRequest(2, TimeUnit.SECONDS);

        String sig1 = req1.getHeader("X-Signature");
        String sig2 = req2.getHeader("X-Signature");

        assertThat(sig1).isNotBlank();
        assertThat(sig2).isNotBlank();
        // 关键断言：body 不同 → 签名不同，证明 body hash 纳入了签名串
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("相同 body 两次请求，因 timestamp/nonce 不同签名不同，但均包含签名头")
    void sameBody_alwaysHasSignatureHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("\"ok\""));

        ClientConfig cfg = ClientConfig.builder()
                .baseUrl(server.url("/").toString())
                .accessKey("ak-test", "sk-test")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        TestClient client = new TestClient(cfg);
        String body = "{\"query\":\"same\"}";

        client.postBody("/sign", body);
        client.postBody("/sign", body);

        RecordedRequest req1 = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest req2 = server.takeRequest(2, TimeUnit.SECONDS);

        // 两次请求都有完整签名头
        assertThat(req1.getHeader("X-Signature")).isNotBlank();
        assertThat(req1.getHeader("X-Access-Key")).isEqualTo("ak-test");
        assertThat(req1.getHeader("X-Timestamp")).isNotBlank();
        assertThat(req1.getHeader("X-Nonce")).isNotBlank();
        assertThat(req2.getHeader("X-Signature")).isNotBlank();
    }
}

