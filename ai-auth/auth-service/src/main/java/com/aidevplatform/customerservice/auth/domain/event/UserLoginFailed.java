package com.aidevplatform.customerservice.auth.domain.event;

import com.aidevplatform.customerservice.auth.domain.model.user.UserId;
import com.aidevplatform.common.core.domain.DomainEvent;

public class UserLoginFailed extends DomainEvent {
    private final UserId userId;
    private final int failCount;
    private final boolean locked;

    public UserLoginFailed(UserId userId, int failCount, boolean locked) {
        this.userId = userId; this.failCount = failCount; this.locked = locked;
    }

    @Override public String getAggregateId() { return String.valueOf(userId.getValue()); }
    public UserId getUserId() { return userId; }
    public int getFailCount() { return failCount; }
    public boolean isLocked() { return locked; }
}
