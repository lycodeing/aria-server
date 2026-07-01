package com.aria.auth.interfaces.rest.vo;

/**
 * 组织设置 VO。
 */
public record OrgSettingsVO(
        String name,
        String path,
        String description,
        String defaultVisibility
) {}
