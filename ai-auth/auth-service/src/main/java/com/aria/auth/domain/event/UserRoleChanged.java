package com.aria.auth.domain.event;

import com.aria.auth.domain.model.user.UserId;
import com.aria.common.core.domain.DomainEvent;

import java.util.Set;

public class UserRoleChanged extends DomainEvent {
    private final UserId userId;
    private final Set<Long> oldRoleIds;
    private final Set<Long> newRoleIds;

    public UserRoleChanged(UserId userId, Set<Long> oldRoleIds, Set<Long> newRoleIds) {
        this.userId = userId;
        this.oldRoleIds = oldRoleIds;
        this.newRoleIds = newRoleIds;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(userId.getValue());
    }

    public UserId getUserId() { return userId; }
    public Set<Long> getOldRoleIds() { return oldRoleIds; }
    public Set<Long> getNewRoleIds() { return newRoleIds; }
}
