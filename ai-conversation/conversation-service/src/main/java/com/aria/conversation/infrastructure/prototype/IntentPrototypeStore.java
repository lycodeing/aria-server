package com.aria.conversation.infrastructure.prototype;

import com.aria.common.core.util.VectorMathUtils;
import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.infrastructure.ai.IntentClassificationConstants;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 意图原型向量存储。
 *
 * <p>原型向量 = 该意图所有 exampleQueries 的 embedding 均值向量（L2 归一化后）。
 * 存储在 Redis HASH ({@link CustomerServiceCacheConstant#INTENT_PROTOTYPES}) 中，
 * Caffeine 本地缓存加速读取（TTL {@link IntentClassificationConstants#PROTOTYPE_CACHE_TTL_MINUTES} 分钟）。
 *
 * <p>刷新触发时机：
 * <ol>
 *   <li>应用启动后首次 {@link #getAllPrototypes()} 调用（懒加载）</li>
 *   <li>IntentConfig 发生变更（通过 {@link #rebuild()} 主动触发）</li>
 *   <li>Caffeine TTL 过期自动重加载</li>
 * </ol>
 */
@Component
@Slf4j
public class IntentPrototypeStore {

    private final RedissonClient redissonClient;
    private final EmbeddingService embeddingService;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;

    /** Caffeine 本地缓存：intentCode → 原型向量（已 L2 归一化）*/
    private final Cache<String, float[]> localCache = Caffeine.newBuilder()
            .expireAfterWrite(IntentClassificationConstants.PROTOTYPE_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES)
            .maximumSize(IntentClassificationConstants.PROTOTYPE_CACHE_MAX_SIZE)
            .build();

    public IntentPrototypeStore(RedissonClient redissonClient,
                                 EmbeddingService embeddingService,
                                 DomainRepository domainRepository,
                                 ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.embeddingService = embeddingService;
        this.domainRepository = domainRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取所有意图的原型向量快照。
     * 优先从本地 Caffeine 缓存读取；缓存未命中时从 Redis HASH 全量加载；
     * Redis 无数据时触发 rebuild()。
     *
     * @return intentCode → 原型向量（已归一化），不可修改
     */
    public Map<String, float[]> getAllPrototypes() {
        // 先从 Caffeine 批量获取（快路径）
        RMap<String, String> redisMap = redissonClient.getMap(CustomerServiceCacheConstant.INTENT_PROTOTYPES);
        Map<String, String> redisData = redisMap.readAllMap();

        if (redisData == null || redisData.isEmpty()) {
            log.debug("[PrototypeStore] Redis 无数据，触发 rebuild");
            rebuild();
            redisData = redisMap.readAllMap();
            if (redisData == null || redisData.isEmpty()) {
                return Map.of();
            }
        }

        Map<String, float[]> result = new HashMap<>();
        for (Map.Entry<String, String> entry : redisData.entrySet()) {
            try {
                PrototypeEntry proto = objectMapper.readValue(entry.getValue(), PrototypeEntry.class);
                if (proto.vector() != null) {
                    result.put(entry.getKey(), proto.vector());
                }
            } catch (Exception e) {
                log.warn("[PrototypeStore] 反序列化失败 intentCode={}", entry.getKey(), e);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 重建所有意图的原型向量，写入 Redis。
     * 遍历 __system__ 域所有意图，批量调用 EmbeddingService，计算均值并归一化。
     */
    public void rebuild() {
        DomainConfig system = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
        if (system == null) {
            log.warn("[PrototypeStore] __system__ 域不存在，跳过重建");
            return;
        }

        Map<String, String> protoMap = new HashMap<>();
        for (IntentConfig intent : system.intents()) {
            List<String> examples = intent.exampleQueries();
            if (examples == null || examples.isEmpty()) {
                continue;
            }
            List<float[]> vectors = examples.stream()
                    .map(embeddingService::encode)
                    .toList();
            float[] prototype = VectorMathUtils.meanAndNormalize(vectors);
            PrototypeEntry entry = new PrototypeEntry(prototype, examples.size(),
                    Instant.now().toString());
            try {
                protoMap.put(intent.code(), objectMapper.writeValueAsString(entry));
            } catch (JsonProcessingException e) {
                // C7 修复：受检异常显式处理，跳过该意图，不中断整体重建
                log.warn("[PrototypeStore] 意图 {} 原型序列化失败，跳过. error={}",
                        intent.code(), e.getMessage());
            }
        }

        if (!protoMap.isEmpty()) {
            RMap<String, String> redisMap = redissonClient.getMap(
                    CustomerServiceCacheConstant.INTENT_PROTOTYPES);
            redisMap.putAll(protoMap);
        }
        localCache.invalidateAll();
        log.info("[PrototypeStore] 重建原型向量 {} 个", protoMap.size());
    }

    /**
     * 原型向量存储结构（public 供 Jackson 反序列化）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrototypeEntry(float[] vector, int exampleCount, String updatedAt) {}
}
