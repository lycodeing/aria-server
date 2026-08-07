package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionOwnershipValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 访客 WebSocket 握手拦截器（防 IDOR 越权）。
 *
 * <p>作用于 {@code /ws/chat/{sessionId}} 端点，在 HTTP 握手阶段校验调用者是否为该会话归属访客，
 * 阻止任意人拿到/枚举 sessionId 后连上他人 WS 通道接收实时消息。
 *
 * <p>凭证载体：浏览器 WebSocket API 不允许自定义请求头，因此约定通过 URL query 参数传递——
 * 访客 token 走 {@code ?token=xxx}、匿名标识走 {@code ?anonymousId=xxx}；sessionId 取自路径最后一段。
 * 归属判定逻辑统一委托 {@link SessionOwnershipValidator}（绑定校验 + 匿名兼容），校验失败返回 HTTP 403。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorHandshakeInterceptor implements HandshakeInterceptor {

    /** sessionId 格式校验，与 ChatWebSocketHandler.SESSION_ID_PATTERN 保持一致 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final SessionOwnershipValidator sessionOwnershipValidator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {
        String sessionId = extractSessionId(request.getURI().getPath());
        if (sessionId == null) {
            log.warn("[WS][AUTH] 访客连接被拒绝：sessionId 缺失或格式非法 path={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        Map<String, String> params = parseQuery(request.getURI().getQuery());
        String token = params.get("token");
        String anonymousId = params.get("anonymousId");

        if (!sessionOwnershipValidator.isOwner(sessionId, token, anonymousId)) {
            log.warn("[WS][AUTH] 访客连接被拒绝：归属校验失败 sessionId={}", sessionId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        log.debug("[WS][AUTH] 访客握手通过 sessionId={}", sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手后处理由业务 Handler 负责
    }

    /**
     * 从路径 {@code /ws/chat/{sessionId}} 提取并校验 sessionId。
     *
     * @param path URI 路径
     * @return 合法 sessionId，非法或缺失返回 null
     */
    private String extractSessionId(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        String sessionId = path.substring(idx + 1);
        return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
    }

    /**
     * 解析 query string 为参数 Map，值经 URL 解码。
     *
     * @param query URI query string（可为 null）
     * @return 参数 Map，永不为 null
     */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0 || eq == param.length() - 1) {
                continue;
            }
            String key = param.substring(0, eq);
            String value = URLDecoder.decode(param.substring(eq + 1).trim(), StandardCharsets.UTF_8);
            if (!value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }
}
