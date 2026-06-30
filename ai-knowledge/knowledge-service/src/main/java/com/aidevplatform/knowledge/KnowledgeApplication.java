package com.aidevplatform.knowledge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库服务启动入口。
 * 负责文档摄取（RabbitMQ）、向量化、混合检索，供 conversation-service 通过 knowledge-sdk 调用。
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
@MapperScan("com.aidevplatform.knowledge.infrastructure.persistence.mapper")
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
