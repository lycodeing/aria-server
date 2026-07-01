package com.aria.auth.infrastructure.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SsoCookieWriter {
    private final String domain;
    private final boolean secure;

    public SsoCookieWriter(@Value("${sa-token.cookie-domain:}") String domain,
                           @Value("${sa-token.is-secure:false}") boolean secure) {
        this.domain = domain; this.secure = secure;
    }

    public void writeTokenCookie(HttpServletResponse response, String tokenName, String tokenValue, int maxAge) {
        Cookie cookie = new Cookie(tokenName, tokenValue);
        if (domain != null && !domain.isBlank()) cookie.setDomain(domain);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        response.addCookie(cookie);
    }

    public void clearTokenCookie(HttpServletResponse response, String tokenName) {
        Cookie cookie = new Cookie(tokenName, "");
        if (domain != null && !domain.isBlank()) cookie.setDomain(domain);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
