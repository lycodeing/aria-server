package com.aria.conversation.infrastructure.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限接口实现。
 *
 * <p>conversation-service 本身不连 auth 库，权限数据由 auth-service
 * 在登录时写入 Redis token-session，此处直接读取，无需回查数据库。
 *
 * <p>key 约定（与 auth-service 保持一致）：
 * <ul>
 *   <li>{@code permissions} — 当前用户的权限 key 列表</li>
 *   <li>{@code roles}       — 当前用户的角色 key 列表</li>
 * </ul>
 *
 * @author aria
 * @see com.aria.auth.infrastructure.auth.StpInterfaceImpl
 */
@Slf4j
@Component
public class StpInterfaceImpl implements StpInterface {

    private static final String PERMISSIONS_KEY = "permissions";
    private static final String ROLES_KEY        = "roles";

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        try {
            Object perms = StpUtil.getTokenSessionByToken(StpUtil.getTokenValue())
                                  .get(PERMISSIONS_KEY);
            if (perms instanceof List<?> list) {
                //noinspection unchecked
                return (List<String>) list;
            }
        } catch (Exception e) {
            log.warn("getPermissionList 读取 token-session 失败: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        try {
            Object roles = StpUtil.getTokenSessionByToken(StpUtil.getTokenValue())
                                  .get(ROLES_KEY);
            if (roles instanceof List<?> list) {
                //noinspection unchecked
                return (List<String>) list;
            }
        } catch (Exception e) {
            log.warn("getRoleList 读取 token-session 失败: {}", e.getMessage());
        }
        return List.of();
    }
}
