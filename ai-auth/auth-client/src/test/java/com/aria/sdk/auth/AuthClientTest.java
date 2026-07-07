package com.aria.sdk.auth;

import com.aria.sdk.auth.exception.AuthClientException;
import com.aria.sdk.auth.model.AiModelConfigDTO;
import com.aria.sdk.auth.model.ModelScope;
import com.aria.sdk.auth.token.TokenVerifyResult;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthClient} 端到端行为测试（基于 MockWebServer）。
 *
 * @author lycodeing
 * @since 2026-07
 */
class AuthClientTest {

    private MockWebServer server;
    private AuthClient client;

    private static final String SECRET = "test-internal-secret";

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = AuthClient.builder()
                .baseUrl(server.url("/").toString())
                .sharedSecret(SECRET)
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("CHAT 作用域调用 /active 路径并附带内部密钥头")
    void chatScopeCallsActivePathWithSecretHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"code":200,"msg":"success","data":{
                            "id":1,"name":"gpt-4o","provider":"openai","apiProtocol":"OPENAI_COMPATIBLE",
                            "baseUrl":"https://api.openai.com","apiKey":"sk-xxx","modelName":"gpt-4o",
                            "temperature":0.7,"maxTokens":2048,"timeoutSec":60
                        }}"""));

        AiModelConfigDTO cfg = client.getActiveModel(ModelScope.CHAT);

        assertThat(cfg.id()).isEqualTo(1L);
        assertThat(cfg.modelName()).isEqualTo("gpt-4o");
        assertThat(cfg.apiKey()).isEqualTo("sk-xxx");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/internal/ai-models/active");
        assertThat(req.getHeader("X-Internal-Secret")).isEqualTo(SECRET);
        assertThat(req.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("EMBEDDING 作用域调用 /active-embedding 路径")
    void embeddingScopeCallsActiveEmbeddingPath() throws Exception {
        server.enqueue(new MockResponse().setBody(minimalOkBody("bge-m3")));

        client.getActiveModel(ModelScope.EMBEDDING);

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/internal/ai-models/active-embedding");
    }

    @Test
    @DisplayName("ROUTER 作用域调用 /active-router 路径")
    void routerScopeCallsActiveRouterPath() throws Exception {
        server.enqueue(new MockResponse().setBody(minimalOkBody("router-mini")));

        client.getActiveModel(ModelScope.ROUTER);

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/internal/ai-models/active-router");
    }

    @Test
    @DisplayName("服务端返回业务错误码时抛 AuthClientException 且携带 bizCode")
    void bizErrorCarriesBizCode() {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":404,\"msg\":\"未找到激活模型\",\"data\":null}"));

        assertThatThrownBy(() -> client.getActiveModel(ModelScope.CHAT))
                .isInstanceOfSatisfying(AuthClientException.class, ex -> {
                    assertThat(ex.getBizCode()).isEqualTo(404);
                    assertThat(ex.getHttpStatus()).isEqualTo(AuthClientException.UNKNOWN_CODE);
                    assertThat(ex.getMessage()).contains("code=404").contains("未找到激活模型");
                });
    }

    @Test
    @DisplayName("HTTP 500 时抛 AuthClientException 且透传 httpStatus")
    void httpErrorCarriesHttpStatus() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client.getActiveModel(ModelScope.CHAT))
                .isInstanceOfSatisfying(AuthClientException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(500);
                    assertThat(ex.getBizCode()).isEqualTo(AuthClientException.UNKNOWN_CODE);
                    assertThat(ex.getMessage()).contains("拉取激活模型配置失败");
                    assertThat(ex.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("data 为空应抛异常")
    void dataMissingThrows() {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":200,\"msg\":\"success\",\"data\":null}"));

        assertThatThrownBy(() -> client.getActiveModel(ModelScope.CHAT))
                .isInstanceOf(AuthClientException.class)
                .hasMessageContaining("data 为空");
    }

    @Test
    @DisplayName("verifyToken 成功返回结果且请求体正确")
    void verifyTokenSuccess() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":200,\"msg\":\"success\",\"data\":{\"valid\":true,\"userId\":\"u1\"}}"));

        TokenVerifyResult result = client.verifyToken("bearer-token-x");

        assertThat(result.valid()).isTrue();
        assertThat(result.userId()).isEqualTo("u1");

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/api/v1/internal/token/verify");
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getBody().readUtf8()).contains("bearer-token-x");
        assertThat(req.getHeader("X-Internal-Secret")).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("参数为空时抛 IllegalArgumentException")
    void paramValidation() {
        assertThatThrownBy(() -> client.getActiveModel(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.verifyToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.verifyToken(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("builder 未设置 sharedSecret 应快速失败")
    void builderFailsFastWhenSharedSecretMissing() {
        assertThatThrownBy(() -> AuthClient.builder()
                .baseUrl("http://x")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AiModelConfigDTO#toString 屏蔽 apiKey 字段")
    void dtoToStringMasksApiKey() {
        AiModelConfigDTO dto = new AiModelConfigDTO(
                1L, "n", "p", "OPENAI_COMPATIBLE", "http://x",
                "sk-should-not-leak", "m", 0.5D, 128, 30);
        assertThat(dto.toString()).doesNotContain("sk-should-not-leak").contains("apiKey='***'");
    }

    // ---- 工具 ----

    private static String minimalOkBody(String modelName) {
        return "{\"code\":200,\"msg\":\"success\",\"data\":{"
                + "\"id\":2,\"name\":\"" + modelName + "\",\"provider\":\"x\","
                + "\"apiProtocol\":\"OPENAI_COMPATIBLE\",\"baseUrl\":\"http://x\","
                + "\"apiKey\":\"k\",\"modelName\":\"" + modelName + "\","
                + "\"temperature\":0.0,\"maxTokens\":0,\"timeoutSec\":30"
                + "}}";
    }
}
