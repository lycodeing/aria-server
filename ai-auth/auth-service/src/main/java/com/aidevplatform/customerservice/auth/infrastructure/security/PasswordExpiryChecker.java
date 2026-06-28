package com.aidevplatform.customerservice.auth.infrastructure.security.password;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class PasswordExpiryChecker {
    @Value("${adp.auth.password.expire-days:90}") private long expireDays;

    public boolean isExpired(Instant passwordChangedAt) {
        if (passwordChangedAt == null) return true;
        return daysUntilExpiry(passwordChangedAt) <= 0;
    }
    public long daysUntilExpiry(Instant passwordChangedAt) {
        if (passwordChangedAt == null) return 0;
        return ChronoUnit.DAYS.between(Instant.now(), passwordChangedAt.plus(expireDays, ChronoUnit.DAYS));
    }
}
