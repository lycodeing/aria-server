package com.aria.customerservice.auth.application.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.customerservice.auth.application.command.LoginCommand;
import com.aria.customerservice.auth.application.result.LoginResult;
import com.aria.customerservice.auth.application.result.TokenRefreshResult;
import com.aria.customerservice.auth.domain.model.user.*;
import com.aria.customerservice.auth.domain.repository.IUserRepository;
import com.aria.customerservice.auth.domain.service.LoginAttemptPolicy;
import com.aria.customerservice.auth.infrastructure.auth.SsoCookieWriter;
import com.aria.customerservice.auth.infrastructure.security.ratelimit.LoginRateLimiter;
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

    public AuthApplicationService(IUserRepository userRepo,
                                  PasswordHasher passwordHasher,
                                  LoginRateLimiter rateLimiter,
                                  LoginAttemptPolicy attemptPolicy,
                                  SsoCookieWriter ssoCookieWriter) {
        this.userRepo = userRepo;
        this.passwordHasher = passwordHasher;
        this.rateLimiter = rateLimiter;
        this.attemptPolicy = attemptPolicy;
        this.ssoCookieWriter = ssoCookieWriter;
    }

    /**
     * 登录。IP 已由 Controller 层从 HttpServletRequest 提取并放入 LoginCommand。
     *
     * @param cmd 登录命令（含用户名、密码、记住我标志、客户端 IP）
     * @return 登录结果（含 Token、用户信息、角色列表）
     */
    @Transactional(rollbackFor = Exception.class)
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

        // 4. 校验密码
        if (!user.getPassword().matches(cmd.getPassword(), passwordHasher)) {
            user.onLoginFailed(attemptPolicy.getMaxFailCount(), attemptPolicy.getLockDurationMinutes());
            userRepo.save(user);
            rateLimiter.recordFailure(ip);
            log.warn("登录失败（密码错误）: username={}, ip={}, failCount={}",
                    cmd.getUsername(), ip, user.getLoginFailCount());
            throw invalidCredential();
        }

        // 5. 登录成功
        user.onLoginSucceeded(ip);
        userRepo.save(user);

        // 6. 从 DB 加载真实角色键列表
        List<String> roleKeys = userRepo.findRoleKeysByUserId(user.getId().getValue());

        // 7. Sa-Token 生成 Token
        long timeout = cmd.isRememberMe() ? TIMEOUT_REMEMBER_ME : TIMEOUT_DEFAULT;
        StpUtil.login(user.getId().getValue(), new SaLoginModel()
                .setTimeout(timeout)
                .setExtra("username", user.getUsername())
                .setExtra("displayName", user.getDisplayName())
                .setExtra("roles", roleKeys));

        // 8. 写 SSO Cookie（SsoCookieWriter 是基础设施端口，通过 RequestContextHolder 获取响应）
        writeSsoCookie(timeout);

        return new LoginResult(
                COOKIE_NAME_AUTHORIZATION,
                StpUtil.getTokenValue(),
                timeout,
                user.getId().getValue(),
                user.getUsername(),
                user.getDisplayName(),
                roleKeys,
                user.isMustChangePassword());
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
     * 刷新 Token，返回强类型结果对象。
     *
     * @return 新 Token 信息
     */
    public TokenRefreshResult refreshToken() {
        if (!StpUtil.isLogin()) {
            throw BusinessException.of(CommonErrorCode.UNAUTHORIZED);
        }
        Long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        StpUtil.login(userId, new SaLoginModel().setTimeout(TIMEOUT_DEFAULT));
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
