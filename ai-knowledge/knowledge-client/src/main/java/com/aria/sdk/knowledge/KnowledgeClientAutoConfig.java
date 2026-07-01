package com.aria.sdk.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * KnowledgeClient Spring Boot 自动装配。
 * conversation-service 引入 knowledge-sdk 依赖后，
 * 只需在 application.yml 配置 ak/sk 和地址即可自动注入 KnowledgeClient Bean。
 *
 * <pre>
 * # application.yml
 * knowledge:
 *   client:
 *     base-url: http://knowledge-service:8081
 *     access-key: ${KNOWLEDGE_ACCESS_KEY}
 *     secret-key: ${KNOWLEDGE_SECRET_KEY}
 *     connect-timeout-ms: 3000
 *     read-timeout-ms: 10000
 * </pre>
 */
@Configuration
@ConditionalOnClass(KnowledgeClient.class)
public class KnowledgeClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeClient knowledgeClient(
            @Value("${knowledge.client.base-url}") String baseUrl,
            @Value("${knowledge.client.access-key}") String ak,
            @Value("${knowledge.client.secret-key}") String sk,
            @Value("${knowledge.client.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${knowledge.client.read-timeout-ms:10000}") long readTimeoutMs) {
        return KnowledgeClient.builder()
            .baseUrl(baseUrl)
            .accessKey(ak, sk)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(readTimeoutMs))
            .maxRetries(3)
            .build();
    }
}
