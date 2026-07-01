package com.aria.auth.interfaces.rest.vo;

/**
 * 成员 VO。
 */
public record MemberVO(
        Long id,
        Long userId,
        Long orgId,
        String role,
        Boolean isExternal,
        String status,
        String joinedAt,
        String lastActive,
        String createdAt
) {}
