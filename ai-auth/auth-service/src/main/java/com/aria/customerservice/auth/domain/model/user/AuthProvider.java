package com.aria.auth.domain.model.user;

/**
 * 账号来源（认证方式）。
 */
public enum AuthProvider {
    LOCAL,
    LDAP,
    OAUTH2_FEISHU,
    OAUTH2_DINGTALK,
    OAUTH2_WECOM,
    SAML_OKTA
}
