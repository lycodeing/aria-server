package com.aria.common.core.domain;

/**
 * 领域事件发布端口接口（定义在 domain 层）。
 *
 * <p>Application Service 依赖此接口，Infrastructure 层提供实现，
 * 保证 Application → Domain 的单向依赖，符合 DDD 分层原则。
 */
public interface IDomainEventPublisher {

    /**
     * 取出聚合根内所有待发布事件并逐一发布。
     *
     * @param aggregateRoot 持久化完成的聚合根
     */
    void publish(AggregateRoot aggregateRoot);
}
