package com.aria.customerservice.auth.domain.model.user;

import com.aria.common.core.domain.AggregateRoot;
import com.aria.common.core.exception.BusinessException;
import com.aria.customerservice.auth.domain.event.UserDisabled;
import com.aria.customerservice.auth.domain.event.UserLoginFailed;
import com.aria.customerservice.auth.domain.event.UserLoginSucceeded;
import com.aria.customerservice.auth.domain.event.UserPasswordChanged;
import com.aria.customerservice.auth.domain.event.UserRegistered;
import com.aria.customerservice.auth.domain.event.UserRoleChanged;

import java.time.Instant;
import java.util.*;

/**
 * 用户聚合根。
 *
 * <p>封装注册、改密、启用/禁用、锁定/解锁等状态流转，
 * 所有变更通过聚合根方法执行，保证不变量，并注册领域事件。
 *
 * @author aria
 */
public class User extends AggregateRoot {

    /** 用户名正则：3-50 位字母、数字、下划线、点、连字符 */
    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_.-]{3,50}$";

    /** 密码历史保留条数上限 */
    private static final int MAX_PASSWORD_HISTORY_SIZE = 5;

    private UserId id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private Password password;
    private UserStatus status;
    private Set<Long> roleIds;
    private AuthProvider provider;

    private int loginFailCount;
    private Instant lockedUntil;
    private Instant passwordChangedAt;
    private Instant lastLoginAt;
    private String lastLoginIp;
    /** 最近 N 次密码哈希，防止密码重用 */
    private List<String> passwordHistory;
    private boolean mustChangePassword;

    // ===== 工厂方法 =====

    /**
     * 注册新用户（触发 UserRegistered 领域事件）。
     *
     * @param id         用户唯一标识
     * @param username   用户名（3-50 位）
     * @param displayName 显示名称
     * @param email      邮箱
     * @param phone      手机号（可为 null）
     * @param password   已编码密码
     * @param roleIds    初始角色 ID 集合
     * @param provider   认证提供方
     * @return 新建的 User 聚合根
     */
    public static User register(UserId id, String username, String displayName, String email,
                                String phone, Password password, Set<Long> roleIds,
                                AuthProvider provider) {
        validateUsername(username);
        validateEmail(email);
        User user = new User();
        user.id = id;
        user.username = username;
        user.displayName = displayName;
        user.email = email;
        user.phone = phone;
        user.password = password;
        user.status = UserStatus.ACTIVE;
        user.roleIds = new HashSet<>(roleIds);
        user.provider = provider;
        user.loginFailCount = 0;
        user.passwordHistory = new ArrayList<>();
        user.passwordHistory.add(password.hash());
        user.passwordChangedAt = Instant.now();
        user.mustChangePassword = true;
        user.registerEvent(new UserRegistered(id, username, email, provider));
        return user;
    }

    /**
     * 从持久化状态重建聚合根（不触发领域事件）。
     * 仅供 Repository 实现层调用，禁止业务代码调用。
     *
     * @param id                  用户唯一标识
     * @param username            用户名
     * @param displayName         显示名称
     * @param email               邮箱
     * @param phone               手机号
     * @param password            密码对象
     * @param status              用户状态
     * @param roleIds             角色 ID 集合
     * @param provider            认证提供方
     * @param loginFailCount      累计登录失败次数
     * @param lockedUntil         锁定到期时间（null 表示未锁定）
     * @param passwordChangedAt   最近一次改密时间
     * @param lastLoginAt         最近一次登录时间
     * @param lastLoginIp         最近一次登录 IP
     * @param passwordHistory     历史密码哈希列表
     * @param mustChangePassword  是否强制下次登录改密
     * @return 重建的 User 聚合根
     */
    public static User reconstitute(UserId id, String username, String displayName, String email,
                                    String phone, Password password, UserStatus status,
                                    Set<Long> roleIds, AuthProvider provider,
                                    int loginFailCount, Instant lockedUntil,
                                    Instant passwordChangedAt, Instant lastLoginAt,
                                    String lastLoginIp, List<String> passwordHistory,
                                    boolean mustChangePassword) {
        User user = new User();
        user.id = id;
        user.username = username;
        user.displayName = displayName;
        user.email = email;
        user.phone = phone;
        user.password = password;
        user.status = status;
        user.roleIds = new HashSet<>(roleIds);
        user.provider = provider;
        user.loginFailCount = loginFailCount;
        user.lockedUntil = lockedUntil;
        user.passwordChangedAt = passwordChangedAt;
        user.lastLoginAt = lastLoginAt;
        user.lastLoginIp = lastLoginIp;
        user.passwordHistory = passwordHistory != null
                ? new ArrayList<>(passwordHistory) : new ArrayList<>();
        user.mustChangePassword = mustChangePassword;
        return user;
    }

    // ===== 登录相关 =====

    /**
     * 登录成功：重置失败计数，记录时间/IP，若账号处于锁定状态则自动解锁。
     *
     * @param ip 客户端 IP
     */
    public void onLoginSucceeded(String ip) {
        this.loginFailCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = Instant.now();
        this.lastLoginIp = ip;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
        registerEvent(new UserLoginSucceeded(id, ip, Instant.now()));
    }

    /**
     * 登录失败：累加失败次数，达阈值则锁定账号。
     *
     * @param maxFailCount        最大允许失败次数
     * @param lockDurationMinutes 锁定时长（分钟）
     */
    public void onLoginFailed(int maxFailCount, long lockDurationMinutes) {
        this.loginFailCount++;
        if (this.loginFailCount >= maxFailCount) {
            this.status = UserStatus.LOCKED;
            this.lockedUntil = Instant.now().plusSeconds(lockDurationMinutes * 60);
        }
        registerEvent(new UserLoginFailed(id, loginFailCount, status == UserStatus.LOCKED));
    }

    /**
     * 判定是否可登录。
     * <p>若账号被禁用，返回 false；
     * 若账号被锁定且锁定未到期，返回 false；
     * 若锁定已到期，自动将状态重置为 ACTIVE（下次 save 时持久化）。
     *
     * @return true 表示可登录
     */
    public boolean canLogin() {
        if (status == UserStatus.DISABLED) {
            return false;
        }
        if (status == UserStatus.LOCKED) {
            if (lockedUntil != null && Instant.now().isAfter(lockedUntil)) {
                // 锁定已到期，自动解锁，状态将在下次 save 时持久化
                this.status = UserStatus.ACTIVE;
                this.loginFailCount = 0;
            } else {
                return false;
            }
        }
        return true;
    }

    // ===== 密码管理 =====

    /**
     * 用户自助修改密码：校验旧密码，校验历史密码不重用。
     *
     * @param oldPlain 旧密码明文
     * @param newPwd   新密码对象（已编码）
     * @param hasher   密码哈希器
     */
    public void changePassword(String oldPlain, Password newPwd, PasswordHasher hasher) {
        if (!this.password.matches(oldPlain, hasher)) {
            throw BusinessException.of("AUTH_PWD_OLD_MISMATCH", "旧密码不正确");
        }
        for (String oldHash : passwordHistory) {
            if (hasher.matches(newPwd.hash(), oldHash)) {
                throw BusinessException.of("AUTH_PWD_HISTORY_DUPLICATE", "不能与最近使用的密码重复");
            }
        }
        this.password = newPwd;
        this.passwordHistory.add(newPwd.hash());
        if (this.passwordHistory.size() > MAX_PASSWORD_HISTORY_SIZE) {
            this.passwordHistory.remove(0);
        }
        this.passwordChangedAt = Instant.now();
        this.mustChangePassword = false;
        registerEvent(new UserPasswordChanged(id));
    }

    /**
     * 管理员重置密码：直接替换密码，强制下次登录改密。
     *
     * @param tempPwd 临时密码（已编码）
     */
    public void resetPassword(Password tempPwd) {
        this.password = tempPwd;
        this.mustChangePassword = true;
        this.passwordChangedAt = Instant.now();
        registerEvent(new UserPasswordChanged(id));
    }

    // ===== 资料更新 =====

    /**
     * 更新用户基本资料。
     * 邮箱唯一性校验由应用服务在调用前完成（需查库），聚合根只做格式校验。
     *
     * @param displayName 新显示名称（null 表示不修改）
     * @param email       新邮箱（null 表示不修改）
     * @param phone       新手机号（null 表示不修改）
     */
    public void updateProfile(String displayName, String email, String phone) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        if (email != null && !email.isBlank() && !email.equals(this.email)) {
            validateEmail(email);
            this.email = email;
        }
        if (phone != null) {
            this.phone = phone;
        }
    }

    // ===== 状态管理 =====

    /** 禁用账号（触发 UserDisabled 事件）。 */
    public void disable() {
        if (this.status == UserStatus.DISABLED) {
            return;
        }
        this.status = UserStatus.DISABLED;
        registerEvent(new UserDisabled(id));
    }

    /** 启用账号（仅对 DISABLED 状态生效）。 */
    public void enable() {
        if (this.status != UserStatus.DISABLED) {
            return;
        }
        this.status = UserStatus.ACTIVE;
        this.loginFailCount = 0;
        this.lockedUntil = null;
    }

    // ===== 角色管理 =====

    /**
     * 全量替换用户角色（触发 UserRoleChanged 事件）。
     *
     * @param newRoleIds 新角色 ID 集合
     */
    public void assignRoles(Set<Long> newRoleIds) {
        Set<Long> old = Set.copyOf(this.roleIds);
        this.roleIds = new HashSet<>(newRoleIds);
        registerEvent(new UserRoleChanged(id, old, Set.copyOf(newRoleIds)));
    }

    // ===== 校验 =====

    private static void validateUsername(String username) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("用户名格式非法（3-50位字母数字下划线）");
        }
    }

    private static void validateEmail(String email) {
        if (email == null || !email.contains("@") || email.indexOf('@') == 0
                || email.indexOf('@') == email.length() - 1) {
            throw new IllegalArgumentException("邮箱格式非法");
        }
    }

    // ===== Getters =====

    public UserId getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Password getPassword() { return password; }
    public UserStatus getStatus() { return status; }
    public Set<Long> getRoleIds() { return Collections.unmodifiableSet(roleIds); }
    public AuthProvider getProvider() { return provider; }
    public int getLoginFailCount() { return loginFailCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public String getLastLoginIp() { return lastLoginIp; }
    public List<String> getPasswordHistory() { return Collections.unmodifiableList(passwordHistory); }
    public boolean isMustChangePassword() { return mustChangePassword; }
}
