package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.application.command.LoginCommand;
import com.aria.auth.application.result.LoginResult;
import com.aria.auth.application.result.TokenRefreshResult;
import com.aria.auth.application.service.AuthApplicationService;
import com.aria.auth.application.service.MenuApplicationService;
import com.aria.auth.interfaces.rest.vo.LoginResultVO;
import com.aria.auth.interfaces.rest.vo.MeVO;
import com.aria.common.web.response.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 认证接口：登录、登出、刷新 Token、获取权限码。
 * Controller 负责从 HttpServletRequest 提取 IP，再构造 LoginCommand，
 * 应用服务不感知 HTTP 关注点。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthApplicationService authService;
    private final MenuApplicationService menuService;

    /**
     * 是否信任 {@code X-Forwarded-For}/{@code X-Real-IP} 代理头。
     * 仅当服务确实部署在会强制覆写这些头的可信反向代理之后时才应开启，默认 false。
     */
    @org.springframework.beans.factory.annotation.Value("${aria.security.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    /**
     * 用户登录，返回 AccessToken。
     * IP 在此处提取后放入 LoginCommand，应用服务不依赖 HttpServletRequest。
     */
    @PostMapping("/login")
    public R<LoginResultVO> login(@RequestBody LoginRequest req,
                                  HttpServletRequest request) {
        String clientIp = extractIp(request);
        LoginCommand cmd = new LoginCommand(
                req.getUsername(), req.getPassword(), req.isRememberMe(), clientIp);
        LoginResult result = authService.login(cmd);
        return R.ok(toLoginResultVO(result));
    }

    /**
     * 退出登录，清除 Sa-Token 会话。
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /**
     * 刷新 AccessToken。
     *
     * @return 新 Token 信息
     */
    @PostMapping("/refresh")
    public R<TokenRefreshResult> refresh() {
        TokenRefreshResult result = authService.refreshToken();
        return R.ok(result);
    }

    /**
     * 获取当前登录用户基本信息。
     */
    @GetMapping("/me")
    @SaCheckLogin
    public R<MeVO> me() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(new MeVO(userId, StpUtil.getTokenValue(), StpUtil.isLogin()));
    }

    /**
     * 获取当前用户按钮级权限码列表，供前端控制按钮显隐。
     */
    @GetMapping("/codes")
    @SaCheckLogin
    public R<List<String>> codes() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(menuService.getUserPermissionCodes(userId));
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 从请求头或远端地址提取客户端真实 IP。
     *
     * <p><b>安全</b>：{@code X-Forwarded-For}/{@code X-Real-IP} 可被客户端任意伪造，
     * 而登录频控与 IP 封禁以该 IP 为 key——若无条件信任这些头，攻击者每次请求换一个
     * 伪造 IP 即可绕过限流暴力破解。因此仅在部署于可信反向代理（会强制覆写 XFF）之后、
     * 通过配置 {@code aria.security.trust-proxy-headers=true} 显式开启时才采信这些头；
     * 默认关闭，直接使用不可伪造的 {@code getRemoteAddr()}。
     */
    private String extractIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
            if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
            return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
        }
        return request.getRemoteAddr();
    }

    private LoginResultVO toLoginResultVO(LoginResult r) {
        LoginResultVO vo = new LoginResultVO();
        vo.setTokenName(r.getTokenName());
        vo.setTokenValue(r.getTokenValue());
        vo.setExpiresIn(r.getExpiresIn());
        vo.setUserId(r.getUserId());
        vo.setUsername(r.getUsername());
        vo.setDisplayName(r.getDisplayName());
        vo.setRoles(r.getRoles());
        vo.setMustChangePassword(r.isMustChangePassword());
        return vo;
    }

    // -------------------------------------------------------
    // Request DTO
    // -------------------------------------------------------

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
        private boolean rememberMe;
    }
}
