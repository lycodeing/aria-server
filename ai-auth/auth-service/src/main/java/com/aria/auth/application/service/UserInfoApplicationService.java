package com.aria.auth.application.service;

import com.aria.auth.application.result.UserInfoResult;
import com.aria.auth.domain.model.role.Role;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.domain.model.user.UserId;
import com.aria.auth.domain.repository.IRoleRepository;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户信息应用服务。
 *
 * <p>负责组装 Vben 前端所需的 UserInfo 格式，包括角色列表和归属路由。
 * 只依赖 Domain 层仓储接口，不直接接触任何 Mapper/DO。
 *
 * @author aria
 */
@Service
@RequiredArgsConstructor
public class UserInfoApplicationService {

    /**
     * 超级管理员角色标识
     */
    private static final String ROLE_SUPER_ADMIN = "super_admin";

    /**
     * 客服角色标识前缀
     */
    private static final String ROLE_PREFIX_KF = "kf_";

    /**
     * 超级管理员首页路由
     */
    private static final String HOME_PATH_ANALYTICS = "/dashboard/analytics";

    /**
     * 客服角色首页路由
     */
    private static final String HOME_PATH_CHAT = "/customerservice/chat";
    /**
     * 默认首页路由（非特殊角色时使用）
     */
    private static final String HOME_PATH_DEFAULT = "/";
    private final IUserRepository userRepository;
    private final IRoleRepository roleRepository;
    @Value("${adp.auth.default-avatar:https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v2.webp}")
    private String defaultAvatar;

    /**
     * 查询当前用户信息，返回强类型结果对象。
     * 角色列表从 token extra 读取，避免二次查 DB。
     *
     * @param userId 当前登录用户 ID
     * @return 用户信息结果
     */
    @SuppressWarnings("unchecked")
    public UserInfoResult getUserInfo(Long userId) {
        User user = userRepository.findById(UserId.of(userId))
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "用户"));

        // Redis Session 模式：从 token session 读取角色键，登录时已存入
        Object extra = cn.dev33.satoken.stp.StpUtil.getTokenSession().get("roles");
        List<String> roles = extra instanceof List<?> list
                ? (List<String>) list
                : roleRepository.findByUserId(userId).stream().map(Role::getRoleKey).toList();

        String realName = user.getDisplayName() != null
                ? user.getDisplayName() : user.getUsername();

        return new UserInfoResult(
                String.valueOf(userId),
                user.getUsername(),
                realName,
                defaultAvatar,
                roles,
                resolveHomePath(roles),
                "");
    }

    /**
     * 根据角色列表解析首页路由。
     * 超级管理员跳分析页，客服角色跳对话页，其余跳默认根路径。
     */
    private String resolveHomePath(List<String> roles) {
        if (roles.contains(ROLE_SUPER_ADMIN)) {
            return HOME_PATH_ANALYTICS;
        }
        if (roles.stream().anyMatch(role -> role.startsWith(ROLE_PREFIX_KF))) {
            return HOME_PATH_CHAT;
        }
        // 普通角色（admin 等）跳默认页，不跳分析页
        return HOME_PATH_DEFAULT;
    }
}
