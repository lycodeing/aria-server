package com.aria.auth.infrastructure.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据提供者。
 *
 * <p>登录/刷新 Token 时已将 roles 和 permissions 存入 token extra，
 * 此处直接从 extra 读取，无需每次鉴权回查数据库，避免每个受保护请求产生额外 DB 开销。
 *
 * <p>extra 生命周期与 token 一致，角色/权限变更后需重新登录或刷新 token 才生效。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private static final String PERMISSIONS_KEY = "permissions";
    private static final String ROLES_KEY = "permissions";

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getPermissionList(Object loginId, String loginType) {
        Object perms = StpUtil.getExtra(loginId.toString(), PERMISSIONS_KEY);
        if (perms instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getRoleList(Object loginId, String loginType) {
        Object roles = StpUtil.getExtra(loginId.toString(), ROLES_KEY);
        if (roles instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
