package com.aria.auth.application.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.domain.model.user.PasswordHasher;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.domain.model.user.UserId;
import com.aria.auth.domain.model.user.UserStatus;
import com.aria.auth.infrastructure.security.password.PasswordExpiryChecker;
import com.aria.auth.application.command.LoginCommand;
import com.aria.auth.application.result.LoginResult;
import com.aria.auth.application.result.TokenRefreshResult;
import com.aria.customerservice.auth.domain.model.user.*;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.auth.domain.service.LoginAttemptPolicy;
import com.aria.auth.infrastructure.auth.SsoCookieWriter;
import com.aria.auth.infrastructure.security.ratelimit.LoginRateLimiter;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 认证应用服务。
 *
 * <p>编排登录、登出、Token 刷新用例，是事务边界。
 * 不依赖 HttpServletRequest，IP 由 Controller 提前解析后放入 LoginCommand。
 *
 * @author aria
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    /** 记住我时的 Token 超时时长：30 天（秒） */
    private static final long TIMEOUT_REMEMBER_ME = 30 * 86400L;

    /** 默认 Token 超时时长：8 小时（秒） */
    private static final long TIMEOUT_DEFAULT = 28800L;

    /** SSO Cookie 名称 */
    private static final String COOKIE_NAME_AUTHORIZATION = "Authorization";

    private final IUserRepository userRepo;
    /** 依赖 Domain 层端口接口，而非具体实现 */
    private final PasswordHasher passwordHasher;
    private final LoginRateLimiter rateLimiter;
    private final LoginAttemptPolicy attemptPolicy;
    private final SsoCookieWriter ssoCookieWriter;
    private final PasswordExpiryChecker passwordExpiryChecker;

    public AuthApplicationService(IUserRepository userRepo,
                                  PasswordHasher passwordHasher,
                                  LoginRateLimiter rateLimiter,
                                  LoginAttemptPolicy attemptPolicy,
                                  SsoCookieWriter ssoCookieWriter,
                                  PasswordExpiryChecker passwordExpiryChecker) {
        this.userRepo              = userRepo;
        this.passwordHasher        = passwordHasher;
        this.rateLimiter           = rateLimiter;
        this.attemptPolicy         = attemptPolicy;
        this.ssoCookieWriter       = ssoCookieWriter;
        this.passwordExpiryChecker = passwordExpiryChecker;
    }

    /**
     * 登录。IP 已由 Controller 层从 HttpServletRequest 提取并放入 LoginCommand。
     *
     * <p>两段式设计：
     * <ol>
     *   <li>{@link #doLoginDb} — 事务边界内：校验、更新登录状态、持久化</li>
     *   <li>Sa-Token login + SSO Cookie — 事务边界外：DB 已提交后才建立会话，
     *       避免 DB 回滚后 Sa-Token 会话仍有效导致状态不一致</li>
     * </ol>
     *
     * @param cmd 登录命令（含用户名、密码、记住我标志、客户端 IP）
     * @return 登录结果（含 Token、用户信息、角色列表）
     */
    public LoginResult login(LoginCommand cmd) {
        // Step 1：事务内校验 + 持久化（返回所需的用户快照数据）
        LoginContext ctx = doLoginDb(cmd);

        // Step 2：Sa-Token 建立会话，roles 和 permissions 存入 token extra，
        // StpInterfaceImpl 直接读 extra，无需每次鉴权回查 DB
        StpUtil.login(ctx.userId(), new SaLoginModel()
                .setTimeout(ctx.timeout())
                .setExtra("username",    ctx.username())
                .setExtra("displayName", ctx.displayName())
                .setExtra("roles",       ctx.roleKeys())
                .setExtra("permissions", ctx.permissionKeys()));

        // Step 3：写 SSO Cookie
        writeSsoCookie(ctx.timeout());

        return new LoginResult(
                COOKIE_NAME_AUTHORIZATION,
                StpUtil.getTokenValue(),
                ctx.timeout(),
                ctx.userId(),
                ctx.username(),
                ctx.displayName(),
                ctx.roleKeys(),
                ctx.mustChangePassword());
    }

    /**
     * 事务内登录逻辑：校验频控/用户状态/密码，更新登录状态并持久化。
     * 不调用 Sa-Token，确保事务回滚时不残留无效会话。
     *
     * @return 登录上下文（用户快照，供事务外 Sa-Token 使用）
     */
    @Transactional(rollbackFor = Exception.class)
    protected LoginContext doLoginDb(LoginCommand cmd) {
        String ip = cmd.getClientIp();

        // 1. IP 频控
        if (!rateLimiter.tryAcquire(ip)) {
            throw BusinessException.of(CommonErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
        }

        // 2. 查用户
        User user = userRepo.findByUsername(cmd.getUsername())
                .orElseThrow(this::invalidCredential);

        // 3. 校验状态
        if (!user.canLogin()) {
            if (user.getStatus() == UserStatus.DISABLED) {
                throw BusinessException.of("AUTH_ACCOUNT_DISABLED", "账号已被禁用");
            }
            throw BusinessException.of("AUTH_ACCOUNT_LOCKED", "账号已锁定，请稍后再试");
        }

        // 4. 校验密码
        if (!user.getPassword().matches(cmd.getPassword(), passwordHasher)) {
            user.onLoginFailed(attemptPolicy.getMaxFailCount(), attemptPolicy.getLockDurationMinutes());
            userRepo.save(user);
            rateLimiter.recordFailure(ip);
            log.warn("登录失败（密码错误）: username={}, ip={}, failCount={}",
                    cmd.getUsername(), ip, user.getLoginFailCount());
            throw invalidCredential();
        }

        // 5. 登录成功，更新状态并持久化
        user.onLoginSucceeded(ip);
        // 密码过期检查：若超过配置天数未修改，标记 mustChangePassword，前端引导用户修改
        if (passwordExpiryChecker.isExpired(user.getPasswordChangedAt())) {
            log.info("密码已过期，标记强制修改 userId={}", user.getId().getValue());
            user.resetPassword(user.getPassword()); // 保持哈希不变，仅触发 mustChangePassword=true
        }
        userRepo.save(user);

        // 6. 加载角色和权限（一并存入 token extra，避免 StpInterfaceImpl 每次鉴权查 DB）
        List<String> roleKeys       = userRepo.findRoleKeysByUserId(user.getId().getValue());
        List<String> permissionKeys = userRepo.findPermissionKeysByUserId(user.getId().getValue());
        long timeout = cmd.isRememberMe() ? TIMEOUT_REMEMBER_ME : TIMEOUT_DEFAULT;

        return new LoginContext(
                user.getId().getValue(), user.getUsername(),
                user.getDisplayName(), roleKeys, permissionKeys, timeout,
                user.isMustChangePassword());
    }

    /** 登录事务内结果快照，不含任何框架引用，纯数据传递。 */
    private record LoginContext(
            Long userId, String username, String displayName,
            List<String> roleKeys, List<String> permissionKeys,
            long timeout, boolean mustChangePassword) {}

    /**
     * 登出，清除当前 Sa-Token 会话。
     */
    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
            log.info("用户登出成功");
        }
    }

    /**
     * 刷新 Token，重新加载用户信息并填充 extra，保证权限校验不失效。
     *
     * <p>注意：必须重新从 DB 加载用户信息再填充 extra，
     * 不能只 logout + login(userId)，否则 extra（username/displayName/roles）全部丢失，
     * 导致后续权限校验、菜单加载等依赖 extra 的接口失效。
     *
     * @return 新 Token 信息
     */
    public TokenRefreshResult refreshToken() {
        if (!StpUtil.isLogin()) {
            throw BusinessException.of(CommonErrorCode.UNAUTHORIZED);
        }
        Long userId = StpUtil.getLoginIdAsLong();
        // 重新加载用户信息，保证 extra 数据与 DB 保持同步
        User user = userRepo.findById(UserId.of(userId))
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.UNAUTHORIZED));
        List<String> roleKeys       = userRepo.findRoleKeysByUserId(userId);
        List<String> permissionKeys = userRepo.findPermissionKeysByUserId(userId);

        StpUtil.logout();
        StpUtil.login(userId, new SaLoginModel()
                .setTimeout(TIMEOUT_DEFAULT)
                .setExtra("username",    user.getUsername())
                .setExtra("displayName", user.getDisplayName())
                .setExtra("roles",       roleKeys)
                .setExtra("permissions", permissionKeys));
        log.info("Token 刷新成功 userId={}", userId);
        return new TokenRefreshResult(StpUtil.getTokenValue(), TIMEOUT_DEFAULT);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 尝试写入 SSO Cookie，失败时仅记录 debug 日志，不影响登录主流程。
     *
     * @param timeout Token 超时时长（秒）
     */
    private void writeSsoCookie(long timeout) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            var resp = attrs.getResponse();
            if (resp != null) {
                ssoCookieWriter.writeTokenCookie(
                        resp, COOKIE_NAME_AUTHORIZATION,
                        StpUtil.getTokenValue(), (int) timeout);
            }
        } catch (Exception ex) {
            log.debug("SSO Cookie 写入跳过（非 Web 上下文）: {}", ex.getMessage());
        }
    }

    private BusinessException invalidCredential() {
        return BusinessException.of("AUTH_INVALID_CREDENTIAL", "用户名或密码错误");
    }
}
