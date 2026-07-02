package com.aria.auth.infrastructure.security;

import com.aria.auth.domain.model.user.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密码哈希实现。
 *
 * <p>cost 可通过 {@code adp.auth.password.bcrypt-cost} 配置（默认 12，约 250ms/次）。
 * 测试环境建议设置 4，大幅缩短集成测试耗时。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher(
            @Value("${adp.auth.password.bcrypt-cost:12}") int cost) {
        this.encoder = new BCryptPasswordEncoder(cost);
    }

    @Override
    public String encode(String plain) {
        return encoder.encode(plain);
    }

    @Override
    public boolean matches(String plain, String hash) {
        return encoder.matches(plain, hash);
    }
}
