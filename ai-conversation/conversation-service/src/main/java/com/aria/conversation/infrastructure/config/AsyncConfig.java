package com.aria.conversation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置。
 *
 * <p>Spring 默认的 {@code SimpleAsyncTaskExecutor} 每次调用都新建线程，高并发下会导致无限制线程创建。
 * 此处定义有界线程池，用于 {@code @Async("cannedResponseExecutor")} 等异步任务。
 */
@Configuration
public class AsyncConfig {

    /**
     * 快捷回复使用次数异步递增线程池。
     * 核心 2 线程，最大 4 线程，队列 500，拒绝策略为 CallerRunsPolicy（降级为同步执行，不丢失请求）。
     */
    @Bean("cannedResponseExecutor")
    public Executor cannedResponseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("canned-async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
