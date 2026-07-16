package com.aria.conversation.infrastructure.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Micrometer Tracing + Reactor 上下文传播配置。
 *
 * <p>Reactor 默认使用线程池调度（boundedElastic、parallel），MDC 是 ThreadLocal，
 * 跨调度器切换时 traceId/spanId 会丢失。
 *
 * <p>开启 {@link Hooks#enableAutomaticContextPropagation()} 后，
 * Micrometer Context Propagation 自动将 Reactor Context 与 ThreadLocal（含 MDC）双向同步，
 * 保证 {@code DomainAgentService.streamChat()} 中的工具调用、RAG 检索等异步链路日志
 * 均可见相同 traceId。
 *
 * <p>需要 {@code io.micrometer:context-propagation}（Spring Boot 3.x BOM 已管理版本）。
 */
@Configuration
public class TracingConfig {

    @PostConstruct
    public void enableReactorContextPropagation() {
        // 注册 SLF4J MDC 与 Reactor Context 的双向传播
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        "slf4j-mdc",
                        MDC::getCopyOfContextMap,
                        map -> {
                            if (map != null) map.forEach(MDC::put);
                        },
                        MDC::clear);

        // 开启 Reactor Hooks，使每次线程切换时自动恢复/保存 Context
        Hooks.enableAutomaticContextPropagation();
    }
}
