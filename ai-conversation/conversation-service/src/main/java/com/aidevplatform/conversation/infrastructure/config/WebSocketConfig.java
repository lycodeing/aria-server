package com.aidevplatform.conversation.infrastructure.config;

import com.aidevplatform.conversation.infrastructure.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册配置。
 * <p>
 * 路径：
 * /ws/chat/{sessionId}   → 访客端接入
 * /ws/agent/{sessionId}  → 座席端接入
 * <p>
 * setAllowedOrigins("*") — 本地开发；生产环境改为具体域名。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat/*", "/ws/agent/*")
                .setAllowedOrigins("*");
    }
}
