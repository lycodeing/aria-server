package com.aria.common.sdk;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 泛型类型引用（用于 Jackson 反序列化泛型类型，如 {@code List<StoryVO>}）。
 * <p>使用方式：
 * <pre>
 * TypeReference&lt;List&lt;StoryVO&gt;&gt; type = new TypeReference&lt;&gt;() {};
 * List&lt;StoryVO&gt; list = JsonUtils.parseObject(json, type);
 * </pre>
 */
public abstract class TypeRef<T> extends TypeReference<T> {
}
