package com.aria.conversation.infrastructure.config;

import com.aria.conversation.infrastructure.websocket.AgentChannelWsHandler;
import com.aria.conversation.infrastructure.websocket.AgentHandshakeInterceptor;
import com.aria.conversation.infrastructure.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 注册配置。
 * <p>
 * 路径：
 * /ws/chat/{sessionId}   → 访客端接入（保持开放策略）
 * /ws/agent              → 座席端接入（需携带 ?token=xxx 通过握手认证）
 * <p>
 * 安全变更（S1/S3）：
 * <ul>
 *   <li>S1: /ws/agent/* 注册 {@link AgentHandshakeInterceptor}，握手阶段校验 token</li>
 *   <li>S1: setAllowedOrigins 从配置文件读取，生产环境禁止通配符</li>
 *   <li>S3: 通过 {@link ServletServerContainerFactoryBean} 限制单条消息最大 64KB</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** 最大文本消息大小：64KB */
    private static final int MAX_MESSAGE_SIZE = 65536;

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final AgentChannelWsHandler agentChannelWsHandler;
    private final AgentHandshakeInterceptor agentHandshakeInterceptor;

    /**
     * 访客端 WS 允许的跨域来源（访客页面可内嵌至任意站点，通常设为 *）。
     * 生产环境通过 APP_CORS_ALLOWED_ORIGINS 环境变量设置。
     */
    @Value("${app.cors.allowed-origins}")
    private String allowedOriginsConfig;

    /**
     * 座席端 WS 允许的跨域来源（仅允许受信任的管理后台域名）。
     * 生产环境通过 APP_CORS_AGENT_ORIGINS 环境变量设置，不配置时继承访客端来源。
     * 建议设为实际管理后台域名，不使用通配符 *。
     */
    @Value("${app.cors.agent-origins:${app.cors.allowed-origins}}")
    private String agentOriginsConfig;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = allowedOriginsConfig.split(",\\s*");
        // 座席端使用独立来源白名单，与访客端解耦
        String[] agentOrigins = agentOriginsConfig.split(",\\s*");

        // 访客端：开放策略（访客页面可内嵌至任意站点），不要求 token
        registry.addHandler(chatWebSocketHandler, "/ws/chat/*")
                .setAllowedOrigins(allowedOrigins);

        // 座席端：独立来源白名单 + 握手拦截器，token 缺失时握手阶段返回 HTTP 401
        registry.addHandler(agentChannelWsHandler, "/ws/agent")
                .addInterceptors(agentHandshakeInterceptor)
                .setAllowedOrigins(agentOrigins);
    }

    /**
     * 配置底层 WebSocket 容器参数。
     * <p>
     * S3：设置单条文本消息的最大缓冲区为 64KB（65536 字节）。
     * 超过此限制时容器会在传输层拒绝消息，与 {@link ChatWebSocketHandler} 中的
     * 应用层长度检查形成双重防护。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 文本消息最大缓冲区：64KB
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_SIZE);
        return container;
    }
}
