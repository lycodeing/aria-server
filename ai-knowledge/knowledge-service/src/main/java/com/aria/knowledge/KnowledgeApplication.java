package com.aria.knowledge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库服务启动入口。
 * 负责文档摄取（RabbitMQ）、向量化、混合检索，供 conversation-service 通过 knowledge-sdk 调用。
 *
 * <p>{@code @EnableRetry} 启用后，所有 {@code @Retryable} 标注的方法（当前仅
 * {@code DocIngestPublisher.publish}）将被 CGLib 代理增强，新增 @Retryable 需评估副作用。
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
@MapperScan("com.aria.knowledge.infrastructure.persistence.mapper")
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
