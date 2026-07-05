package com.aria.auth.infrastructure.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSO Cookie 写入器。
 *
 * <p>Cookie 通过 Set-Cookie 响应头手动构建，添加 {@code SameSite=Strict} 属性：
 * <ul>
 *   <li>防止 CSRF：跨站请求不会携带此 Cookie</li>
 *   <li>Servlet API 4 不支持 {@code setSameSite}，需手动拼接响应头</li>
 * </ul>
 */
@Component
public class SsoCookieWriter {

    private final String domain;
    private final boolean secure;

    public SsoCookieWriter(
            @Value("${sa-token.cookie-domain:}") String domain,
            @Value("${sa-token.is-secure:false}") boolean secure) {
        this.domain = domain;
        this.secure = secure;
    }

    /**
     * 写入 Token Cookie，携带 HttpOnly + SameSite=Strict（防 CSRF）。
     */
    public void writeTokenCookie(HttpServletResponse response,
                                 String tokenName, String tokenValue, int maxAge) {
        response.addHeader("Set-Cookie", buildCookieHeader(tokenName, tokenValue, maxAge));
    }

    /**
     * 清除 Token Cookie（maxAge=0），保持与写入时相同的 Secure 设置，
     * 确保浏览器能正确清除 Secure Cookie。
     */
    public void clearTokenCookie(HttpServletResponse response, String tokenName) {
        response.addHeader("Set-Cookie", buildCookieHeader(tokenName, "", 0));
    }

    // ---- 内部工具 ----

    private String buildCookieHeader(String name, String value, int maxAge) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value);
        sb.append("; Path=/");
        sb.append("; MaxAge=").append(maxAge);
        sb.append("; HttpOnly");
        sb.append("; SameSite=Strict");
        if (secure) sb.append("; Secure");
        if (domain != null && !domain.isBlank()) sb.append("; Domain=").append(domain);
        return sb.toString();
    }
}
