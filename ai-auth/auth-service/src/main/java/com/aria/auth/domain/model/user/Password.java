package com.aria.auth.domain.model.user;

import com.aria.auth.domain.service.PasswordPolicyChecker;

import java.util.Objects;

/**
 * 密码值对象（不可变）。
 * <p>内部只持有 BCrypt 哈希，永不暴露明文。通过 PasswordHasher 端口完成编码与比对。
 * <p>明文密码只存活在调用栈帧（方法参数），不作为字段持久化到对象中，
 * 避免堆转储（heap dump）暴露在线用户密码。
 */
public final class Password {

    private final String hash;

    private Password(String hash) {
        this.hash = hash;
    }

    /**
     * 从明文构造（编码）。
     * 明文密码的强度校验由 {@link PasswordPolicyChecker} 负责，
     * 此处仅做非空最小保护，避免传入 null 导致 hasher 异常。
     */
    public static Password encode(String plain, PasswordHasher hasher) {
        if (plain == null || plain.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return new Password(hasher.encode(plain));
        // plain 在方法返回后即可被 GC 回收，不会留存在堆上
    }

    /**
     * 从已存储哈希重建（从 DB 加载时）。
     */
    public static Password fromHash(String hash) {
        return new Password(hash);
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

