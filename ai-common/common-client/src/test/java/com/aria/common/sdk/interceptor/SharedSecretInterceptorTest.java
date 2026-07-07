package com.aria.common.sdk.interceptor;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SharedSecretInterceptor} 单元测试。
 * 覆盖：请求头附加、空密钥快速失败、拦截器覆盖调用方设置的头。
 *
 * @author lycodeing
 * @since 2026-07
 */
class SharedSecretInterceptorTest {

    private MockWebServer server;

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
    @DisplayName("附加 X-Internal-Secret 请求头")
    void shouldAppendXInternalSecretHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new SharedSecretInterceptor("s3cret-value"))
                .build();
        client.newCall(new Request.Builder().url(server.url("/ping")).build()).execute().close();

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("s3cret-value");
    }

    @Test
    @DisplayName("空密钥应立即抛出 IllegalArgumentException")
    void shouldThrowWhenSecretBlank() {
        assertThatThrownBy(() -> new SharedSecretInterceptor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部共享密钥不能为空");

        assertThatThrownBy(() -> new SharedSecretInterceptor(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SharedSecretInterceptor("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("覆盖调用方已设置的同名头")
    void shouldOverrideCallerSetSameHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new SharedSecretInterceptor("real-secret"))
                .build();
        // 调用方误加了错误的密钥头，拦截器应覆盖为配置值
        client.newCall(new Request.Builder()
                        .url(server.url("/x"))
                        .header("X-Internal-Secret", "wrong-value")
                        .build())
                .execute().close();

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("real-secret");
    }
}
