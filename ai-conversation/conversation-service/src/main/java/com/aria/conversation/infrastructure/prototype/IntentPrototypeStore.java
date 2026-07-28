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
     * 优先从本地 Caffeine 缓存读取（快路径，避免高频 Redis 访问）；
     * 缓存未命中时从 Redis HASH 全量加载并回填缓存；
     * Redis 无数据时触发 rebuild()。
     *
     * @return intentCode → 原型向量（已归一化），不可修改
     */
    public Map<String, float[]> getAllPrototypes() {
        // 快路径：从 Caffeine 批量读取已缓存的快照
        Map<String, float[]> cached = Map.copyOf(localCache.asMap());
        if (!cached.isEmpty()) {
            return cached;
        }

        // 缓存未命中：从 Redis 全量加载
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
        // 回填 Caffeine 缓存，后续调用走快路径
        localCache.putAll(result);
        return Map.copyOf(result);
    }

    /**
     * 重建所有意图的原型向量，写入 Redis。
     *
     * <p>遍历所有启用域（含 {@code __system__} 域），为每个意图的 exampleQueries 计算均值原型向量。
     * 多个域可能定义同名 intentCode，后加载的域会覆盖先加载的（建议业务意图在域级定义，路由意图在 __system__ 定义）。
     *
     * <p>I1 修复：原仅遍历 __system__ 域，域级业务意图（如 query_logistics）的原型不被构建，
     * 导致 Tier2 对域路径业务意图完全盲目，改为遍历所有启用域。
     */
    public void rebuild() {
        List<com.aria.conversation.infrastructure.dit.config.DomainConfig> allDomains =
                domainRepository.findAllEnabled();

        if (allDomains.isEmpty()) {
            log.warn("[PrototypeStore] 无可用域，跳过重建");
            return;
        }

        Map<String, String> protoMap = new HashMap<>();
        for (var domain : allDomains) {
            for (IntentConfig intent : domain.intents()) {
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
                    log.warn("[PrototypeStore] 意图 {} 原型序列化失败，跳过. error={}",
                            intent.code(), e.getMessage());
                }
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
