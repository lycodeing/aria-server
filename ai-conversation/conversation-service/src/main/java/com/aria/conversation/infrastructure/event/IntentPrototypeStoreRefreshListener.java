package com.aria.conversation.infrastructure.event;

import com.aria.conversation.domain.event.IntentConfigChangedEvent;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听意图配置变更事件，异步触发原型向量重建。
 *
 * <p>使用专用线程池 {@code prototypeRebuildExecutor}，需在 @Configuration 中配置：
 * <pre>{@code
 *   @Bean("prototypeRebuildExecutor")
 *   public Executor prototypeRebuildExecutor() {
 *       ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
 *       exec.setCorePoolSize(1);
 *       exec.setMaxPoolSize(2);
 *       exec.setQueueCapacity(5);
 *       exec.setThreadNamePrefix("proto-rebuild-");
 *       exec.initialize();
 *       return exec;
 *   }
 * }</pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentPrototypeStoreRefreshListener {

    private final IntentPrototypeStore store;

    /**
     * 监听 {@link IntentConfigChangedEvent}，异步触发原型向量重建，不阻塞 HTTP 响应。
     *
     * @param event 意图配置变更领域事件
     */
    @Async("prototypeRebuildExecutor")
    @EventListener
    public void onEvent(IntentConfigChangedEvent event) {
        log.info("[PrototypeStore] 检测到 IntentConfig 变更，触发原型重建 domain={}",
                event.domainCode());
        store.rebuild();
    }
}
