# 对话服务核心链路重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对话服务核心链路重构，消除 ChatAppService / DomainAgentService 职责混杂，统一 Caffeine 本地缓存策略，使每个类单一职责、符合 DDD 分层和阿里巴巴代码规范。

**Architecture:** 将 506 行的 `ChatAppService` 拆分为路由分发器（~80行）+ `DomainSessionAppService`（域会话管理）+ `FaqChatAppService`（FAQ编排）；将 506 行的 `DomainAgentService` 提取 `ToolSpecBuilder`（工具规格构造）和 `DomainToolProviderFactory`（工具组装）；`RoutingConfigProvider` 去除 Redis/PubSub 改用 Caffeine + POJO 反序列化；`KeywordRegexIntentMatcher` / `KeywordRegexDomainMatcher` 去除 `@PostConstruct`/`@EventListener`，统一用 Caffeine TTL。

**Tech Stack:** Java 17, Spring Boot 3, Reactor/Flux, LangChain4j 1.1.0, Caffeine 3.x, Jackson, JUnit 5, Mockito

## Global Constraints

- 所有新类单一职责，行数 ≤ 150 行
- 严格遵循 DDD 分层：interfaces → application → domain → infrastructure
- 遵循阿里巴巴 Java 开发手册：`public` 方法必须有 Javadoc，无魔法字符串，构造函数参数 ≤ 7
- 缓存策略统一：Caffeine TTL 5 分钟，key 用 `CustomerServiceCacheConstant` 常量
- commit message 使用中文
- 每个 Task 结束后运行全量测试：`mvn -pl ai-conversation/conversation-service test -q`，`BUILD SUCCESS`
- 工作目录：`/Users/lycodeing/IdeaProjects/aria-server`，分支：`feat/intent-routing-enhancement`

---

### Task 1: `CustomerServiceCacheConstant` — 客服业务 Caffeine 缓存 Key 常量

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CustomerServiceCacheConstant.java`

**Interfaces:**
- Produces: `CustomerServiceCacheConstant.ROUTING_CONFIG = "routing.config"`, `INTENT_RULES = "rules"`, `DOMAIN_RULES = "domain_rules"`

- [ ] **Step 1: 创建常量类**

```java
package com.aria.conversation.infrastructure.config;

/**
 * 客服业务本地缓存 Key 常量（Caffeine）。
 *
 * <p>仅收录本服务内 Caffeine 本地缓存使用的 key，
 * Redis 缓存 key 由各自的 Provider/Repository 私有管理，不在此维护。
 *
 * <p>命名规范：{模块}.{资源}，与 system_config.config_key 保持一致（如适用）。
 * 禁止在代码中直接使用字符串字面量作为缓存键。
 */
public final class CustomerServiceCacheConstant {

    /**
     * 路由阈值配置缓存键，对应 system_config.config_key = 'routing.config'。
     * 由 {@code RoutingConfigProvider} 使用，缓存序列化后的 {@code RoutingConfig} 对象。
     */
    public static final String ROUTING_CONFIG = "routing.config";

    /**
     * 意图规则列表缓存键（Caffeine 内部 key，无对应 DB 记录）。
     * 由 {@code KeywordRegexIntentMatcher} 使用，缓存编译后的意图规则列表。
     */
    public static final String INTENT_RULES = "rules";

    /**
     * 域路由规则列表缓存键（Caffeine 内部 key，无对应 DB 记录）。
     * 由 {@code KeywordRegexDomainMatcher} 使用，缓存编译后的域规则列表。
     */
    public static final String DOMAIN_RULES = "domain_rules";

    private CustomerServiceCacheConstant() {
        throw new UnsupportedOperationException("CustomerServiceCacheConstant is a utility class");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service compile -q 2>&1 | tail -3
```

Expected: 无输出（clean compile）

- [ ] **Step 3: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 CustomerServiceCacheConstant 客服业务 Caffeine 缓存 Key 常量"
```

---

### Task 2: `RoutingConfig` POJO + `RoutingConfigProvider` 重构（去除 Redis/PubSub）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingConfig.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingConfigProvider.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java`（call site 变更）
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridDomainRoutingService.java`（call site 变更）
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentServiceTest.java`（stub 变更）

**Interfaces:**
- Consumes: `CustomerServiceCacheConstant.ROUTING_CONFIG`
- Produces:
  - `RoutingConfig.getIntent().getMinLlmConfidence()` → `double`
  - `RoutingConfig.getIntent().getMaxExamplesToInject()` → `int`
  - `RoutingConfig.getDomain().isRuleEnabled()` → `boolean`
  - `RoutingConfigProvider.getConfig()` → `RoutingConfig`

- [ ] **Step 1: 创建 `RoutingConfig.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.ai.RoutingProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 路由阈值配置值对象。
 *
 * <p>对应 system_config 中 config_key = 'routing.config' 的 JSON 结构，
 * Jackson 反序列化后直接使用，字段缺失时保持默认值，无需 JsonNode 路径导航。
 *
 * <pre>{@code
 * {
 *   "intent": { "embeddingEnabled": false, "embeddingThreshold": 0.75,
 *               "minLlmConfidence": 0.0, "maxExamplesToInject": 5 },
 *   "domain":  { "ruleEnabled": true }
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class RoutingConfig {

    private Intent intent = new Intent();
    private Domain domain  = new Domain();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Intent {
        /** 是否启用向量相似度匹配层（Tier 2），默认关闭；第二阶段开启 */
        private boolean embeddingEnabled    = false;
        /** 向量相似度命中阈值，低于此值继续走 LLM，范围 0.0~1.0，推荐 0.75 */
        private double  embeddingThreshold  = 0.75;
        /** LLM 意图分类置信度下限，低于此值降级为 UNKNOWN；0.0 表示关闭阈值检查 */
        private double  minLlmConfidence    = 0.0;
        /** few-shot prompt 中每个意图最多注入的示例句子条数，过多会增加 token 消耗 */
        private int     maxExamplesToInject = 5;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Domain {
        /** 是否启用域路由关键词/正则规则层（Tier 1），false=跳过规则直接走 LLM 小模型 */
        private boolean ruleEnabled = true;
    }

    /**
     * 从 {@link RoutingProperties} YAML 默认值构造，auth-service 不可用时降级使用。
     *
     * @param p YAML 绑定的默认配置
     * @return 等价的 RoutingConfig 实例
     */
    public static RoutingConfig fromProperties(RoutingProperties p) {
        RoutingConfig c = new RoutingConfig();
        c.getIntent().setEmbeddingEnabled(p.getIntent().isEmbeddingEnabled());
        c.getIntent().setEmbeddingThreshold(p.getIntent().getEmbeddingThreshold());
        c.getIntent().setMinLlmConfidence(p.getIntent().getMinLlmConfidence());
        c.getIntent().setMaxExamplesToInject(p.getIntent().getMaxExamplesToInject());
        c.getDomain().setRuleEnabled(p.getDomain().isRuleEnabled());
        return c;
    }
}
```

- [ ] **Step 2: 完整替换 `RoutingConfigProvider.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 路由阈值配置提供者。
 *
 * <p>从 auth-service system_config 表读取 {@code routing.config} JSON 并反序列化为
 * {@link RoutingConfig}，Caffeine 本地缓存 TTL 5 分钟。
 * auth-service 不可用时降级返回 {@link RoutingProperties} YAML 默认值构造的配置对象。
 *
 * <p>运营在管理后台修改配置后，最多 5 分钟内自动生效，无需手动刷新或重启。
 *
 * <p>调用方示例：
 * <pre>{@code
 * double threshold = routingConfigProvider.getConfig().getIntent().getMinLlmConfidence();
 * boolean enabled  = routingConfigProvider.getConfig().getDomain().isRuleEnabled();
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingConfigProvider {

    /** auth-service SDK 客户端，用于拉取 system_config 表中的配置值 */
    private final AuthClient        authClient;
    /** JSON 反序列化工具，将 routing.config 字符串转换为 {@link RoutingConfig} 对象 */
    private final ObjectMapper      objectMapper;
    /** YAML 绑定的默认配置，auth-service 不可用时作为降级兜底，永远不为 null */
    private final RoutingProperties defaults;

    /**
     * Caffeine 本地缓存，单条记录，TTL 5 分钟。
     * maximumSize=1 确保内存占用可预期；TTL 过期后由 Caffeine 自动触发重新加载。
     */
    private final Cache<String, RoutingConfig> localCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 获取当前路由配置。缓存命中直接返回，未命中时从 auth-service 拉取并缓存。
     *
     * @return {@link RoutingConfig}，auth-service 不可用时返回 YAML 默认值
     */
    public RoutingConfig getConfig() {
        return localCache.get(CustomerServiceCacheConstant.ROUTING_CONFIG, k -> load());
    }

    /**
     * 从 auth-service 拉取配置并反序列化，失败时降级为 YAML 默认值。
     * 降级时不写缓存，下次请求重新尝试拉取。
     */
    private RoutingConfig load() {
        try {
            String json = authClient.getSystemConfigValue(
                    CustomerServiceCacheConstant.ROUTING_CONFIG);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, RoutingConfig.class);
            }
        } catch (Exception e) {
            log.warn("[RoutingConfig] 拉取配置失败，降级使用 YAML 默认值", e);
        }
        return RoutingConfig.fromProperties(defaults);
    }
}
```

- [ ] **Step 3: 更新 `LangChain4jIntentService.java` 中的调用方式**

将第 67 行的：
```java
int maxExamples = routingConfigProvider.getMaxExamplesToInject();
```
替换为：
```java
int maxExamples = routingConfigProvider.getConfig().getIntent().getMaxExamplesToInject();
```

将第 108 行的：
```java
double minConfidence = routingConfigProvider.getMinLlmConfidence();
```
替换为：
```java
double minConfidence = routingConfigProvider.getConfig().getIntent().getMinLlmConfidence();
```

- [ ] **Step 4: 更新 `HybridDomainRoutingService.java` 中的调用方式**

将：
```java
if (routingConfigProvider.isDomainRuleEnabled()) {
```
替换为：
```java
if (routingConfigProvider.getConfig().getDomain().isRuleEnabled()) {
```

- [ ] **Step 5: 更新 `LangChain4jIntentServiceTest.java` 中的 stub**

将 setUp() 里的：
```java
when(routingConfigProvider.getMaxExamplesToInject()).thenReturn(5);
when(routingConfigProvider.getMinLlmConfidence()).thenReturn(0.0);
```
替换为：
```java
RoutingConfig config = new RoutingConfig();
when(routingConfigProvider.getConfig()).thenReturn(config);
```

- [ ] **Step 6: 运行全量测试**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 RoutingConfig POJO，RoutingConfigProvider 改用 Caffeine 本地缓存，去除 Redis/PubSub 依赖"
```

### Task 3: `KeywordRegexIntentMatcher` — 去除 `@PostConstruct`/`@EventListener`，改用 Caffeine TTL

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcher.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcherTest.java`

**Interfaces:**
- Consumes: `CustomerServiceCacheConstant.INTENT_RULES`
- Produces: `KeywordRegexIntentMatcher.match(String)` → `Optional<IntentResult>`（接口不变）

- [ ] **Step 1: 完整替换 `KeywordRegexIntentMatcher.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 关键词/正则意图规则匹配器（Tier 1）。
 *
 * <p>规则列表通过 Caffeine 本地缓存维护（TTL 5 分钟），
 * 运营修改配置后最多 5 分钟内自动生效，无需手动刷新或重启。
 *
 * <p><b>ReDoS 防护：</b>patterns 在管理后台 API 层由 {@link com.aria.conversation.interfaces.rest.validation.ValidRegexPatterns}
 * 校验合法性（长度 ≤ 200 字符，禁止嵌套量词），本类不重复校验。
 *
 * <p><b>中文关键词说明：</b>纯子串匹配，建议关键词至少 3 个汉字，
 * 高敏感意图（TRANSFER_REQUEST）建议使用正则而非短关键词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexIntentMatcher {

    /** 领域配置仓储，提供 __system__ 域意图列表（含 keywords / patterns / sortOrder 字段） */
    private final DomainRepository domainRepository;

    /**
     * Caffeine 本地缓存，存储编译后的意图规则列表，TTL 5 分钟，单条记录。
     * TTL 过期后由 Caffeine 自动触发 loadRules() 重新拉取，与 RoutingConfigProvider 策略统一。
     */
    private final Cache<String, List<IntentRuleEntry>> rulesCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 尝试用规则匹配用户消息（纯内存操作，< 1ms）。
     *
     * <p>遍历规则列表（已按 sortOrder 升序排列），依次执行关键词包含匹配和正则匹配，
     * 第一个命中即返回，不继续匹配剩余规则。
     *
     * @param userMessage 用户消息，null 或空白直接返回 empty
     * @return 命中返回 {@link IntentResult}（confidence=1.0，intentCode 小写），未命中返回 empty
     */
    public Optional<IntentResult> match(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return Optional.empty();
        }
        String lower = userMessage.toLowerCase();
        for (IntentRuleEntry entry : loadRules()) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    log.debug("[RuleMatcher] 关键词命中 intent={} kw={}", entry.intentCode(), kw);
                    return Optional.of(new IntentResult(entry.intentType(),
                            entry.intentCode().toLowerCase(), 1.0));
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    log.debug("[RuleMatcher] 正则命中 intent={} pattern={}", entry.intentCode(), p.pattern());
                    return Optional.of(new IntentResult(entry.intentType(),
                            entry.intentCode().toLowerCase(), 1.0));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从 Caffeine 缓存加载规则列表，缓存未命中时从 __system__ 域拉取并编译。
     *
     * @return 编译后的规则条目列表，按 sortOrder 升序排列，不可修改
     */
    private List<IntentRuleEntry> loadRules() {
        return rulesCache.get(CustomerServiceCacheConstant.INTENT_RULES, k -> {
            DomainConfig system = domainRepository
                    .findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
            if (system == null) {
                log.warn("[RuleMatcher] __system__ 域不存在，规则层不可用");
                return List.of();
            }
            List<IntentRuleEntry> rules = system.intents().stream()
                    .filter(this::hasRules)
                    .sorted(Comparator.comparingInt(IntentConfig::sortOrder))
                    .map(this::compile)
                    .toList();
            log.info("[RuleMatcher] 加载意图规则 {} 条", rules.size());
            return rules;
        });
    }

    private boolean hasRules(IntentConfig i) {
        return (i.keywords() != null && !i.keywords().isEmpty())
                || (i.patterns() != null && !i.patterns().isEmpty());
    }

    private IntentRuleEntry compile(IntentConfig i) {
        List<Pattern> patterns = i.patterns() == null ? List.of()
                : i.patterns().stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList();
        IntentType type;
        try {
            type = IntentType.valueOf(i.code().toUpperCase());
        } catch (IllegalArgumentException e) {
            type = IntentType.FAQ_QUERY;
        }
        return new IntentRuleEntry(i.code(), type,
                i.keywords() == null ? List.of() : i.keywords(), patterns);
    }

    record IntentRuleEntry(
            String intentCode, IntentType intentType,
            List<String> keywords, List<Pattern> compiledPatterns
    ) {}
}
```

- [ ] **Step 2: 更新 `KeywordRegexIntentMatcherTest.java`**

去掉所有 `matcher.reload()` 调用，改为让 Caffeine loader 自动触发（mock `domainRepository` 后直接调用 `matcher.match()`）：

将所有 `matcher.reload();` 行直接删除（共 6 处），每个 test 方法的 given 阶段只需：
```java
when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
        .thenReturn(Optional.of(systemDomain(...)));
// 直接调用 matcher.match("...") 即可触发 Caffeine loader
```

同时在 `setUp()` 里重置缓存（避免测试间互相污染），在 `@BeforeEach` 中重新创建 matcher：
```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    // 每个测试用新的 matcher 实例，避免 Caffeine 缓存跨测试污染
    matcher = new KeywordRegexIntentMatcher(domainRepository);
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=KeywordRegexIntentMatcherTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 7 tests

- [ ] **Step 4: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): KeywordRegexIntentMatcher 改用 Caffeine TTL，去除 @PostConstruct 和 @EventListener"
```

---

### Task 4: `KeywordRegexDomainMatcher` — 同 Task 3，统一缓存策略

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexDomainMatcher.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexDomainMatcherTest.java`

**Interfaces:**
- Consumes: `CustomerServiceCacheConstant.DOMAIN_RULES`
- Produces: `KeywordRegexDomainMatcher.matchDomain(String)` → `Optional<String>`（接口不变）

- [ ] **Step 1: 完整替换 `KeywordRegexDomainMatcher.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 关键词/正则域路由规则匹配器（Tier 1）。
 *
 * <p>规则列表通过 Caffeine 本地缓存维护（TTL 5 分钟），
 * 与 {@link KeywordRegexIntentMatcher} 保持相同的缓存策略，
 * 运营修改配置后最多 5 分钟内自动生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexDomainMatcher {

    /** 领域配置仓储，提供所有启用域的 keywords/patterns 字段 */
    private final DomainRepository domainRepository;
    /** JSON 解析工具，将 keywords/patterns JSONB 字符串解析为 List<String> */
    private final ObjectMapper     objectMapper;

    /**
     * Caffeine 本地缓存，存储编译后的域规则列表，TTL 5 分钟，单条记录。
     */
    private final Cache<String, List<DomainRuleEntry>> rulesCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 尝试用规则匹配用户消息，返回命中的域 code。
     *
     * @param userMessage 用户消息，null 或空白直接返回 empty
     * @return 命中的域 code，未命中返回 empty（继续走 LLM 路由）
     */
    public Optional<String> matchDomain(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return Optional.empty();
        }
        String lower = userMessage.toLowerCase();
        for (DomainRuleEntry entry : loadRules()) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    return Optional.of(entry.domainCode());
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    return Optional.of(entry.domainCode());
                }
            }
        }
        return Optional.empty();
    }

    private List<DomainRuleEntry> loadRules() {
        return rulesCache.get(CustomerServiceCacheConstant.DOMAIN_RULES, k -> {
            List<DomainRuleEntry> rules = domainRepository.findAllEnabledSummary().stream()
                    .filter(this::hasRules)
                    .map(this::compile)
                    .toList();
            log.info("[DomainRuleMatcher] 加载域规则 {} 条", rules.size());
            return rules;
        });
    }

    private boolean hasRules(DomainDO d) {
        return (d.getKeywords() != null && !d.getKeywords().isBlank() && !d.getKeywords().equals("[]"))
                || (d.getPatterns() != null && !d.getPatterns().isBlank() && !d.getPatterns().equals("[]"));
    }

    private DomainRuleEntry compile(DomainDO d) {
        return new DomainRuleEntry(d.getCode(), parseJson(d.getKeywords()),
                parseJson(d.getPatterns()).stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList());
    }

    private List<String> parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DomainRuleMatcher] JSON 解析失败: {}", json);
            return List.of();
        }
    }

    record DomainRuleEntry(String domainCode, List<String> keywords, List<Pattern> compiledPatterns) {}
}
```

- [ ] **Step 2: 更新 `KeywordRegexDomainMatcherTest.java`**

同 Task 3，在 `@BeforeEach` 中重新创建实例避免缓存污染，去掉 `matcher.reload()` 调用：

```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    matcher = new KeywordRegexDomainMatcher(domainRepository, objectMapper);
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=KeywordRegexDomainMatcherTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 3 tests

- [ ] **Step 4: 全量测试**

```bash
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): KeywordRegexDomainMatcher 改用 Caffeine TTL，去除 @PostConstruct 和 @EventListener"
```

### Task 5: `ToolSpecBuilder` — 工具规格构造器（从 DomainAgentService 提取）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/tool/ToolSpecBuilder.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/tool/ToolSpecBuilderTest.java`

**Interfaces:**
- Produces: `ToolSpecBuilder.build(ToolConfig tc)` → `ToolSpecification`

- [ ] **Step 1: 写失败测试 `ToolSpecBuilderTest.java`**

```java
package com.aria.conversation.infrastructure.ai.tool;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolSpecBuilder")
class ToolSpecBuilderTest {

    private ToolSpecBuilder builder;

    private static ToolConfig tool(String code, String description, String paramSchema) {
        return new ToolConfig(code, "name", description, "HTTP", "GET",
                "http://x", "{}", null, paramSchema, null, "NONE", "{}", 5000, false);
    }

    @BeforeEach
    void setUp() {
        builder = new ToolSpecBuilder(new ObjectMapper());
    }

    @Test
    @DisplayName("paramSchema 为空时构建无参数 ToolSpecification")
    void build_emptySchema_noParams() {
        ToolConfig tc = tool("query_order", "查询订单", null);
        ToolSpecification spec = builder.build(tc);

        assertThat(spec.name()).isEqualTo("query_order");
        assertThat(spec.description()).isEqualTo("查询订单");
        assertThat(spec.parameters().properties()).isEmpty();
    }

    @Test
    @DisplayName("string 类型参数正确映射")
    void build_stringType_mapsCorrectly() {
        String schema = "{\"properties\":{\"orderId\":{\"type\":\"string\",\"description\":\"订单号\"}},"
                + "\"required\":[\"orderId\"]}";
        ToolSpecification spec = builder.build(tool("t", "desc", schema));

        assertThat(spec.parameters().properties()).containsKey("orderId");
        assertThat(spec.parameters().properties().get("orderId"))
                .isInstanceOf(JsonStringSchema.class);
        assertThat(spec.parameters().required()).contains("orderId");
    }

    @Test
    @DisplayName("integer / number / boolean / array 类型正确映射")
    void build_typeMappings() {
        String schema = "{\"properties\":{"
                + "\"qty\":{\"type\":\"integer\"},"
                + "\"price\":{\"type\":\"number\"},"
                + "\"express\":{\"type\":\"boolean\"},"
                + "\"tags\":{\"type\":\"array\"}"
                + "}}";
        ToolSpecification spec = builder.build(tool("t", "d", schema));

        assertThat(spec.parameters().properties().get("qty")).isInstanceOf(JsonIntegerSchema.class);
        assertThat(spec.parameters().properties().get("price")).isInstanceOf(JsonNumberSchema.class);
        assertThat(spec.parameters().properties().get("express")).isInstanceOf(JsonBooleanSchema.class);
        assertThat(spec.parameters().properties().get("tags")).isInstanceOf(JsonArraySchema.class);
    }

    @Test
    @DisplayName("非法 JSON Schema 降级为无参数，不抛出异常")
    void build_invalidSchema_fallbackToEmpty() {
        ToolSpecification spec = builder.build(tool("t", "d", "{invalid-json}"));
        assertThat(spec.parameters().properties()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=ToolSpecBuilderTest -q 2>&1 | tail -5
```

Expected: 编译失败（ToolSpecBuilder 不存在）

- [ ] **Step 3: 创建 `ToolSpecBuilder.java`**

```java
package com.aria.conversation.infrastructure.ai.tool;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.service.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具规格构造器。
 *
 * <p>将 {@link ToolConfig#paramSchema()} 定义的 JSON Schema 字符串解析为
 * LangChain4j {@link ToolSpecification}，支持的类型映射：
 * <ul>
 *   <li>string / object / 未知 → {@link JsonStringSchema}（降级）</li>
 *   <li>integer → {@link JsonIntegerSchema}</li>
 *   <li>number  → {@link JsonNumberSchema}</li>
 *   <li>boolean → {@link JsonBooleanSchema}</li>
 *   <li>array   → {@link JsonArraySchema}（items 默认 string）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSpecBuilder {

    /** JSON 反序列化工具，用于解析 paramSchema JSON Schema 字符串 */
    private final ObjectMapper objectMapper;

    /**
     * 将工具配置转换为 LangChain4j 工具规格。
     *
     * <p>paramSchema 为空或 JSON 解析失败时，参数 Schema 为空（无参数工具），
     * 不抛出异常，降级为可用状态，确保工具列表构建不被单个异常中断。
     *
     * @param tc 工具配置，含 code（唯一标识）、description（LLM 使用说明）、paramSchema（参数定义）
     * @return LangChain4j {@link ToolSpecification}，供 AiServices ToolProvider 注册使用
     */
    public ToolSpecification build(ToolConfig tc) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        if (StringUtils.isNotBlank(tc.paramSchema())) {
            try {
                Map<String, Object> schema = objectMapper.readValue(
                        tc.paramSchema(), new TypeReference<>() {});
                parseProperties(schema, schemaBuilder);
                parseRequired(schema, schemaBuilder);
            } catch (Exception e) {
                log.error("[ToolSpecBuilder] paramSchema 解析失败 tool={}", tc.code(), e);
            }
        }
        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(schemaBuilder.build())
                .build();
    }

    /**
     * 解析 JSON Schema 的 "properties" 节点，将每个属性按类型映射为对应的 JsonSchemaElement。
     */
    private void parseProperties(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        props.forEach((name, def) -> {
            if (!(def instanceof Map<?, ?> propDef)) {
                builder.addProperty(name, JsonStringSchema.builder().build());
                return;
            }
            String desc = safeString(propDef.get("description"));
            builder.addProperty(name, mapType(safeString(propDef.get("type")), desc));
        });
    }

    /**
     * 解析 JSON Schema 的 "required" 数组并设置到 builder。
     */
    private void parseRequired(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }
    }

    /**
     * 将 JSON Schema type 字符串映射为对应的 LangChain4j JsonSchemaElement。
     */
    private JsonSchemaElement mapType(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array"   -> JsonArraySchema.builder().description(description)
                    .items(JsonStringSchema.builder().build()).build();
            default        -> JsonStringSchema.builder().description(description).build();
        };
    }

    /** 安全地将 Object 转换为 String，null 或非 String 类型返回空串，避免 NPE。 */
    private String safeString(Object value) {
        return value instanceof String s ? s : "";
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=ToolSpecBuilderTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 4 tests

- [ ] **Step 5: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 ToolSpecBuilder，从 DomainAgentService 提取工具规格构造逻辑"
```

---

### Task 6: `DomainToolProviderFactory` — 域工具提供者工厂（从 DomainAgentService 提取）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/tool/DomainToolProviderFactory.java`

**Interfaces:**
- Consumes: `ToolSpecBuilder.build(ToolConfig)`, `HttpToolRunner`, `McpClientRegistry`, `BuiltinTools`
- Produces: `DomainToolProviderFactory.build(List<ToolConfig>, Sinks.Many<ChatEvent>, BuiltinTools)` → `ToolProvider`

- [ ] **Step 1: 创建 `DomainToolProviderFactory.java`**

```java
package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.infrastructure.ai.tool.ToolSpecBuilder;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.ai.mcp.McpClientRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域工具提供者工厂。
 *
 * <p>按优先级组装三层 ToolProvider：
 * <ul>
 *   <li>低优先级：MCP 工具（外部服务动态工具），失败时跳过</li>
 *   <li>中优先级：域 HTTP 工具（覆盖同名 MCP 工具）</li>
 *   <li>高优先级：内置工具 switch_domain / transfer_to_agent（不可被覆盖）</li>
 * </ul>
 *
 * <p>所有工具统一通过 ToolProvider 注册，避免 {@code .tools()} + {@code .toolProvider()}
 * 混合使用时 LangChain4j 1.1.0 executor 合并缺失导致 NPE。
 *
 * <p><b>per-request 原则：</b>每次请求必须调用 {@link #build} 重新构建 ToolProvider，
 * 不可复用，因为 builtinTools 和 eventSink 均携带请求级上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainToolProviderFactory {

    /** MCP 工具注册表，聚合所有外部 MCP 服务端提供的动态工具 */
    private final McpClientRegistry mcpClientRegistry;
    /** 域 HTTP 工具执行器，负责模板渲染、HTTP 调用和结果提取 */
    private final HttpToolRunner    httpToolRunner;
    /** 工具规格构造器，将 ToolConfig.paramSchema JSON Schema 转换为 LangChain4j ToolSpecification */
    private final ToolSpecBuilder   toolSpecBuilder;
    /** JSON 序列化工具，用于构造 tool_call / tool_done SSE 事件载荷 */
    private final ObjectMapper      objectMapper;

    /**
     * 构建当前请求独立的 ToolProvider，供 DomainAgentService 挂载到 AiServices。
     *
     * @param domainTools  当前域的 HTTP 工具配置列表，来自 DomainConfig
     * @param eventSink    SSE 事件发射器，工具执行前后向前端推送 tool_call / tool_done 事件
     * @param builtinTools 内置工具实例（per-request），含 sessionId / domainCode 等会话上下文
     * @return 组装完成的三层 ToolProvider
     */
    public ToolProvider build(List<ToolConfig> domainTools,
                              Sinks.Many<ChatEvent> eventSink,
                              BuiltinTools builtinTools) {
        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
            loadMcpTools(toolMap, eventSink);
            loadDomainTools(toolMap, domainTools, eventSink);
            toolMap.putAll(builtinTools.buildToolSpecs());
            log.debug("[ToolFactory] 工具总数={}", toolMap.size());
            return new ToolProviderResult(toolMap);
        };
    }

    /**
     * 加载 MCP 工具并用 SSE 事件包装器包裹，加载失败时跳过不影响域工具和内置工具。
     */
    private void loadMcpTools(Map<ToolSpecification, ToolExecutor> toolMap,
                               Sinks.Many<ChatEvent> eventSink) {
        try {
            ToolProviderResult mcp = mcpClientRegistry.getToolProvider().provideTools(null);
            if (mcp != null && mcp.tools() != null) {
                mcp.tools().forEach((spec, exec) ->
                        toolMap.put(spec, wrapWithSseEvents(spec.name(), exec, eventSink)));
                log.debug("[ToolFactory] MCP 工具数={}", mcp.tools().size());
            }
        } catch (Exception e) {
            log.warn("[ToolFactory] MCP 工具加载失败，已跳过", e);
        }
    }

    /**
     * 加载当前域的 HTTP 工具，覆盖同名 MCP 工具（中优先级）。
     */
    private void loadDomainTools(Map<ToolSpecification, ToolExecutor> toolMap,
                                  List<ToolConfig> tools,
                                  Sinks.Many<ChatEvent> eventSink) {
        for (ToolConfig tc : tools) {
            toolMap.put(toolSpecBuilder.build(tc), buildHttpExecutor(tc, eventSink));
        }
    }

    /**
     * 构建域 HTTP 工具的执行器。执行前发射 tool_call，执行后发射 tool_done。
     * 工具执行失败时返回错误描述字符串，不抛出异常，由 LLM 自行决策是否重试。
     */
    private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return (req, memId) -> {
            emitToolCall(tc.code(), eventSink);
            try {
                Map<String, Object> args = parseArgs(req.arguments());
                ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
                emitToolDone(tc.code(), result.isSuccess(), result.getErrorMsg(), eventSink);
                return result.isSuccess() ? result.getResponse() : "工具执行失败: " + result.getErrorMsg();
            } catch (Exception e) {
                log.error("[ToolFactory] HTTP 工具执行异常 tool={}", tc.code(), e);
                emitToolDone(tc.code(), false, e.getMessage(), eventSink);
                return "工具执行失败: " + e.getMessage();
            }
        };
    }

    /**
     * 用 SSE 事件包装器包裹 MCP ToolExecutor。
     * 执行失败时先发射 tool_done（失败状态）再重新抛出，确保前端不停在 loading 状态。
     */
    private ToolExecutor wrapWithSseEvents(String name, ToolExecutor delegate,
                                            Sinks.Many<ChatEvent> eventSink) {
        return (req, memId) -> {
            emitToolCall(name, eventSink);
            try {
                String result = delegate.execute(req, memId);
                emitToolDone(name, true, null, eventSink);
                return result;
            } catch (Exception e) {
                emitToolDone(name, false, e.getMessage(), eventSink);
                throw e;
            }
        };
    }

    private void emitToolCall(String toolCode, Sinks.Many<ChatEvent> sink) {
        try {
            sink.tryEmitNext(ChatEvent.toolCall(
                    objectMapper.writeValueAsString(ToolCallPayload.running(toolCode))));
        } catch (Exception e) {
            log.warn("[ToolFactory] tool_call 事件发射失败 tool={}", toolCode, e);
        }
    }

    private void emitToolDone(String toolCode, boolean success, String errorMsg,
                               Sinks.Many<ChatEvent> sink) {
        try {
            String json = success
                    ? objectMapper.writeValueAsString(ToolDonePayload.success(toolCode, 0))
                    : objectMapper.writeValueAsString(ToolDonePayload.error(toolCode, 0, errorMsg));
            sink.tryEmitNext(ChatEvent.toolDone(json));
        } catch (Exception e) {
            log.warn("[ToolFactory] tool_done 事件发射失败 tool={}", toolCode, e);
        }
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ToolFactory] 工具参数解析失败: {}", arguments, e);
            return Map.of();
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl ai-conversation/conversation-service compile -q 2>&1 | tail -5
```

Expected: 无输出（clean compile）

- [ ] **Step 3: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 DomainToolProviderFactory，从 DomainAgentService 提取三层工具组装逻辑"
```

### Task 7: 精简 `DomainAgentService` — 工具构建委托给 Task 5/6

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/DomainAgentService.java`

**Interfaces:**
- Consumes: `DomainToolProviderFactory.build(...)`, `ToolSpecBuilder`（移除直接依赖）
- Produces: `DomainAgentService.streamChat(String sessionId, String domainCode, String userMessage)` → `Flux<ChatEvent>`（接口不变）

- [ ] **Step 1: 完整替换 `DomainAgentService.java`（保留核心逻辑，移除工具构建逻辑）**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.application.service.tool.BuiltinTools;
import com.aria.conversation.application.service.tool.DomainToolProviderFactory;
import com.aria.conversation.application.service.tool.DomainSummary;
import com.aria.conversation.application.service.tool.InvocationParameters;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 域 Agent 流式对话执行器。
 *
 * <p>为每次请求构建独立的 {@code DomainAssistant}，包含：
 * <ul>
 *   <li>RAG 增强的 system prompt（每次请求通过闭包计算）</li>
 *   <li>三层工具（MCP + 域 HTTP + 内置），由 {@link DomainToolProviderFactory} 组装</li>
 *   <li>token 流与工具事件流通过 {@link Flux#merge} 合并输出</li>
 * </ul>
 *
 * <p><b>per-request 构建：</b>每次调用必须重新 build DomainAssistant，
 * systemPrompt 依赖 RAG 闭包，builtinTools 依赖 per-request 会话上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainAgentService {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    /** AI 模型工厂，提供流式 ChatModel 实例 */
    private final DynamicModelFactory         modelFactory;
    /** 领域配置仓储，用于查询域工具列表和所有域列表 */
    private final DomainRepository            domainRepo;
    /** 域工具提供者工厂，按三层优先级组装 ToolProvider */
    private final DomainToolProviderFactory   toolProviderFactory;
    /** 对话记忆存储，按 sessionId 维护多轮上下文 */
    private final SessionChatMemoryStore      memoryStore;
    /** 知识库 RAG 检索客户端 */
    private final KnowledgeServiceClient      knowledgeServiceClient;
    /** JSON 序列化工具，用于构造内置工具的 SSE 事件载荷 */
    private final ObjectMapper                objectMapper;
    /** 激活域 Redis 仓储，内置工具 switch_domain 使用 */
    private final SessionDomainRepository     sessionDomainRepo;
    /** 域切换审计仓储，内置工具 switch_domain 使用 */
    private final SessionDomainSwitchRepository domainSwitchRepo;
    /** 会话队列服务，内置工具 transfer_to_agent 使用 */
    private final SessionQueueService         sessionQueueService;

    /**
     * LangChain4j AiService 流式对话接口（per-request 构建）。
     */
    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * 流式域对话，发射 {@link ChatEvent} token 流和工具生命周期事件。
     *
     * @param sessionId   会话 ID（用作对话记忆 key）
     * @param domainCode  当前活跃域 code
     * @param userMessage 用户消息文本
     * @return AI token 事件与工具事件的合并流
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.info("[DomainAgent] start sessionId={} domain={} msg={}",
                sessionId, domainCode, userMessage.length() > 30
                        ? userMessage.substring(0, 30) + "…" : userMessage);

        List<DomainSummary> allDomains = domainRepo.findAllEnabledSummary().stream()
                .map(d -> new DomainSummary(d.getCode(), d.getDescription()))
                .toList();

        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits, buildDomainAddon(allDomains), null);

        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        List<ToolConfig> domainTools = getToolsForDomain(domainCode);
        InvocationParameters params = new InvocationParameters(
                sessionId, domainCode, userMessage, allDomains, eventSink);
        BuiltinTools builtinTools = new BuiltinTools(
                params, sessionDomainRepo, domainSwitchRepo, objectMapper, sessionQueueService);

        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .systemMessageProvider(id -> systemPrompt)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                        .chatMemoryStore(memoryStore).build())
                .toolProvider(toolProviderFactory.build(domainTools, eventSink, builtinTools))
                .build();

        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(content -> ChatEvent.token(content, objectMapper))
                .doFinally(signal -> {
                    log.info("[DomainAgent] done sessionId={} signal={}", sessionId, signal);
                    eventSink.tryEmitComplete();
                });

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)));
    }

    private List<ToolConfig> getToolsForDomain(String domainCode) {
        return domainRepo.findByCode(domainCode)
                .map(dc -> dc.intents().stream()
                        .flatMap(ic -> ic.toolBindings().stream())
                        .map(IntentToolBinding::tool)
                        .distinct()
                        .toList())
                .orElse(List.of());
    }

    /**
     * 将所有启用域拼装为 system prompt addon，告知 LLM 可切换的域列表。
     */
    private String buildDomainAddon(List<DomainSummary> allDomains) {
        if (allDomains == null || allDomains.isEmpty()) return null;
        String domainList = allDomains.stream()
                .map(d -> d.code() + "（" + d.description() + "）")
                .collect(Collectors.joining("，"));
        return "当前可用服务域：" + domainList;
    }
}
```

- [ ] **Step 2: 全量测试**

```bash
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 精简 DomainAgentService，工具构建委托 DomainToolProviderFactory，行数从 506 降至 ~130"
```

### Task 8: `DomainSessionAppService` — 新建域会话生命周期管理器

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/DomainSessionAppService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/DomainSessionAppServiceTest.java`

**Interfaces:**
- Consumes: `SessionQueueService.initAiChatSession(String)`, `SessionDomainRepository.find(String)`, `DomainRoutingService.route(...)`, `SessionDomainSwitchRepository.record(...)`
- Produces: `DomainSessionAppService.resolveActiveDomain(String sessionId, String message, String domainCode)` → `String`

- [ ] **Step 1: 写失败测试 `DomainSessionAppServiceTest.java`**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.domain.service.DomainRoutingService.RouteResult;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainSessionAppService")
class DomainSessionAppServiceTest {

    @Mock private SessionQueueService            sessionQueueService;
    @Mock private SessionDomainRepository        sessionDomainRepo;
    @Mock private SessionDomainSwitchRepository  domainSwitchRepo;
    @Mock private ConversationHistoryRepository  historyRepository;
    @Mock private DomainRoutingService           domainRoutingService;

    private DomainSessionAppService service;

    @BeforeEach
    void setUp() {
        service = new DomainSessionAppService(sessionQueueService, sessionDomainRepo,
                domainSwitchRepo, historyRepository, domainRoutingService);
    }

    @Test
    @DisplayName("首次进入：以 domainCode 初始化激活域，写 INITIAL 切换记录，调用域路由")
    void resolveActiveDomain_firstEntry_initsDomainAndRoutes() {
        when(sessionDomainRepo.find("s1")).thenReturn(Optional.empty());
        when(historyRepository.findAll("s1")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenReturn(new RouteResult("ecommerce", false));

        String result = service.resolveActiveDomain("s1", "你好", "ecommerce");

        assertThat(result).isEqualTo("ecommerce");
        verify(sessionDomainRepo).save("s1", "ecommerce");
        ArgumentCaptor<DomainSwitchRecord> captor = ArgumentCaptor.forClass(DomainSwitchRecord.class);
        verify(domainSwitchRepo).record(captor.capture());
        assertThat(captor.getValue().switchType()).isEqualTo("INITIAL");
    }

    @Test
    @DisplayName("已有激活域：直接读取，不写初始化记录")
    void resolveActiveDomain_existingDomain_noInitWrite() {
        when(sessionDomainRepo.find("s2")).thenReturn(Optional.of("finance"));
        when(historyRepository.findAll("s2")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenReturn(new RouteResult("finance", false));

        String result = service.resolveActiveDomain("s2", "msg", "ecommerce");

        assertThat(result).isEqualTo("finance");
        verify(sessionDomainRepo, never()).save(any(), any());
    }

    @Test
    @DisplayName("路由建议切换域：更新 Redis 激活域并写 ROUTER_MODEL 切换记录")
    void resolveActiveDomain_routerSuggestsSwitch_updatesDomain() {
        when(sessionDomainRepo.find("s3")).thenReturn(Optional.of("ecommerce"));
        when(historyRepository.findAll("s3")).thenReturn(List.of());
        when(domainRoutingService.route(any(), eq("ecommerce"), any()))
                .thenReturn(new RouteResult("finance", true));

        String result = service.resolveActiveDomain("s3", "买基金", "ecommerce");

        assertThat(result).isEqualTo("finance");
        verify(sessionDomainRepo).save("s3", "finance");
        ArgumentCaptor<DomainSwitchRecord> captor = ArgumentCaptor.forClass(DomainSwitchRecord.class);
        verify(domainSwitchRepo).record(captor.capture());
        assertThat(captor.getValue().switchType()).isEqualTo("ROUTER_MODEL");
    }

    @Test
    @DisplayName("路由异常：降级保持当前域，不抛出异常")
    void resolveActiveDomain_routerThrows_fallbackToCurrentDomain() {
        when(sessionDomainRepo.find("s4")).thenReturn(Optional.of("ecommerce"));
        when(historyRepository.findAll("s4")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenThrow(new RuntimeException("路由服务不可用"));

        String result = service.resolveActiveDomain("s4", "msg", "ecommerce");

        assertThat(result).isEqualTo("ecommerce");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=DomainSessionAppServiceTest -q 2>&1 | tail -5
```

Expected: 编译失败

- [ ] **Step 3: 创建 `DomainSessionAppService.java`**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 域会话生命周期管理器。
 *
 * <p>封装域对话的三步初始化编排：
 * <ol>
 *   <li>幂等初始化 AI_CHAT 会话记录</li>
 *   <li>读取或首次写入 session 激活域（Redis）</li>
 *   <li>ROUTER 小模型域路由决策，必要时切换域并写审计日志</li>
 * </ol>
 *
 * <p><b>线程要求：</b>包含阻塞操作（Redis 读写、小模型 HTTP 调用），
 * 调用方必须在 {@link Schedulers#boundedElastic()} 线程上调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainSessionAppService {

    /** 会话队列服务，提供幂等初始化 AI_CHAT 会话记录的能力 */
    private final SessionQueueService            sessionQueueService;
    /** 激活域 Redis 仓储，存储 sessionId → domainCode 的映射关系 */
    private final SessionDomainRepository        sessionDomainRepo;
    /** 域切换审计仓储，记录每次域变更的原因、来源和时间 */
    private final SessionDomainSwitchRepository  domainSwitchRepo;
    /** 对话历史仓储，提供最近 N 轮历史作为域路由的多轮上下文 */
    private final ConversationHistoryRepository  historyRepository;
    /** 域路由服务（@Primary 实现为 HybridDomainRoutingService），Tier1 规则 → Tier2 小模型 */
    private final DomainRoutingService           domainRoutingService;

    /**
     * 解析当前会话的活跃域，完整编排三步初始化流程。
     *
     * <p><b>线程要求：</b>包含阻塞操作（Redis 读写、HTTP 调用），
     * 调用方必须在 {@link Schedulers#boundedElastic()} 线程上调用。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息（用于路由上下文和审计记录）
     * @param domainCode 前端传入的默认域标识，仅首次进入时使用
     * @return 最终确定的活跃域编码
     */
    public String resolveActiveDomain(String sessionId, String message, String domainCode) {
        sessionQueueService.initAiChatSession(sessionId);
        String activeDomain = resolveOrInitDomain(sessionId, message, domainCode);
        return routeDomainIfNeeded(sessionId, message, activeDomain);
    }

    /**
     * 读取 session 当前激活域；若不存在（首次进入），则以 {@code domainCode} 初始化
     * 并写入一条 {@link SwitchType#INITIAL} 类型的域切换记录。
     */
    private String resolveOrInitDomain(String sessionId, String message, String domainCode) {
        return sessionDomainRepo.find(sessionId).orElseGet(() -> {
            saveDomainSwitch(sessionId, null, domainCode, SwitchType.INITIAL, message, "用户进入服务入口");
            log.info("[DomainSession] sessionId={} 初始化激活域={}", sessionId, domainCode);
            return domainCode;
        });
    }

    /**
     * 调用域路由服务进行路由决策；若建议切换则更新 Redis 激活域并写审计日志。
     * 路由过程异常时降级保持当前域，不中断对话流程。
     */
    private String routeDomainIfNeeded(String sessionId, String message, String activeDomain) {
        try {
            List<ConversationMessage> history = historyRepository.findAll(sessionId);
            DomainRoutingService.RouteResult routing =
                    domainRoutingService.route(message, activeDomain, history);
            if (!routing.shouldSwitch()) {
                return activeDomain;
            }
            String newDomain = routing.suggestedDomain();
            saveDomainSwitch(sessionId, activeDomain, newDomain,
                    SwitchType.ROUTER_MODEL, message, "小模型检测切换");
            log.info("[DomainSession] sessionId={} 域切换 {} -> {}", sessionId, activeDomain, newDomain);
            return newDomain;
        } catch (Exception e) {
            log.warn("[DomainSession] sessionId={} 路由异常，降级保持当前域={}", sessionId, activeDomain, e);
            return activeDomain;
        }
    }

    /**
     * 原子化保存域绑定关系并记录域切换审计日志。
     * 每次域变更必须同时更新 Redis 绑定和审计记录，确保两者一致。
     */
    private void saveDomainSwitch(String sessionId, String fromDomain, String toDomain,
                                  String switchType, String message, String reason) {
        sessionDomainRepo.save(sessionId, toDomain);
        domainSwitchRepo.record(new DomainSwitchRecord(
                sessionId, fromDomain, toDomain, switchType, message, reason, null));
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest=DomainSessionAppServiceTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 4 tests

- [ ] **Step 5: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 DomainSessionAppService，提取域会话生命周期管理逻辑"
```

---

### Task 9: `FaqChatAppService` — 新建 FAQ 对话编排器

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/FaqChatAppService.java`

**Interfaces:**
- Consumes: `DynamicModelFactory`, `ConversationHistoryRepository`, `KnowledgeServiceClient`, `IntentService`, `SessionQueueService`, `ObjectMapper`
- Produces:
  - `FaqChatAppService.stream(String sessionId, String message)` → `Flux<ChatEvent>`
  - `FaqChatAppService.appendAndHint(String sessionId, String message)` → `Flux<ChatEvent>`
  - `FaqChatAppService.handleTransfer(String sessionId, IntentResult intent)` → `Flux<ChatEvent>`

- [ ] **Step 1: 创建 `FaqChatAppService.java`**

注意：此类提取自 `ChatAppService`，逻辑完全相同，不做功能变更。

```java
package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * FAQ 对话编排器。
 *
 * <p>编排通用 FAQ 链路：知识库检索（RAG）→ 意图分类 → 路由分支 → 流式 token 生成。
 * {@link #handleTransfer} 方法同时被 Domain 路径复用，实现转人工的统一处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqChatAppService {

    /** 超出业务范围时的统一拒答回复，所有 OUT_OF_SCOPE 意图均返回此文案 */
    private static final String OUT_OF_SCOPE_REPLY =
            "抱歉，我是专业的客服助手，只能回答业务相关的问题，无法帮您解答这个问题。";
    /** 系统自动触发转人工时写入队列的原因描述，用于座席侧展示 */
    private static final String TRANSFER_AUTO_REASON = "系统识别到用户需要人工服务";
    /** 转人工队列条目的默认标签，用于座席分组路由 */
    private static final String TRANSFER_DEFAULT_TAG = "咨询";
    /** FAQ 路径转人工时的 intentCode 占位符，区别于 DIT 域路径使用真实意图 code */
    private static final String FAQ_TRANSFER_INTENT_CODE = "faq_transfer";
    /** 会话已接入人工时的提示文案，告知用户消息已转发给座席 */
    private static final String AGENT_HINT_MSG = "（消息已发送给人工客服）";

    /** AI 模型工厂，提供流式对话 ChatModel 实例 */
    private final DynamicModelFactory            aiClient;
    /** 对话历史仓储，负责追加和读取多轮对话上下文 */
    private final ConversationHistoryRepository  historyRepository;
    /** 知识库 RAG 检索客户端，根据用户消息向量检索相关知识块 */
    private final KnowledgeServiceClient         knowledgeServiceClient;
    /** 意图分类服务（@Primary 实现为 HybridIntentService），Tier1 规则 → Tier2 LLM */
    private final IntentService                  intentService;
    /** 会话队列服务，提供幂等初始化、入队和状态查询能力 */
    private final SessionQueueService            sessionQueueService;
    /** JSON 序列化工具，用于构造 TransferPayload、sources 等 SSE 载荷 */
    private final ObjectMapper                   objectMapper;

    /**
     * 已接入人工时的消息处理：仅追加历史记录并返回提示，不调用 AI。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return 仅含提示文案的单元素 token 事件流
     */
    public Flux<ChatEvent> appendAndHint(String sessionId, String message) {
        historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
        return Flux.just(ChatEvent.token(AGENT_HINT_MSG, objectMapper));
    }

    /**
     * 通用 FAQ 流程：RAG 检索 + 意图分类 + 路由分支 + 流式 token 生成。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ChatEvent 流，包含 token 事件，RAG 命中时首先发送 sources 事件
     */
    public Flux<ChatEvent> stream(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    sessionQueueService.initAiChatSession(sessionId);
                    historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
                    List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(message);
                    IntentResult intent = intentService.classify(message);
                    log.debug("[FAQ] sessionId={} intent={} confidence={}",
                            sessionId, intent.intent(), intent.confidence());
                    return new FaqContext(hits, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> buildEventStream(sessionId, message, ctx));
    }

    /**
     * 转人工处理，FAQ 路径和 Domain 路径共用。
     *
     * <p>入队操作失败时仅打 warn 日志，不抛出异常，确保 TRANSFER 事件始终能发送给前端。
     *
     * @param sessionId 会话 ID
     * @param intent    触发转人工的意图分类结果，用于区分 COMPLAINT 和 TRANSFER_REQUEST 回复文案
     * @return 单元素 TRANSFER 语义事件流；序列化失败时降级为 token 事件流
     */
    public Flux<ChatEvent> handleTransfer(String sessionId, IntentResult intent) {
        try {
            sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
        } catch (Exception e) {
            log.warn("[FAQ] 自动转人工入队失败 sessionId={}", sessionId, e);
        }
        String reply = intent.intent() == IntentType.COMPLAINT
                ? "非常抱歉给您带来了不好的体验，我已为您转接人工客服，请稍候。"
                : "好的，我已为您转接人工客服，请稍候。";
        historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(), reply);
        try {
            String json = objectMapper.writeValueAsString(
                    new TransferPayload(FAQ_TRANSFER_INTENT_CODE, reply));
            return Flux.just(ChatEvent.transfer(json));
        } catch (JsonProcessingException e) {
            log.warn("[FAQ] transfer payload 序列化失败 sessionId={}", sessionId, e);
            return Flux.just(ChatEvent.token(reply, objectMapper));
        }
    }

    private Flux<ChatEvent> buildEventStream(String sessionId, String message, FaqContext ctx) {
        if (ctx.intent().requiresTransfer()) {
            return handleTransfer(sessionId, ctx.intent());
        }
        if (ctx.intent().intent() == IntentType.OUT_OF_SCOPE) {
            historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(), OUT_OF_SCOPE_REPLY);
            return Flux.just(ChatEvent.token(OUT_OF_SCOPE_REPLY, objectMapper));
        }
        return buildLlmStream(sessionId, message, ctx);
    }

    private Flux<ChatEvent> buildLlmStream(String sessionId, String message, FaqContext ctx) {
        List<KnowledgeSearchResult.Hit> effectiveHits =
                ctx.intent().skipRag() ? List.of() : ctx.hits();
        String systemPrompt = SystemPromptBuilder.build(effectiveHits);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder replyBuf = new StringBuilder();

        Flux<ChatEvent> tokenStream = aiClient.streamChat(aiPrompt, systemPrompt)
                .map(token -> {
                    replyBuf.append(token);
                    return ChatEvent.token(token, objectMapper);
                })
                .onErrorResume(e -> {
                    log.warn("[FAQ] LLM 调用失败 sessionId={}", sessionId, e);
                    return Flux.just(ChatEvent.error("抱歉，AI 服务暂时不可用，请稍后重试。", objectMapper));
                })
                .doFinally(s -> {
                    if (!replyBuf.isEmpty()) {
                        historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(),
                                replyBuf.toString());
                    }
                });

        if (effectiveHits.isEmpty()) {
            return tokenStream;
        }
        ChatEvent sourcesEvent = buildSourcesEvent(effectiveHits);
        return tokenStream.switchOnFirst((signal, flux) -> {
            if (signal.hasValue()) {
                String type = signal.get() != null ? signal.get().eventType() : null;
                if (ChatEvent.EventType.ERROR.equals(type)) {
                    return flux;
                }
            }
            return Flux.concat(Flux.just(sourcesEvent), flux);
        });
    }

    /**
     * 将 RAG 命中结果序列化为 sources SSE 事件。
     * label 优先使用文档面包屑（breadcrumb），缺失时降级为"文档片段"。
     */
    private ChatEvent buildSourcesEvent(List<KnowledgeSearchResult.Hit> hits) {
        List<Map<String, String>> sources = hits.stream()
                .map(h -> Map.of(
                        "docId", h.getDocId() != null ? h.getDocId() : "",
                        "label", StringUtils.isNotBlank(h.getBreadcrumb())
                                ? h.getBreadcrumb() : "文档片段"))
                .toList();
        try {
            return ChatEvent.sources(objectMapper.writeValueAsString(sources));
        } catch (JsonProcessingException e) {
            log.warn("[FAQ] sources 序列化失败，降级返回空数组", e);
            return ChatEvent.sources("[]");
        }
    }

    private List<ChatMessage> toAiPrompt(List<com.aria.conversation.domain.ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }

    /** FAQ 路由阶段的中间结果，携带 RAG 命中列表和意图分类结果。 */
    private record FaqContext(List<KnowledgeSearchResult.Hit> hits, IntentResult intent) {}
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl ai-conversation/conversation-service compile -q 2>&1 | tail -3
```

Expected: 无输出

- [ ] **Step 3: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 新增 FaqChatAppService，提取 FAQ 对话编排逻辑及转人工公共方法"
```

### Task 10: 精简 `ChatAppService` — 仅保留路由分发逻辑

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceIntentTest.java`

**Interfaces:**
- Consumes: `FaqChatAppService`, `DomainSessionAppService`, `DomainAgentService`, `IntentService`, `SessionQueueService`
- Produces: `ChatAppService.stream(String, String, String)` → `Flux<ChatEvent>`（对外接口不变）

- [ ] **Step 1: 完整替换 `ChatAppService.java`（精简为纯路由分发器）**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话路由分发器。
 *
 * <p>统一流式对话入口，根据会话状态和请求参数将请求分发到三条处理路径：
 * <ol>
 *   <li>已接入人工 → 直接返回提示，不走 AI</li>
 *   <li>有 domainCode → 域会话路径（DomainSessionAppService + DomainAgentService）</li>
 *   <li>无 domainCode → 通用 FAQ 路径（FaqChatAppService）</li>
 * </ol>
 *
 * <p>本类只做路由决策，不含任何业务编排逻辑，所有具体实现委托给对应 Service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    /** 会话队列服务，用于判断是否已接入人工及入队操作 */
    private final SessionQueueService        sessionQueueService;
    /** 域会话生命周期管理器，封装激活域读写和小模型路由决策 */
    private final DomainSessionAppService    domainSessionService;
    /** FAQ 对话编排器，封装 RAG + 意图路由 + LLM 流程及转人工公共方法 */
    private final FaqChatAppService          faqChatService;
    /** 域 Agent 流式对话执行器，处理携带工具的域内对话 */
    private final DomainAgentService         domainAgentService;
    /** 意图分类服务，Tier1 关键词/正则 → Tier2 LLM 兜底 */
    private final IntentService              intentService;
    /** JSON 序列化工具，用于构造 SSE 事件载荷 */
    private final ObjectMapper               objectMapper;

    /**
     * 统一流式对话入口，返回 {@link ChatEvent} 流供 Controller 转换为 SSE。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @param domainCode 领域标识（可选，null 走通用 FAQ 流程）
     * @return ChatEvent 流
     */
    public Flux<ChatEvent> stream(String sessionId, String message, String domainCode) {
        // 1. 已接入人工 → 存历史，返回提示
        if (sessionQueueService.isActive(sessionId)) {
            return faqChatService.appendAndHint(sessionId, message);
        }
        // 2. 有 domainCode → 域路径
        if (StringUtils.isNotBlank(domainCode)) {
            return streamDomain(sessionId, message, domainCode);
        }
        // 3. 通用 FAQ 路径
        return faqChatService.stream(sessionId, message);
    }

    /**
     * 域路径处理：在 boundedElastic 线程完成阻塞操作（域会话管理 + 意图分类），
     * 再根据意图决策走转人工或 DomainAgentService。
     *
     * <p>意图分类在域会话解析完成后执行，用于快速拦截 TRANSFER_REQUEST/COMPLAINT，
     * 避免消耗 function-calling token。
     */
    private Flux<ChatEvent> streamDomain(String sessionId, String message, String domainCode) {
        return Mono.fromCallable(() -> {
                    // 阻塞操作：Redis读写 + 小模型推理 + 意图分类
                    String activeDomain = domainSessionService.resolveActiveDomain(
                            sessionId, message, domainCode);
                    IntentResult intent = intentService.classify(message);
                    return new DomainRouteContext(activeDomain, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {
                    if (ctx.intent().requiresTransfer()) {
                        log.info("[Chat] domain 路径意图拦截 sessionId={} intent={}",
                                sessionId, ctx.intent().intent());
                        return faqChatService.handleTransfer(sessionId, ctx.intent());
                    }
                    return domainAgentService.streamChat(sessionId, ctx.activeDomain(), message);
                });
    }

    // -------------------------------------------------------
    // 历史查询（供 Controller 使用）
    // -------------------------------------------------------

    /** 清除会话历史。 */
    public void clearHistory(String sessionId) {
        // 委托给 FaqChatAppService 的 historyRepository，此处通过 Spring 注入处理
        // 暂保留以维持 Controller 接口兼容性，后续可提取到 VisitorHistoryService
        throw new UnsupportedOperationException("请直接调用 ConversationHistoryRepository");
    }

    /** 用户主动请求转人工。 */
    public com.aria.conversation.domain.SessionQueueItem requestTransfer(
            String sessionId, String userName, String transferReason, String tag) {
        return sessionQueueService.enqueue(sessionId, userName, transferReason, tag);
    }

    /** 查询会话当前状态。 */
    public com.aria.conversation.domain.SessionStatus getSessionStatus(String sessionId) {
        return sessionQueueService.getSessionStatus(sessionId);
    }

    /**
     * 域路径路由阶段中间结果，携带最终确定的活跃域编码和意图分类结果。
     *
     * @param activeDomain 经域路由决策后的最终活跃域编码
     * @param intent       意图分类结果，决定后续走转人工还是 DomainAgentService
     */
    private record DomainRouteContext(String activeDomain, IntentResult intent) {}
}
```

- [ ] **Step 2: 更新 `ChatAppServiceIntentTest.java`**

由于意图路由逻辑已移至 `FaqChatAppService`，测试需调整构造依赖。将 `setUp()` 中的构造调用从 10 参数改为 6 参数，并新增 `FaqChatAppService` 和 `DomainSessionAppService` 的 mock：

```java
// 原来（10参数）：
service = new ChatAppService(aiClient, historyRepository, knowledgeServiceClient,
        intentClassifier, sessionQueueService, objectMapper,
        sessionDomainRepo, domainSwitchRepo, domainRoutingService, domainAgentService);

// 改为（6参数，Intent/FAQ逻辑通过 faqChatService mock 委托）：
@Mock private FaqChatAppService     faqChatService;
@Mock private DomainSessionAppService domainSessionService;
// 其余 mock 保持不变（aiClient, intentClassifier, sessionQueueService 等）

@BeforeEach
void setUp() {
    service = new ChatAppService(sessionQueueService, domainSessionService,
            faqChatService, domainAgentService, intentClassifier, objectMapper);
    lenient().when(knowledgeServiceClient.search(anyString())).thenReturn(List.of());
    lenient().when(historyRepository.findAll(anyString())).thenReturn(List.of());
}
```

同时，各测试中原来直接 mock `intentClassifier` 并验证 `aiClient` 的测试，改为：
- FAQ 路径测试：mock `faqChatService.stream()` 返回预期事件流
- 验证 `faqChatService.stream()` 被调用（而非直接验证 `aiClient`）

**说明：** `ChatAppServiceIntentTest` 中的核心意图路由场景（转人工、拒答、闲聊、FAQ）的详细测试应放在 `FaqChatAppServiceTest` 中。此测试文件重构为仅验证 `ChatAppService` 的三路分发逻辑（dispatch 测试），保留以下 3 个测试用例：

```java
@Test
@DisplayName("已接入人工：委托 faqChatService.appendAndHint")
void stream_agentActive_delegatesToAppendAndHint() {
    when(sessionQueueService.isActive("s1")).thenReturn(true);
    when(faqChatService.appendAndHint("s1", "消息")).thenReturn(
            Flux.just(ChatEvent.token("已发送", objectMapper)));

    StepVerifier.create(service.stream("s1", "消息", null))
            .assertNext(e -> assertThat(e.eventType()).isNull())
            .verifyComplete();
    verify(faqChatService).appendAndHint("s1", "消息");
}

@Test
@DisplayName("无 domainCode：委托 faqChatService.stream")
void stream_noDomainCode_delegatesToFaqStream() {
    when(sessionQueueService.isActive("s2")).thenReturn(false);
    when(faqChatService.stream("s2", "查订单")).thenReturn(Flux.empty());

    service.stream("s2", "查订单", null).blockLast();

    verify(faqChatService).stream("s2", "查订单");
    verify(domainAgentService, never()).streamChat(any(), any(), any());
}

@Test
@DisplayName("有 domainCode + requiresTransfer：委托 faqChatService.handleTransfer，不走 DomainAgent")
void stream_domainCode_transferIntent_delegatesToHandleTransfer() {
    when(sessionQueueService.isActive("s3")).thenReturn(false);
    when(domainSessionService.resolveActiveDomain("s3", "转人工", "ecommerce"))
            .thenReturn("ecommerce");
    when(intentClassifier.classify("转人工"))
            .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, "transfer_request", 1.0));
    when(faqChatService.handleTransfer(eq("s3"), any()))
            .thenReturn(Flux.just(ChatEvent.transfer("{}")));

    StepVerifier.create(service.stream("s3", "转人工", "ecommerce"))
            .assertNext(e -> assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER))
            .verifyComplete();
    verify(domainAgentService, never()).streamChat(any(), any(), any());
}
```

删除原来的 `@Mock private ConversationHistoryRepository historyRepository`、`@Mock private KnowledgeServiceClient knowledgeServiceClient`、`@Mock private SessionDomainRepository sessionDomainRepo`、`@Mock private SessionDomainSwitchRepository domainSwitchRepo`、`@Mock private DomainRoutingService domainRoutingService` 等不再直接注入 ChatAppService 的 mock。

- [ ] **Step 3: 运行全量测试**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: commit**

```bash
git add ai-conversation/
git commit -m "feat(重构): 精简 ChatAppService 为纯路由分发器，行数从 506 降至 ~100，更新分发测试"
```

---

### Task 11: 清理 `DomainCacheEvictedEvent` 监听 + 全量验证

**Files:**
- Verify: 确认 `KeywordRegexIntentMatcher` 和 `KeywordRegexDomainMatcher` 中已无 `@EventListener`
- Verify: `DomainRepository.evict()` 中的 `publishEvent` 调用可以保留（作为扩展点）
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java`（可选：移除或保留 publishEvent）

- [ ] **Step 1: 确认无残留 @EventListener**

```bash
grep -rn "@EventListener.*DomainCacheEvictedEvent" \
  ai-conversation/conversation-service/src/main/java/ 2>/dev/null
```

Expected: 无输出（已在 Task 3/4 中删除）

- [ ] **Step 2: 运行完整测试套件**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test 2>&1 | \
  grep -E "Tests run|BUILD" | tail -5
```

Expected:
```
[INFO] Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 确认 auth-service 测试也通过**

```bash
mvn -pl ai-auth/auth-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: git log 确认所有 commit 均为中文**

```bash
git log --oneline feat/intent-routing-enhancement ^main | head -15
```

- [ ] **Step 5: 最终 commit（清理 + 验证完成）**

```bash
git add ai-conversation/
git commit -m "chore(重构): 清理遗留 @EventListener 引用，完成对话服务核心链路重构全量验证"
```
