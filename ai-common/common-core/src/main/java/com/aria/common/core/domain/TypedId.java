package com.aria.common.core.domain;

import java.util.Objects;

/**
 * 强类型 ID 值对象基类。
 * <p>防止 Long 类型 ID 在方法参数中传错（如 repoId 和 userId 混淆）。
 * 子类通过 extends TypedId 并提供工厂方法使用：
 * <pre>
 * public class StoryId extends TypedId {
 *     public StoryId(Long value) { super(value); }
 *     public static StoryId of(Long v) { return new StoryId(v); }
 * }
 * </pre>
 * equals/hashCode 按类型 + 值判断，不同类型的 ID 即使值相同也不相等。
 */
public abstract class TypedId {

    private final Long value;

    protected TypedId(Long value) {
        this.value = Objects.requireNonNull(value, "ID value cannot be null");
    }

    public Long getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypedId other)) return false;
        return this.getClass() == other.getClass() && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), value);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + value + ")";
    }
}
