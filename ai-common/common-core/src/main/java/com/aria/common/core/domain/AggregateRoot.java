package com.aria.common.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合根基类。
 * <p>所有领域聚合根继承此类，通过 {@link #registerEvent} 收集领域事件，
 * 由 Application Service 在事务提交前调用 {@link #pullDomainEvents} 取出并通过
 * Spring {@link org.springframework.context.ApplicationEventPublisher} 发布。
 *
 * <p>监听方使用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保
 * 只在事务成功提交后执行异步副作用（用户注册通知、登录审计等）。
 */
public abstract class AggregateRoot {

    /**
     * 待发布的领域事件列表。
     * 标记 transient 防止聚合根被序列化（如存入 Redis）时事件列表随之序列化，
     * 反序列化后通过懒初始化恢复，避免重复发布。
     */
    private transient List<DomainEvent> domainEvents;

    private List<DomainEvent> events() {
        if (domainEvents == null) {
            domainEvents = new ArrayList<>();
        }
        return domainEvents;
    }

    /**
     * 注册领域事件（子类在状态变更方法中调用）。
     */
    protected void registerEvent(DomainEvent event) {
        events().add(event);
    }

    /**
     * 取出并清空所有待发布的领域事件。
     * Application Service 在持久化后调用此方法，将事件交给 Outbox / Event Publisher。
     *
     * @return 不可变的事件列表副本
     */
    public List<DomainEvent> pullDomainEvents() {
        var snapshot = List.copyOf(events());
        events().clear();
        return snapshot;
    }

    /**
     * 是否有未发布的领域事件。
     */
    public boolean hasDomainEvents() {
        return domainEvents != null && !domainEvents.isEmpty();
    }
}
