package com.aria.auth.infrastructure.event;

import com.aria.common.core.domain.AggregateRoot;
import com.aria.common.core.domain.DomainEvent;
import com.aria.common.core.domain.IDomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 领域事件发布器（Infrastructure 层实现）。
 *
 * <p>实现 {@link IDomainEventPublisher} 接口，通过 Spring {@link ApplicationEventPublisher}
 * 将领域事件发布给各监听方。监听方应使用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保只在事务提交后执行副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher implements IDomainEventPublisher {

    private final ApplicationEventPublisher springEventPublisher;

    @Override
    public void publish(AggregateRoot aggregateRoot) {
        List<DomainEvent> events = aggregateRoot.pullDomainEvents();
        if (events.isEmpty()) {
            return;
        }
        for (DomainEvent event : events) {
            springEventPublisher.publishEvent(event);
            log.debug("[DomainEvent] 发布事件 type={}, aggregateId={}",
                    event.getEventType(), event.getAggregateId());
        }
    }
}
