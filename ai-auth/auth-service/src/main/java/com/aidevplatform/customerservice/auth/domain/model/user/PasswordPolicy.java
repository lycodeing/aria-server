package com.aidevplatform.customerservice.auth.domain.model.user;

public final class PasswordPolicy {
    private final int minLength; private final boolean requireUpper; private final boolean requireDigit;
    private final boolean requireSpecial; private final long maxAgeDays; private final int historyLimit;

    public PasswordPolicy(int minLength, boolean requireUpper, boolean requireDigit, boolean requireSpecial, long maxAgeDays, int historyLimit) {
        this.minLength = minLength; this.requireUpper = requireUpper; this.requireDigit = requireDigit;
        this.requireSpecial = requireSpecial; this.maxAgeDays = maxAgeDays; this.historyLimit = historyLimit;
    }
    public int getMinLength() { return minLength; }
    public boolean isRequireUpper() { return requireUpper; }
    public boolean isRequireDigit() { return requireDigit; }
    public boolean isRequireSpecial() { return requireSpecial; }
    public long getMaxAgeDays() { return maxAgeDays; }
    public int getHistoryLimit() { return historyLimit; }
}
