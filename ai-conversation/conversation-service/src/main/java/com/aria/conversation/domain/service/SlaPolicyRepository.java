package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;

import java.util.List;

/**
 * SLA 策略仓储接口（domain 层）。
 *
 * <p>定义在 domain 层，由 {@code infrastructure.cache.SlaPolicyCache} 实现。
 * 通过依赖倒置（Dependency Inversion Principle）保持 domain 层不依赖 infrastructure 层：
 * <pre>
 *   domain/service/SlaPolicyRepository      ← 接口，domain 层定义
 *   infrastructure/cache/SlaPolicyCache     ← 实现，infrastructure 层
 * </pre>
 *
 * <p>{@link SlaPolicyMatcher} 通过此接口获取策略列表，
 * 无需关心数据来自缓存、数据库还是其他存储介质。
 */
public interface SlaPolicyRepository {

    /**
     * 获取所有已启用的 SLA 策略（按优先级倒序）。
     *
     * @return 已启用策略列表，无策略时返回空列表（不返回 null）
     */
    List<SlaPolicyEntity> findAllEnabled();
}
