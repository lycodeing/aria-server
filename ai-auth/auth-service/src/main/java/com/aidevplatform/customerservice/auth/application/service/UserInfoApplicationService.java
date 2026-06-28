package com.aidevplatform.customerservice.auth.application.service;

import com.aidevplatform.customerservice.auth.application.result.UserInfoResult;
import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.customerservice.auth.domain.model.user.User;
import com.aidevplatform.customerservice.auth.domain.model.user.UserId;
import com.aidevplatform.customerservice.auth.domain.repository.IRoleRepository;
import com.aidevplatform.customerservice.auth.domain.repository.IUserRepository;
import com.aidevplatform.common.core.exception.BusinessException;
import com.aidevplatform.common.core.exception.CommonErrorCode;
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
 * @author aidevplatform
 */
@Service
@RequiredArgsConstructor
public class UserInfoApplicationService {

    /** 超级管理员角色标识 */
    private static final String ROLE_SUPER_ADMIN = "super_admin";

    /** 客服角色标识前缀 */
    private static final String ROLE_PREFIX_KF = "kf_";

    /** 超级管理员首页路由 */
    private static final String HOME_PATH_ANALYTICS = "/analytics";

    /** 客服角色首页路由 */
    private static final String HOME_PATH_CHAT = "/customerservice/chat";

    private final IUserRepository userRepository;
    private final IRoleRepository roleRepository;

    @Value("${adp.auth.default-avatar:https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v2.webp}")
    private String defaultAvatar;

    /**
     * 查询当前用户信息，返回强类型结果对象。
     *
     * @param userId 当前登录用户 ID
     * @return 用户信息结果
     */
    public UserInfoResult getUserInfo(Long userId) {
        User user = userRepository.findById(UserId.of(userId))
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "用户"));

        List<String> roles = roleRepository.findByUserId(userId).stream()
                .map(Role::getRoleKey)
                .toList();

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
     * 超级管理员跳分析页，客服相关角色跳对话页，其余跳默认页。
     *
     * @param roles 角色键列表
     * @return 首页路由路径
     */
    private String resolveHomePath(List<String> roles) {
        if (roles.contains(ROLE_SUPER_ADMIN)) {
            return HOME_PATH_ANALYTICS;
        }
        if (roles.stream().anyMatch(role -> role.startsWith(ROLE_PREFIX_KF))) {
            return HOME_PATH_CHAT;
        }
        return HOME_PATH_ANALYTICS;
    }
}
