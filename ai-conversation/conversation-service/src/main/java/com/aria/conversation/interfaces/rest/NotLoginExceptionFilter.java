package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.exception.NotLoginException;
import com.aria.common.web.response.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 最外层安全网：捕获 Sa-Token 在异步/SSE 请求中抛出的 {@link NotLoginException}，
 * 避免其传播到 Tomcat 错误页并打印 ERROR 堆栈。
 *
 * <p>正常情况下全局 {@code @ExceptionHandler} 会处理该异常；但当响应已处于
 * 异步/流式状态（如 SSE 连接）时，异常处理器可能无法正确写入 JSON，导致异常逃逸。
 * 该 Filter 在 Filter 链最外层兜底，返回标准 401 JSON。</p>
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 100)
@RequiredArgsConstructor
public class NotLoginExceptionFilter implements Filter {

    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (NotLoginException e) {
            HttpServletResponse resp = (HttpServletResponse) response;
            if (!resp.isCommitted()) {
                log.warn("NotLoginException 被 Filter 兜底捕获，返回 401: type={}", e.getType());
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(resp.getOutputStream(), R.fail(401, "未登录或 Token 已过期"));
                resp.getOutputStream().flush();
            } else {
                log.warn("NotLoginException 发生在响应已提交后，无法写入 401: {}", e.getMessage());
            }
        }
    }
}
