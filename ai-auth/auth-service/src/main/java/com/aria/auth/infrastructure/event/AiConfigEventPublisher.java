package com.aria.auth.infrastructure.event;

import com.aria.auth.application.service.AiModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 模型配置变更事件发布器。
 *
 * <p>封装通过 Redis Pub/Sub 广播配置变更通知的基础设施细节，
 * 对上层（{@link AiModelConfigService}）
 * 屏蔽 topic 名称、序列化格式等细节。
 *
 * <p>订阅方（conversation-service 的 {@code RemoteAiModelConfigProvider}）
 * 收到通知后清除本地缓存，下次请求时重新从 auth-service 拉取最新配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConfigEventPublisher {

    /** Pub/Sub 频道：与 RemoteAiModelConfigProvider.PUBSUB_TOPIC 保持一致 */
    private static final String TOPIC = "aria:config:ai-changed";

    private final StringRedisTemplate redisTemplate;

    /**
     * 广播 AI 模型配置变更通知。
     * 应在事务提交后调用（通过 {@link org.springframework.transaction.support.TransactionSynchronization#afterCommit} 触发），
     * 确保订阅方读到的是已持久化的最新数据。
     */
    public void publishChanged() {
        redisTemplate.convertAndSend(TOPIC, "{}");
        log.info("[AiConfigEvent] 已广播配置变更通知");
    }
}
