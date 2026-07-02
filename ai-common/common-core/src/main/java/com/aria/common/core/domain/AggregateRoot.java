package com.aria.common.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合根基类。
 * <p>所有领域聚合根继承此类，通过 {@link #registerEvent} 收集领域事件，
 * 由 Application Service 在事务提交前调用 {@link #pullDomainEvents} 取出并发布。
 *
 * <p><b>当前状态（Phase-1）：</b>领域事件已在各聚合根方法中注册（如 {@code UserRegistered}、
 * {@code UserLoginSucceeded} 等），但 Application Service 层尚未调用
 * {@link #pullDomainEvents} 进行发布，事件逻辑为预留设计。
 *
 * <p><b>Phase-2 TODO：</b>在各 {@code @Transactional} 方法末尾调用
 * {@code aggregateRoot.pullDomainEvents()} 并通过 Spring Application Event
 * 或 Outbox 模式发布，实现用户注册通知、登录审计等异步副作用。
 */
public abstract class AggregateRoot {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /**
     * 注册领域事件（子类在状态变更方法中调用）。
     */
    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /**
     * 取出并清空所有待发布的领域事件。
     * <p>Application Service 在持久化后调用此方法，将事件交给 Outbox / Event Publisher。
     *
     * @return 不可变的事件列表副本
     */
    public List<DomainEvent> pullDomainEvents() {
        var events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    /**
     * 是否有未发布的领域事件。
     */
    public boolean hasDomainEvents() {
        return !domainEvents.isEmpty();
    }
}
