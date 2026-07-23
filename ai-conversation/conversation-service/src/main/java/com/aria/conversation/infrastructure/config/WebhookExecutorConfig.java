package com.aria.conversation.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Webhook 异步推送线程池配置。
 * 独立线程池，与 SLA 扫描主线程隔离，避免 Webhook 超时阻塞分片扫描。
 */
@Configuration
public class WebhookExecutorConfig {

    @Value("${sla.webhook.core-pool-size:2}")
    private int corePoolSize;

    @Value("${sla.webhook.max-pool-size:5}")
    private int maxPoolSize;

    @Value("${sla.webhook.queue-capacity:50}")
    private int queueCapacity;

    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-");
        // 队列满时降级为调用方线程同步执行，不丢失告警
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
