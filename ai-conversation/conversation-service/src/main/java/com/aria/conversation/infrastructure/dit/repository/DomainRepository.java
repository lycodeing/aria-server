package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.aria.conversation.infrastructure.dit.mapper.DomainMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentSlotMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentToolMapper;
import com.aria.conversation.infrastructure.dit.mapper.ToolMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 领域配置仓储。
 *
 * <p>缓存策略：
 * <pre>
 *   findByCode(code)
 *     ├─ Redis HIT  → 反序列化直接返回（TTL 10 分钟）
 *     └─ Redis MISS → DB 查询 → 组装 DomainConfig → 写 Redis → 返回
 * </pre>
 *
 * <p>缓存失效：管理后台修改领域/意图/工具配置后调用 {@link #evict(String)} 主动失效。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DomainRepository {

    private static final String CACHE_KEY_PREFIX = "dit:domain:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisCacheHelper cache;
    private final ObjectMapper objectMapper;
    private final DomainMapper domainMapper;
    private final IntentMapper intentMapper;
    private final IntentSlotMapper slotMapper;
    private final IntentToolMapper intentToolMapper;
    private final ToolMapper toolMapper;

    /**
     * 按 domainCode 查找领域配置。
     *
     * @param domainCode 前端传入的领域标识，如 "ecommerce"
     * @return 完整领域配置，含意图、槽位、工具绑定；不存在返回 empty
     */
    public Optional<DomainConfig> findByCode(String domainCode) {
        String cacheKey = CACHE_KEY_PREFIX + domainCode;

        // 1. 尝试从 Redis 取
        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                return Optional.of(objectMapper.readValue(cached, DomainConfig.class));
            } catch (Exception e) {
                log.warn("[DIT] 领域配置缓存反序列化失败，回退 DB code={}", domainCode, e);
                cache.delete(cacheKey);
            }
        }

        // 2. Redis miss，查 DB
        Optional<DomainDO> domainOpt = domainMapper.findByCode(domainCode);
        if (domainOpt.isEmpty()) {
            log.debug("[DIT] 领域配置不存在 code={}", domainCode);
            return Optional.empty();
        }

        DomainConfig config = buildDomainConfig(domainOpt.get());

        // 3. 写入 Redis 缓存
        try {
            cache.set(cacheKey, objectMapper.writeValueAsString(config), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[DIT] 领域配置写缓存失败 code={}", domainCode, e);
        }

        log.debug("[DIT] 领域配置从 DB 加载 code={} intents={}", domainCode, config.intents().size());
        return Optional.of(config);
    }

    /**
     * 查询所有启用的领域配置（供域路由判断使用）。
     * 列表查询不走 Redis 缓存，每次直接读 DB，避免缓存一致性复杂度。
     *
     * @return 所有 enabled=true 的领域配置列表，按 id 升序
     */
    public List<DomainConfig> findAllEnabled() {
        List<DomainDO> domains = domainMapper.findAllEnabled();
        return domains.stream()
                .map(this::buildDomainConfig)
                .toList();
    }

    /**
     * 主动失效领域配置缓存（管理后台修改配置后调用）。
     *
     * @param domainCode 领域标识
     */
    public void evict(String domainCode) {
        cache.delete(CACHE_KEY_PREFIX + domainCode);
        log.info("[DIT] 领域配置缓存已失效 code={}", domainCode);
    }

    // ---- 私有：DB 数据组装 ----

    private DomainConfig buildDomainConfig(DomainDO domain) {
        List<IntentDO> intentDOs = intentMapper.findByDomainId(domain.getId());
        List<IntentConfig> intents = new ArrayList<>(intentDOs.size());

        for (IntentDO intentDO : intentDOs) {
            List<SlotConfig> slots = buildSlots(intentDO.getId());
            List<IntentToolBinding> bindings = buildToolBindings(intentDO.getId());
            intents.add(new IntentConfig(
                    intentDO.getCode(),
                    intentDO.getName(),
                    intentDO.getDescription(),
                    intentDO.getExampleQueries(),
                    Boolean.TRUE.equals(intentDO.getAutoTransfer()),
                    Boolean.TRUE.equals(intentDO.getSkipRag()),
                    intentDO.getFallbackReply(),
                    slots,
                    bindings
            ));
        }

        return new DomainConfig(
                domain.getCode(),
                domain.getName(),
                domain.getDescription(),
                domain.getSystemPromptAddon(),
                domain.getKnowledgeBaseId(),
                intents
        );
    }

    private List<SlotConfig> buildSlots(Long intentId) {
        List<IntentSlotDO> slotDOs = slotMapper.findByIntentId(intentId);
        List<SlotConfig> result = new ArrayList<>(slotDOs.size());
        for (IntentSlotDO s : slotDOs) {
            result.add(new SlotConfig(
                    s.getSlotName(),
                    s.getSlotType() != null ? s.getSlotType() : "string",
                    s.getDescription(),
                    Boolean.TRUE.equals(s.getRequired()),
                    parseJsonArray(s.getResolveStrategy()),
                    s.getSessionKey(),
                    s.getDiscoverToolCode(),
                    s.getDiscoverFixedParams(),
                    s.getAskUserPrompt(),
                    parseJsonArray(s.getEnumValues())
            ));
        }
        return result;
    }

    private List<IntentToolBinding> buildToolBindings(Long intentId) {
        List<IntentToolDO> bindingDOs = intentToolMapper.findByIntentId(intentId);
        if (bindingDOs.isEmpty()) return List.of();

        // 批量查询工具，消除 N+1 问题（阿里规约：禁止在循环中查询数据库）
        List<Long> toolIds = bindingDOs.stream().map(IntentToolDO::getToolId).toList();
        java.util.Map<Long, ToolDO> toolMap = toolMapper.selectBatchIds(toolIds).stream()
                .collect(java.util.stream.Collectors.toMap(ToolDO::getId, t -> t));

        List<IntentToolBinding> result = new ArrayList<>(bindingDOs.size());
        for (IntentToolDO b : bindingDOs) {
            ToolDO t = toolMap.get(b.getToolId());
            if (t == null) continue;
            ToolConfig toolConfig = new ToolConfig(
                    t.getCode(), t.getName(), t.getDescription(),
                    t.getToolType(), t.getHttpMethod(), t.getUrlTemplate(),
                    t.getHeadersTemplate(), t.getBodyTemplate(), t.getParamSchema(),
                    t.getResponseJsonpath(), t.getAuthType(), t.getAuthConfig(),
                    t.getTimeoutMs() != null ? t.getTimeoutMs() : 5000,
                    Boolean.TRUE.equals(t.getIsDiscoverTool())
            );
            result.add(new IntentToolBinding(
                    toolConfig,
                    b.getExecutionMode() != null ? b.getExecutionMode() : "OPTIONAL",
                    b.getExecutionOrder() != null ? b.getExecutionOrder() : 0,
                    b.getParamMappings()
            ));
        }
        return result;
    }

    /** 容错解析 JSON 数组字符串为 List<String>，失败返回空列表 */
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DIT] JSON 数组解析失败: {}", json);
            return List.of();
        }
    }
}
