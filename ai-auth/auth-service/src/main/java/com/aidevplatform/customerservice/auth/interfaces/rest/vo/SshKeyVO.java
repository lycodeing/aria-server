package com.aidevplatform.customerservice.auth.interfaces.rest.vo;

/**
 * SSH Key VO。
 */
public record SshKeyVO(long id, String title, String fingerprint, String createdAt) {}
