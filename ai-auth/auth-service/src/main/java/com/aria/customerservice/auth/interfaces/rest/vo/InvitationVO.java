package com.aria.customerservice.auth.interfaces.rest.vo;

/**
 * 邀请记录 VO。
 */
public record InvitationVO(
        Long id,
        String email,
        String role,
        Boolean isExternal,
        String status,
        String token,
        String sentAt,
        String expiresAt,
        String acceptedAt,
        String createdAt
) {}
