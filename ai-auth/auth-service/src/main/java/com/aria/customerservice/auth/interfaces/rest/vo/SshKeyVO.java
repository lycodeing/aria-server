package com.aria.customerservice.auth.interfaces.rest.vo;

/**
 * SSH Key VO。
 */
public record SshKeyVO(long id, String title, String fingerprint, String createdAt) {}
