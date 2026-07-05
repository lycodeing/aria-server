package com.aria.auth.application.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.application.command.LoginCommand;
import com.aria.auth.application.result.LoginResult;
import com.aria.auth.application.result.TokenRefreshResult;
import com.aria.auth.domain.model.user.PasswordHasher;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.domain.model.user.UserId;
import com.aria.auth.domain.model.user.UserStatus;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.auth.infrastructure.auth.SsoCookieWriter;
import com.aria.auth.infrastructure.security.ratelimit.LoginRateLimiter;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 认证应用服务。
 *
 * <p>编排登录、登出、Token 刷新用例，自身不含 {@code @Transactional}：
 * <ul>
 *   <li>数据库事务委托给 {@link LoginTransactionService}，避免 Spring AOP 代理
 *       因 {@code this} 内部调用而失效</li>
 *   <li>登录失败的失败计数和审计事件通过 {@code REQUIRES_NEW} 独立事务持久化，
 *       确保即使本次登录抛出异常，审计记录仍可提交</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    /**
     * 记住我时的 Token 超时时长：30 天（秒）
     */
    private static final long TIMEOUT_REMEMBER_ME = 30 * 86400L;

    /**
     * 默认 Token 超时时长：8 小时（秒）
     */
    private static final long TIMEOUT_DEFAULT = 28800L;

    /**
     * SSO Cookie 名称
     */
    private static final String COOKIE_NAME_AUTHORIZATION = "Authorization";

    private final IUserRepository userRepo;
    private final PasswordHasher passwordHasher;
    private final LoginRateLimiter rateLimiter;
    private final LoginTransactionService loginTransactionService;
    private final SsoCookieWriter ssoCookieWriter;

    /**
     * 登录。分三段执行：
     * <ol>
     *   <li>频控 + 状态校验 + 密码校验（无事务）</li>
     *   <li>{@link LoginTransactionService#doLoginSuccess} — 事务内持久化登录状态并发布事件</li>
     *   <li>Sa-Token 建立会话 + SSO Cookie — 事务已提交后执行，避免 DB 回滚后会话残留</li>
     * </ol>
     */
    public LoginResult login(LoginCommand cmd) {
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

        // 4. 校验密码：失败时使用 REQUIRES_NEW 独立事务记录失败计数，确保审计不丢失
        if (!user.getPassword().matches(cmd.getPassword(), passwordHasher)) {
            loginTransactionService.recordLoginFailure(user, ip);
            rateLimiter.recordFailure(ip);
            throw invalidCredential();
        }

        // 5. 登录成功：事务内更新状态 + 发布事件
        long timeout = cmd.isRememberMe() ? TIMEOUT_REMEMBER_ME : TIMEOUT_DEFAULT;
        LoginContext ctx = loginTransactionService.doLoginSuccess(user, cmd, timeout);

        // 6. Sa-Token 建立会话（DB 已提交，会话与 DB 状态一致）
        StpUtil.login(ctx.userId(), new SaLoginModel().setTimeout(ctx.timeout()));
        // Redis Session 模式：将用户信息存入 token session，供其他服务跨进程读取
        var session = StpUtil.getTokenSession();
        session.set("username", ctx.username());
        session.set("displayName", ctx.displayName());
        session.set("roles", ctx.roleKeys());
        session.set("permissions", ctx.permissionKeys());

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
     */
    public TokenRefreshResult refreshToken() {
        if (!StpUtil.isLogin()) {
            throw BusinessException.of(CommonErrorCode.UNAUTHORIZED);
        }
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userRepo.findById(UserId.of(userId))
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.UNAUTHORIZED));
        List<String> roleKeys = userRepo.findRoleKeysByUserId(userId);
        List<String> permissionKeys = userRepo.findPermissionKeysByUserId(userId);

        StpUtil.logout();
        StpUtil.login(userId, new SaLoginModel().setTimeout(TIMEOUT_DEFAULT));
        var session = StpUtil.getTokenSession();
        session.set("username", user.getUsername());
        session.set("displayName", user.getDisplayName());
        session.set("roles", roleKeys);
        session.set("permissions", permissionKeys);
        log.info("Token 刷新成功 userId={}", userId);
        return new TokenRefreshResult(StpUtil.getTokenValue(), TIMEOUT_DEFAULT);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

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
