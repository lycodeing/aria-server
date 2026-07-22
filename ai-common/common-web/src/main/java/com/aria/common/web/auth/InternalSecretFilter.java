package com.aria.common.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口共享密钥校验过滤器。
 *
 * <p>对所有 {@code /internal/**} 路径强制校验 {@code X-Internal-Secret} 请求头，
 * 补充 Sa-Token 将 {@code /internal/**} 整体放行（供访客/WebSocket 路由复用）后留下的鉴权空白。
 *
 * <p>安全设计：
 * <ul>
 *   <li>使用 {@link MessageDigest#isEqual} 恒定时间比较，防止时序攻击。</li>
 *   <li>{@code aria.internal.secret} 未配置时拒绝所有内部请求（fail-secure）。</li>
 *   <li>非 {@code /internal/**} 路径直接放行，不影响普通业务接口。</li>
 * </ul>
 *
 * <p>本 Filter 由 {@link InternalSecretAutoConfig} 注册，无需各服务手动配置，
 * 引入 {@code aria-common-web} 依赖即自动生效。
 *
 * @see InternalSecretAutoConfig
 * @see com.aria.common.sdk.interceptor.SharedSecretInterceptor 客户端对应拦截器
 */
@Slf4j
public class InternalSecretFilter extends OncePerRequestFilter {

    /** 服务端校验请求头，与客户端 {@code SharedSecretInterceptor.HEADER} 保持一致。 */
    public static final String HEADER = "X-Internal-Secret";

    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String API_INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private final String internalSecret;

    public InternalSecretFilter(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅拦截 /internal/** 和 /api/v1/internal/** 路径；其余路径直接放行
        String uri = request.getRequestURI();
        return !uri.startsWith(INTERNAL_PATH_PREFIX) && !uri.startsWith(API_INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (!matches(provided)) {
            log.warn("[InternalFilter] 拒绝未授权的内部接口访问 path={} remoteAddr={}",
                    request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"forbidden\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 恒定时间比较，防止时序攻击。
     * 配置未设置（空串）时直接返回 false，fail-secure。
     */
    boolean matches(String provided) {
        if (provided == null || internalSecret == null || internalSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                internalSecret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
