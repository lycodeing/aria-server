package com.aidevplatform.customerservice.auth.domain.event;

import com.aidevplatform.customerservice.auth.domain.model.user.UserId;
import com.aidevplatform.common.core.domain.DomainEvent;

public class UserDisabled extends DomainEvent {
    private final UserId userId;

    public UserDisabled(UserId userId) {
        this.userId = userId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(userId.getValue());
    }

    public UserId getUserId() {
        return userId;
    }
}
