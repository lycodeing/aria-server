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

    /**
     * 可观测性异步落库线程池（P0 观测性改造：意图分层明细、RAG 检索质量、LLM 成本日志共用）。
     *
     * <p>这类写入的定位是「可丢失、绝不能阻塞主链路」，因此：
     * <ul>
     *   <li>核心 1 / 最大 2 线程，队列 200：观测写入本身很轻，无需大池；</li>
     *   <li>拒绝策略 {@code DiscardPolicy}：队列满时直接丢弃，绝不退回调用线程
     *       （SSE 主线程 / WebSocket handler）同步执行，避免拉高响应延迟。</li>
     * </ul>
     * 与业务池（{@code CallerRunsPolicy}，任务不可丢）职责隔离。
     */
    @Bean("observabilityExecutor")
    public Executor observabilityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("observability-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
