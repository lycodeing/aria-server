package com.aria.auth.interfaces.rest.vo;

/**
 * 团队 VO。
 */
public record TeamVO(
        Long id,
        Long orgId,
        String name,
        String description,
        String permission,
        String createdAt,
        String updatedAt
) {}
