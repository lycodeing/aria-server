package com.aria.conversation.infrastructure.cache;

import com.aria.conversation.domain.service.SlaPolicyRepository;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaPolicyMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SLA 策略本地缓存（Caffeine，TTL 5 分钟）。
 *
 * <p>实现 {@link SlaPolicyRepository} 接口，由 domain 层的 {@code SlaPolicyMatcher}
 * 通过接口注入，保持 domain → infrastructure 依赖方向正确（依赖倒置原则）。
 *
 * <p>使用与 {@code RoutingConfigProvider} 相同的 Caffeine 直接缓存模式，
 * 不依赖 Spring {@code @EnableCaching}，TTL 到期后自动重新从 DB 加载。
 *
 * <p>当管理后台修改 SLA 策略后，调用 {@link #evict()} 主动失效，
 * 下次 {@link #findAllEnabled()} 调用时会重新从 DB 拉取最新数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaPolicyCache implements SlaPolicyRepository {

    private static final String CACHE_KEY = "all_enabled";

    private final SlaPolicyMapper slaPolicyMapper;

    /**
     * Caffeine 本地缓存，单条记录，TTL 5 分钟。
     * maximumSize=1 确保内存占用可预期。
     */
    private final Cache<String, List<SlaPolicyEntity>> localCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 获取所有已启用的 SLA 策略（按优先级倒序）。
     * 实现 {@link SlaPolicyRepository#findAllEnabled()} 接口方法。
     *
     * <p>缓存命中直接返回，未命中时从 DB 加载并写入缓存。
     * 若 DB 返回空列表，也会缓存（避免频繁查询空表），但调用方应做好空列表处理。
     *
     * @return 已启用策略列表，无策略时返回空列表
     */
    @Override
    public List<SlaPolicyEntity> findAllEnabled() {
        List<SlaPolicyEntity> cached = localCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        try {
            List<SlaPolicyEntity> policies = slaPolicyMapper.selectAllEnabled();
            localCache.put(CACHE_KEY, policies);
            log.debug("[SlaPolicyCache] 从 DB 加载 {} 条 SLA 策略", policies.size());
            return policies;
        } catch (Exception e) {
            log.warn("[SlaPolicyCache] 加载 SLA 策略失败，返回空列表", e);
            return Collections.emptyList();
        }
    }

    /**
     * 主动失效缓存，下次调用 {@link #findAllEnabled()} 时重新从 DB 加载。
     * 在管理后台保存 SLA 策略变更后调用此方法。
     */
    public void evict() {
        localCache.invalidateAll();
        log.debug("[SlaPolicyCache] 缓存已失效");
    }
}

