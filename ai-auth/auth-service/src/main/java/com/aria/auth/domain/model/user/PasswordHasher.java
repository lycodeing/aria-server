package com.aria.auth.domain.model.user;

/**
 * 密码哈希器端口（领域层定义，基础设施层用 BCrypt 实现）。
 */
public interface PasswordHasher {
    String encode(String plain);

    boolean matches(String plain, String hash);
}
