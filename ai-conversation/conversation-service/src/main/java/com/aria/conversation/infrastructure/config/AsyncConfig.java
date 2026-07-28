package com.aria.conversation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置。
 *
 * <p>Spring 默认的 {@code SimpleAsyncTaskExecutor} 每次调用都新建线程，高并发下会导致无限制线程创建。
 * 此处定义有界线程池，用于 {@code @Async} 各命名异步任务。
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

    /**
     * 意图案例自动积累线程池（{@link com.aria.conversation.infrastructure.ai.IntentAccumulationService} 使用）。
     * 核心 2 线程，最大 4 线程，队列 200，适配高频 Tier3 积累场景。
     * 与 {@link #prototypeRebuildExecutor()} 分离，防止积累任务挤压低频重建任务。
     * 拒绝策略为 CallerRunsPolicy：队列满时降级为同步执行，不丢弃任务。
     */
    @Bean("intentAccumulateExecutor")
    public Executor intentAccumulateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("intent-accumulate-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 意图原型向量重建线程池（{@link com.aria.conversation.infrastructure.event.IntentPrototypeStoreRefreshListener} 使用）。
     * 核心 1 线程，最大 2 线程，队列 5（配置变更低频，不需要大队列）。
     * 拒绝策略为 DiscardOldestPolicy：连续快速变更时保留最新的重建请求。
     */
    @Bean("prototypeRebuildExecutor")
    public Executor prototypeRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("proto-rebuild-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}
