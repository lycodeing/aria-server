package com.aidevplatform.conversation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * 对话服务启动类。
 * 端口：8082
 * 功能：AI SSE 流式对话、WebSocket 人工客服、RabbitMQ 可靠消息持久化
 */
@SpringBootApplication
@EnableRetry
@MapperScan("com.aidevplatform.conversation.infrastructure.persistence.mapper")
public class ConversationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
