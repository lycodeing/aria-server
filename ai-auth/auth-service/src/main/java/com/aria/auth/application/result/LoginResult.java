package com.aria.auth.application.result;

import java.util.List;

/**
 * 登录用例输出结果（强类型，替代 Map&lt;String, Object&gt;）。
 */
public class LoginResult {

    private final String tokenName;
    private final String tokenValue;
    private final long expiresIn;
    private final long userId;
    private final String username;
    private final String displayName;
    private final List<String> roles;
    private final boolean mustChangePassword;

    public LoginResult(String tokenName, String tokenValue, long expiresIn,
                       long userId, String username, String displayName,
                       List<String> roles, boolean mustChangePassword) {
        this.tokenName = tokenName;
        this.tokenValue = tokenValue;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = roles;
        this.mustChangePassword = mustChangePassword;
    }

    public String getTokenName() {
        return tokenName;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
