package com.aria.customerservice.auth.infrastructure.security;

import com.aria.customerservice.auth.domain.model.user.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密码哈希实现（cost=12，约 250ms/次）。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private static final int COST = 12;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST);

    @Override
    public String encode(String plain) {
        return encoder.encode(plain);
    }

    @Override
    public boolean matches(String plain, String hash) {
        return encoder.matches(plain, hash);
    }
}
