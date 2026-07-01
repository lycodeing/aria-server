package com.aria.auth.application.result;

/**
 * Token 刷新结果（强类型，替代 Map&lt;String, Object&gt;）。
 *
 * @author aria
 */
public class TokenRefreshResult {

    private final String tokenValue;
    private final long expiresIn;

    public TokenRefreshResult(String tokenValue, long expiresIn) {
        this.tokenValue = tokenValue;
        this.expiresIn = expiresIn;
    }

    public String getTokenValue() { return tokenValue; }
    public long getExpiresIn() { return expiresIn; }
}
