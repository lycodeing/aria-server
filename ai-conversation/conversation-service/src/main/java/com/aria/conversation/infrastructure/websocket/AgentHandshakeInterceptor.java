package com.aria.conversation.infrastructure.websocket;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 座席 WebSocket 握手拦截器。
 * <p>
 * 仅作用于 /ws/agent/* 端点，在 HTTP 握手阶段校验 URL query 参数中的 token，
 * 防止未认证客户端接入座席通道。
 * <p>
 * WebSocket 握手本质是一次 HTTP Upgrade 请求，浏览器 WebSocket API 不允许自定义
 * 请求头，因此约定通过 URL query 参数 {@code ?token=xxx} 传递凭证。
 * <p>
 * 鉴权策略：通过 Sa-Token {@link StpUtil#getLoginIdByToken(String)} 校验 token 有效性，
 * token 无效或已过期时拒绝握手并返回 HTTP 401。
 */
@Slf4j
@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手前校验。
     * token 缺失、为空或 Sa-Token 校验失败时返回 HTTP 401，拒绝握手。
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        String token = extractToken(query);

        if (!validateToken(token)) {
            log.warn("[WS][AUTH] 座席连接被拒绝：token 缺失或无效 path={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 将 token 和 loginId 存入 WebSocket session attributes，便于后续 handler 获取用户身份
        String loginId = (String) StpUtil.getLoginIdByToken(token);
        attributes.put("token", token);
        attributes.put("agentId", loginId);
        log.debug("[WS][AUTH] 座席握手通过 agentId={} path={}", loginId, request.getURI().getPath());
        return true;
    }

    /** 握手完成后无需额外处理。 */
    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手后处理由业务 Handler 负责
    }

    /**
     * Token 校验逻辑：通过 Sa-Token 校验 token 是否有效。
     * <p>
     * 调用 {@link StpUtil#getLoginIdByToken(String)} 若 token 有效则返回 loginId，
     * 无效（过期、伪造、已注销）时抛出 {@link NotLoginException}，此处捕获并返回 false。
     *
     * @param token 从 URL query 中提取的 token 值，可能为 null
     * @return 校验通过返回 true
     */
    protected boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            return loginId != null;
        } catch (NotLoginException e) {
            log.debug("[WS][AUTH] token 无效或已过期: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 query string 中提取 token 参数值。
     * 支持格式：{@code token=xxx} 或 {@code a=1&token=xxx&b=2}
     *
     * @param query URI query string（可为 null）
     * @return token 值，未找到或为空时返回 null
     */
    private String extractToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                String value = URLDecoder.decode(
                    param.substring("token=".length()).trim(),
                    StandardCharsets.UTF_8
                );
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
