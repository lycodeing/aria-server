package com.aria.conversation.infrastructure.websocket.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 当前 Pod 的唯一身份标识。
 *
 * <p>注入 {@link AnonymousQueue}（在 RabbitMQConfig 中声明为 @Bean），
 * 以其队列名作为 podId。Spring AMQP 自动声明该队列，Pod 停止时队列自动删除。
 *
 * <p>与旧实现（注入 RabbitAdmin + afterPropertiesSet 声明队列）相比，此方式不依赖
 * RabbitAdmin Bean，避免了 PodIdentity → RabbitAdmin → WebSocketConfig → chatWebSocketHandler
 * → podIdentity 形成的循环依赖。
 *
 * <p>初始化顺序保证：Spring AMQP 的 {@code RabbitListenerAnnotationBeanPostProcessor}
 * 在 {@code SmartInitializingSingleton.afterSingletonsInstantiated()} 阶段才求值 SpEL，
 * 该阶段晚于所有 Bean 初始化，因此消费者中 {@code key = "#{@podIdentity.get()}"}
 * 求值时 podId 已经就绪，不会为 null。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodIdentity {

    private final AnonymousQueue wsPodDeliveryQueue;

    /**
     * 返回当前 Pod 的唯一标识（RabbitMQ AnonymousQueue 名称，格式如 {@code spring.gen-abc123}）。
     *
     * @return podId 字符串，不为 null
     */
    public String get() {
        return wsPodDeliveryQueue.getName();
    }

    /**
     * 判断给定 podId 是否属于本 Pod。
     *
     * @param targetPodId 目标 podId
     * @return {@code true} 表示目标在本 Pod；{@code false} 表示在其他 Pod
     */
    public boolean isLocal(String targetPodId) {
        return wsPodDeliveryQueue.getName().equals(targetPodId);
    }

    @PostConstruct
    public void logPodId() {
        log.info("[Cluster] Pod 身份初始化完成 podId={}", wsPodDeliveryQueue.getName());
    }
}
