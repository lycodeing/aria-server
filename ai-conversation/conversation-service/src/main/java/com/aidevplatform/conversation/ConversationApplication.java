package com.aidevplatform.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 对话服务启动类。
 * 端口：8082，接入天翼云 DeepSeek-V4-Flash，提供 SSE 流式对话接口。
 */
@SpringBootApplication
public class ConversationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
