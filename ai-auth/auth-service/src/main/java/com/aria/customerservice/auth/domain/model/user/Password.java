package com.aria.auth.domain.model.user;

import java.util.Objects;

/**
 * 密码值对象（不可变）。
 * <p>内部只持有 BCrypt 哈希，永不暴露明文。通过 PasswordHasher 端口完成编码与比对。
 */
public final class Password {

    private final String hash;
    private final String plainTransient; // 仅在生成/校验瞬间存在，不落库

    private Password(String hash, String plainTransient) {
        this.hash = hash;
        this.plainTransient = plainTransient;
    }

    /**
     * 从明文构造（编码）。
     */
    public static Password encode(String plain, PasswordHasher hasher) {
        if (plain == null || plain.length() < 8) {
            throw new IllegalArgumentException("密码长度不足（最少8位）");
        }
        return new Password(hasher.encode(plain), plain);
    }

    /**
     * 从已存储哈希重建（从 DB 加载时）。
     */
    public static Password fromHash(String hash) {
        return new Password(hash, null);
    }

    /**
     * 比对明文。
     */
    public boolean matches(String plain, PasswordHasher hasher) {
        return plain != null && hasher.matches(plain, this.hash);
    }

    /**
     * 落库哈希。
     */
    public String hash() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Password p && Objects.equals(hash, p.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash);
    }
}
