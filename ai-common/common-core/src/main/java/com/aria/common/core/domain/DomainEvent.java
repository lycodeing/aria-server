package com.aria.common.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件基类。
 * <p>所有领域事件继承此类，携带唯一 eventId（幂等键）、eventType（类名）、occurredAt（发生时间）。
 * 事件通过 Outbox 模式持久化到 domain_event_outbox 表，再由 Quartz 轮询投递到 Redis Stream。
 */
public abstract class DomainEvent {

    private final String eventId;
    private final String eventType;
    private final Instant occurredAt;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = this.getClass().getSimpleName();
        this.occurredAt = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * 聚合根 ID（字符串形式），用于 Outbox 的 aggregate_id 字段。
     */
    public abstract String getAggregateId();

    /**
     * 聚合根类型名，用于 Outbox 的 aggregate_type 字段。
     */
    public String getAggregateType() {
        return eventType.replaceAll("(Created|Updated|Changed|Completed|Failed|Succeeded|Deleted|Merged|Closed|Opened|Approved|Rejected|Deprecated|Activated|Released|Superseded|Archived|Started|TimedOut|Requested|Pushed|Violated)$", "");
    }

    @Override
    public String toString() {
        return eventType + "{" + "eventId='" + eventId + '\'' + ", aggregateId='" + getAggregateId() + '\'' + ", occurredAt=" + occurredAt + '}';
    }
}
