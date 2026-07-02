package com.aria.auth.application.service;

import com.aria.auth.application.command.LoginCommand;
import com.aria.auth.domain.model.user.Password;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.domain.model.user.UserId;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.auth.domain.service.LoginAttemptPolicy;
import com.aria.auth.infrastructure.security.password.PasswordExpiryChecker;
import com.aria.common.core.domain.IDomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 登录相关数据库事务服务（独立 Bean）。
 *
 * <p>将登录成功路径和失败路径拆分为两个独立事务方法，
 * 解决 {@link AuthApplicationService} 内 {@code this} 调用导致的 Spring AOP 事务代理失效问题。
 *
 * <ul>
 *   <li>{@link #doLoginSuccess} — 标准事务：保存登录成功状态，提交后发布事件</li>
 *   <li>{@link #recordLoginFailure} — {@code REQUIRES_NEW}：独立提交失败计数和审计事件，
 *       即使外层调用方随后抛出异常也不会回滚，确保审计记录不丢失</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginTransactionService {

    private final IUserRepository userRepo;
    private final LoginAttemptPolicy attemptPolicy;
    private final PasswordExpiryChecker passwordExpiryChecker;
    private final IDomainEventPublisher domainEventPublisher;

    /**
     * 登录成功路径：更新登录状态并持久化，发布领域事件，返回登录上下文。
     *
     * @param user    已通过密码校验的用户聚合根
     * @param cmd     登录命令（含记住我标志）
     * @param timeout Token 超时时长（秒）
     * @return 登录上下文（用于事务外建立 Sa-Token 会话）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginContext doLoginSuccess(User user, LoginCommand cmd, long timeout) {
        user.onLoginSucceeded(cmd.getClientIp());
        // 密码过期检查：保持哈希不变，仅触发 mustChangePassword=true
        if (passwordExpiryChecker.isExpired(user.getPasswordChangedAt())) {
            log.info("密码已过期，标记强制修改 userId={}", user.getId().getValue());
            user.resetPassword(user.getPassword());
        }
        userRepo.save(user);
        domainEventPublisher.publish(user);

        List<String> roleKeys       = userRepo.findRoleKeysByUserId(user.getId().getValue());
        List<String> permissionKeys = userRepo.findPermissionKeysByUserId(user.getId().getValue());

        return new LoginContext(
                user.getId().getValue(), user.getUsername(),
                user.getDisplayName(), roleKeys, permissionKeys,
                timeout, user.isMustChangePassword());
    }

    /**
     * 登录失败路径：以 {@code REQUIRES_NEW} 独立事务持久化失败计数并发布审计事件。
     *
     * <p>使用独立事务确保：即使调用方（{@link AuthApplicationService#login}）随后抛出
     * "用户名或密码错误"异常，本次失败计数和 {@code UserLoginFailed} 审计事件仍可提交，
     * 安全告警监听方不会丢失通知。
     *
     * @param user 已完成密码校验失败的用户聚合根
     * @param ip   客户端 IP
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordLoginFailure(User user, String ip) {
        user.onLoginFailed(attemptPolicy.getMaxFailCount(), attemptPolicy.getLockDurationMinutes());
        userRepo.save(user);
        domainEventPublisher.publish(user);
        log.warn("登录失败（密码错误）: userId={}, ip={}, failCount={}",
                user.getId().getValue(), ip, user.getLoginFailCount());
    }
}
