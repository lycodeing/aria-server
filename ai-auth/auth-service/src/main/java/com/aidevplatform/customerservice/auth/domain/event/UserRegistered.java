package com.aidevplatform.customerservice.auth.domain.event;

import com.aidevplatform.customerservice.auth.domain.model.user.AuthProvider;
import com.aidevplatform.customerservice.auth.domain.model.user.UserId;
import com.aidevplatform.common.core.domain.DomainEvent;

import java.time.Instant;
import java.util.Set;

public class UserRegistered extends DomainEvent {
    private final UserId userId;
    private final String username;
    private final String email;
    private final AuthProvider provider;

    public UserRegistered(UserId userId, String username, String email, AuthProvider provider) {
        this.userId = userId; this.username = username; this.email = email; this.provider = provider;
    }

    @Override public String getAggregateId() { return String.valueOf(userId.getValue()); }
    public UserId getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public AuthProvider getProvider() { return provider; }
}
