package com.aria.conversation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 对话服务启动类。
 * 端口：8082
 * 功能：AI SSE 流式对话、WebSocket 人工客服、RabbitMQ 可靠消息持久化
 *
 * <p>鉴权说明：conversation-service 的接口分两类：
 * <ul>
 *   <li>/api/v1/chat/**      - 访客公开接口，无需登录（SaTokenWebConfig 白名单）</li>
 *   <li>/api/v1/sessions/**  - 座席接口，需 Sa-Token 登录（StpUtil.checkLogin）</li>
 * </ul>
 */
@SpringBootApplication
@EnableRetry
@EnableAsync
@MapperScan({
    "com.aria.conversation.infrastructure.persistence.mapper",
    "com.aria.conversation.infrastructure.dit.mapper",
    "com.aria.conversation.infrastructure.canned",
    "com.aria.conversation.infrastructure.csat"
})
public class ConversationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
