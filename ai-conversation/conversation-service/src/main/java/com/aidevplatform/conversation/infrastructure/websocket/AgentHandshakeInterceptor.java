package com.aidevplatform.conversation.infrastructure.websocket;

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
 * 当前校验策略：token 存在且非空即通过（Phase-1 实现）。
 * 扩展点：{@link #validateToken(String)} 已预留，后续鉴权任务只需在此方法内
 * 补充 JWT 签名校验或 Sa-Token 有效性验证，无需修改调用方。
 */
@Slf4j
@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手前校验。
     * token 缺失或为空时返回 HTTP 401，拒绝握手。
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

        // 将 token 存入 WebSocket session attributes，便于后续 handler 获取用户身份
        attributes.put("token", token);
        log.debug("[WS][AUTH] 座席握手通过 path={}", request.getURI().getPath());
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
     * Token 校验逻辑（扩展点）。
     * <p>
     * Phase-1：非空即通过。
     * Phase-2（后续任务）：在此处加入 JWT 解码、Sa-Token checkLogin 等验证逻辑。
     *
     * @param token 从 URL query 中提取的 token 值，可能为 null
     * @return 校验通过返回 true
     */
    protected boolean validateToken(String token) {
        return token != null && !token.isBlank();
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
