package com.aidevplatform.common.web.auth;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

/**
 * 认证上下文工具。
 * <p>封装 Sa-Token 的常用操作，所有 Controller / Service 通过此类获取当前登录用户信息。
 */
public final class AuthContext {

    private AuthContext() {}

    /**
     * 当前登录用户 ID。
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 未登录时抛出
     */
    public static Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 当前登录用户名（从 Token extra 中读取）。
     */
    public static String currentUsername() {
        Object username = StpUtil.getExtra("username");
        return username != null ? username.toString() : "unknown";
    }

    /**
     * 当前登录用户显示名。
     */
    public static String currentDisplayName() {
        Object displayName = StpUtil.getExtra("displayName");
        return displayName != null ? displayName.toString() : currentUsername();
    }

    /**
     * 当前登录用户角色列表（从 Token extra 中读取）。
     */
    @SuppressWarnings("unchecked")
    public static List<String> currentRoles() {
        Object roles = StpUtil.getExtra("roles");
        if (roles instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    /**
     * 是否拥有指定角色。
     */
    public static boolean hasRole(String role) {
        return currentRoles().contains(role);
    }

    /**
     * 是否是超级管理员。
     */
    public static boolean isAdmin() {
        return hasRole("admin");
    }

    /**
     * 是否已登录。
     */
    public static boolean isLogin() {
        return StpUtil.isLogin();
    }

    /**
     * 当前 Token 值。
     */
    public static String currentToken() {
        return StpUtil.getTokenValue();
    }
}
