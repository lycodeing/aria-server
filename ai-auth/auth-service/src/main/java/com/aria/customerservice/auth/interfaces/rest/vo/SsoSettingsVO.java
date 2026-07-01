package com.aria.customerservice.auth.interfaces.rest.vo;

/**
 * SSO/LDAP 配置 VO。
 */
public record SsoSettingsVO(
        String ldapUrl,
        String bindDn,
        String baseDn,
        String userFilter
) {}
