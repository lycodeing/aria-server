package com.aria.auth.application.service;

import com.aria.auth.domain.model.user.*;
import com.aria.customerservice.auth.domain.model.user.*;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.auth.domain.service.PasswordPolicyChecker;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import com.aria.auth.application.query.UserPageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 用户管理应用服务。
 *
 * <p>编排用户 CRUD、密码管理、角色分配等用例，只依赖 Domain 层接口，无反射操作。
 *
 * @author aria
 */
@Service
public class UserApplicationService {

    private final IUserRepository userRepo;
    /** 依赖 Domain 层端口接口，而非具体实现 */
    private final PasswordHasher passwordHasher;
    private final PasswordPolicyChecker passwordPolicy;

    public UserApplicationService(IUserRepository userRepo,
                                  PasswordHasher passwordHasher,
                                  PasswordPolicyChecker passwordPolicy) {
        this.userRepo = userRepo;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
    }

    // -------------------------------------------------------
    // 用户创建
    // -------------------------------------------------------

    /**
     * 创建新用户（管理员操作）。
     *
     * @param username    用户名
     * @param displayName 显示名称
     * @param email       邮箱（可为 null）
     * @param phone       手机号（可为 null）
     * @param password    初始密码明文
     * @return 创建后的 User 聚合根
     */
    @Transactional(rollbackFor = Exception.class)
    public User create(String username, String displayName, String email,
                       String phone, String password) {
        if (userRepo.existsByUsername(username)) {
            throw BusinessException.of("AUTH_USERNAME_EXISTS", "用户名已存在");
        }
        if (email != null && !email.isBlank() && userRepo.existsByEmail(email)) {
            throw BusinessException.of("AUTH_EMAIL_EXISTS", "邮箱已存在");
        }
        passwordPolicy.check(password);
        Password pwd = Password.encode(password, passwordHasher);
        User user = User.register(
                UserId.of(IdGenerator.nextId()),
                username, displayName, email, phone,
                pwd, Set.of(), AuthProvider.LOCAL);
        userRepo.save(user);
        return user;
    }

    // -------------------------------------------------------
    // 用户查询
    // -------------------------------------------------------

    /**
     * 按 ID 查询用户，不在时抛出业务异常。
     *
     * @param id 用户 ID
     * @return User 聚合根
     */
    public User getById(Long id) {
        return loadUser(id);
    }

    /**
     * 查询当前登录用户信息（供 Controller /me 接口使用）。
     *
     * @param id 当前登录用户 ID
     * @return User 聚合根
     */
    public User getCurrentUser(Long id) {
        return loadUser(id);
    }

    /**
     * 分页搜索用户列表。
     *
     * @param query 分页查询条件（含关键词和分页参数）
     * @return 分页结果
     */
    public PageResult<User> search(UserPageQuery query) {
        return userRepo.search(query);
    }

    // -------------------------------------------------------
    // 用户资料更新
    // -------------------------------------------------------

    /**
     * 更新用户基本资料。邮箱唯一性在此处校验，聚合根只做格式校验。
     *
     * @param id          用户 ID
     * @param displayName 新显示名称（null 表示不修改）
     * @param email       新邮箱（null 表示不修改）
     * @param phone       新手机号（null 表示不修改）
     * @return 更新后的 User 聚合根
     */
    @Transactional(rollbackFor = Exception.class)
    public User updateProfile(Long id, String displayName, String email, String phone) {
        User user = loadUser(id);
        if (email != null && !email.isBlank() && !email.equals(user.getEmail())) {
            if (userRepo.existsByEmail(email)) {
                throw BusinessException.of("AUTH_EMAIL_EXISTS", "邮箱已存在");
            }
        }
        user.updateProfile(displayName, email, phone);
        userRepo.save(user);
        return user;
    }

    // -------------------------------------------------------
    // 状态管理
    // -------------------------------------------------------

    /**
     * 禁用账号。
     *
     * @param id 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        User user = loadUser(id);
        user.disable();
        userRepo.save(user);
    }

    /**
     * 启用账号。
     *
     * @param id 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void enable(Long id) {
        User user = loadUser(id);
        user.enable();
        userRepo.save(user);
    }

    /**
     * 删除用户。
     * 防止管理员删除自己，避免 Sa-Token 会话有效但 DB 记录已不存在的不一致状态。
     *
     * @param id 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 自删保护：当前登录用户不能删除自己
        if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
            Long currentUserId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
            if (id.equals(currentUserId)) {
                throw BusinessException.of("AUTH_SELF_DELETE", "不能删除当前登录用户");
            }
        }
        loadUser(id);
        userRepo.delete(UserId.of(id));
    }

    // -------------------------------------------------------
    // 密码管理
    // -------------------------------------------------------

    /**
     * 用户自助修改密码（需提供旧密码）。
     *
     * @param id          用户 ID
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long id, String oldPassword, String newPassword) {
        User user = loadUser(id);
        passwordPolicy.check(newPassword);
        Password newPwd = Password.encode(newPassword, passwordHasher);
        user.changePassword(oldPassword, newPwd, passwordHasher);
        userRepo.save(user);
    }

    /**
     * 管理员重置密码（无需旧密码，强制下次登录改密）。
     *
     * @param id          用户 ID
     * @param newPassword 新密码明文
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        User user = loadUser(id);
        passwordPolicy.check(newPassword);
        user.resetPassword(Password.encode(newPassword, passwordHasher));
        userRepo.save(user);
    }

    // -------------------------------------------------------
    // 角色分配
    // -------------------------------------------------------

    /**
     * 全量替换用户角色。
     *
     * @param id      用户 ID
     * @param roleIds 新角色 ID 集合（null 时清空所有角色）
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long id, Set<Long> roleIds) {
        User user = loadUser(id);
        user.assignRoles(roleIds == null ? Set.of() : roleIds);
        userRepo.save(user);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 按 ID 加载用户，不存在时抛出 NOT_FOUND 业务异常。
     */
    private User loadUser(Long id) {
        return userRepo.findById(UserId.of(id))
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "用户"));
    }
}
