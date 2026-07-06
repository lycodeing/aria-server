package com.aria.knowledge.infrastructure.config;

import com.aria.knowledge.domain.service.ChunkQualityDomainService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库服务通用 Bean 配置。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class AppConfig {

    /**
     * Chunk 质量领域服务 Bean。
     *
     * <p>domain 层不引入 Spring 注解（保持框架无关性），
     * 由此处统一注册为 Spring Bean，供 QualityFilterHandler 等 Handler 注入。
     */
    @Bean
    public ChunkQualityDomainService chunkQualityDomainService() {
        return new ChunkQualityDomainService();
    }

    /**
     * 知识库双路检索专用 IO 线程池。
     *
     * <p>向量检索和全文检索均为 JDBC 阻塞操作，必须使用专用线程池，
     * 禁止使用 {@link java.util.concurrent.ForkJoinPool#commonPool()}，
     * 避免 IO 阻塞饿死其他使用公共池的任务（并行流、CompletableFuture 链等）。
     *
     * <p>参数说明：
     * <ul>
     *   <li>核心线程数 4：覆盖常规并发（每次请求 2 路，同时 2 个请求）</li>
     *   <li>最大线程数 20：突发流量扩容，DB 连接池默认 10，不超过其 2 倍</li>
     *   <li>队列容量 200：请求积压缓冲，超出时调用方线程降级执行</li>
     * </ul>
     */
    @Bean("searchExecutor")
    public Executor searchExecutor() {
        ThreadFactory namedFactory = new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kb-search-" + count.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 20,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                namedFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}

