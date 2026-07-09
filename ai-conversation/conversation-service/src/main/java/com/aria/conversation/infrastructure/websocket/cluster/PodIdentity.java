package com.aria.conversation.infrastructure.websocket.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 当前 Pod 的唯一身份标识。
 *
 * <p>在 {@link #afterPropertiesSet()} 中通过 {@link RabbitAdmin} 声明一个
 * {@code exclusive + autoDelete} 的匿名队列，其队列名作为 podId。
 * Pod 停止时队列随连接断开自动删除，保证 podId 在 Pod 存活期间唯一有效。
 *
 * <p>初始化顺序保证：Spring AMQP 的 {@code RabbitListenerAnnotationBeanPostProcessor}
 * 在 {@code SmartInitializingSingleton.afterSingletonsInstantiated()} 阶段才求值 SpEL，
 * 该阶段晚于所有 {@code InitializingBean.afterPropertiesSet()} 调用，因此消费者中
 * {@code key = "#{@podIdentity.get()}"} 求值时 podId 已经就绪，不会为 null。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodIdentity implements InitializingBean {

    private final RabbitAdmin rabbitAdmin;

    private volatile String podId;

    /**
     * 在所有属性注入完成后，通过 RabbitAdmin 声明一个匿名队列并将其名称记为 podId。
     * 匿名队列为 exclusive + autoDelete，Pod 断开连接时队列自动销毁。
     */
    @Override
    public void afterPropertiesSet() {
        Queue queue = new AnonymousQueue();
        String name = rabbitAdmin.declareQueue(queue);
        // declareQueue 理论上不应返回 null，但做防御处理
        this.podId = (name != null) ? name : queue.getName();
        log.info("[Cluster] Pod 身份初始化完成 podId={}", podId);
    }

    /**
     * 返回当前 Pod 的唯一标识（RabbitMQ AnonymousQueue 名称，格式如 {@code spring.gen-abc123}）。
     *
     * @return podId 字符串，不为 null（{@link #afterPropertiesSet()} 调用后保证有效）
     */
    public String get() {
        return podId;
    }

    /**
     * 判断给定 podId 是否属于本 Pod。
     *
     * @param targetPodId 目标 podId
     * @return {@code true} 表示目标在本 Pod；{@code false} 表示在其他 Pod
     */
    public boolean isLocal(String targetPodId) {
        return podId.equals(targetPodId);
    }
}
