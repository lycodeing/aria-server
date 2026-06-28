package com.aidevplatform.customerservice.auth.domain.model.user;

import com.aidevplatform.common.core.domain.TypedId;

public class UserId extends TypedId {
    public UserId(Long value) {
        super(value);
    }

    public static UserId of(Long value) {
        return new UserId(value);
    }
}
