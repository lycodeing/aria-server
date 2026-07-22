package com.aria.common.web.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InternalSecretFilter} 单元测试。
 *
 * <p>验证：正确密钥放行、错误/缺失密钥 403、非 internal 路径直接放行。
 */
class InternalSecretFilterTest {

    private static final String SECRET = "test-secret-2024";

    private InternalSecretFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalSecretFilter(SECRET);
    }

    @Test
    @DisplayName("正确密钥 → 放行，filter chain 继续执行")
    void correctSecret_passes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/knowledge/search");
        req.addHeader(InternalSecretFilter.HEADER, SECRET);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();  // chain 已继续执行
    }

    @Test
    @DisplayName("错误密钥 → 403 Forbidden")
    void wrongSecret_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/ai-models/active");
        req.addHeader(InternalSecretFilter.HEADER, "wrong-secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();  // chain 未继续执行
    }

    @Test
    @DisplayName("缺少密钥头 → 403 Forbidden")
    void missingHeader_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/knowledge/search");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("非 /internal 路径 → shouldNotFilter 返回 true，直接放行")
    void nonInternalPath_skipsFilter() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/chat/send");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    @DisplayName("/internal 路径 → shouldNotFilter 返回 false，执行校验")
    void internalPath_runsFilter() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/knowledge/search");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    @Test
    @DisplayName("配置为空串时 → fail-secure，所有内部请求均被拒绝")
    void emptySecret_rejectsAll() throws Exception {
        InternalSecretFilter emptyFilter = new InternalSecretFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/knowledge/search");
        req.addHeader(InternalSecretFilter.HEADER, "any-value");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        emptyFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("matches() 使用恒定时间比较，正确时返回 true")
    void matches_correctSecret_returnsTrue() {
        assertThat(filter.matches(SECRET)).isTrue();
    }

    @Test
    @DisplayName("matches() null 输入返回 false")
    void matches_null_returnsFalse() {
        assertThat(filter.matches(null)).isFalse();
    }

    @Test
    @DisplayName("/api/v1/internal 路径 → shouldNotFilter 返回 false，执行校验")
    void apiV1InternalPath_runsFilter() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/token/verify");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    @Test
    @DisplayName("403 响应体格式与全系统一致，字段为 message 而非 msg")
    void forbidden_responseBodyUsesMessageField() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/knowledge/search");
        req.addHeader(InternalSecretFilter.HEADER, "wrong-secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"message\"");
        assertThat(res.getContentAsString()).doesNotContain("\"msg\"");
    }
}
