package com.aidevplatform.customerservice.auth.domain.event;

import com.aidevplatform.customerservice.auth.domain.model.user.UserId;
import com.aidevplatform.common.core.domain.DomainEvent;

import java.time.Instant;

public class UserLoginSucceeded extends DomainEvent {
    private final UserId userId;
    private final String ip;
    private final Instant loginAt;

    public UserLoginSucceeded(UserId userId, String ip, Instant loginAt) {
        this.userId = userId; this.ip = ip; this.loginAt = loginAt;
    }

    @Override public String getAggregateId() { return String.valueOf(userId.getValue()); }
    public UserId getUserId() { return userId; }
    public String getIp() { return ip; }
    public Instant getLoginAt() { return loginAt; }
}
