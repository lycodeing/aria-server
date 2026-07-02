package com.aria.auth.domain.model.user;

import com.aria.common.core.domain.TypedId;

public class UserId extends TypedId {
    public UserId(Long value) {
        super(value);
    }

    public static UserId of(Long value) {
        return new UserId(value);
    }
}
