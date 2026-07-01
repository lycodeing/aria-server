package com.aria.conversation;

import com.aria.common.web.auth.SaTokenWebConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * 对话服务启动类。
 * 端口：8082
 * 功能：AI SSE 流式对话、WebSocket 人工客服、RabbitMQ 可靠消息持久化
 *
 * <p>鉴权说明：conversation-service 的接口分两类：
 * <ul>
 *   <li>/api/v1/chat/**      - 访客公开接口，无需登录</li>
 *   <li>/api/v1/sessions/**  - 座席接口，Phase-2 TODO 接入 Sa-Token</li>
 * </ul>
 * 暂时排除 common-web 的全局 Sa-Token 拦截器，待 Phase-2 完成后移除 exclude。
 */
@SpringBootApplication(exclude = SaTokenWebConfig.class)
@EnableRetry
@MapperScan("com.aria.conversation.infrastructure.persistence.mapper")
public class ConversationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
