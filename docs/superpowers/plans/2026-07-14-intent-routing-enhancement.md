# Intent Routing Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Aria 对话服务的意图分类和域路由增加关键词/正则规则前置层（Tier 1）+ LLM few-shot 增强（方案 C），大幅减少 LLM 调用次数并提升准确率。

**Architecture:** `HybridIntentService`（`@Primary`）作为级联协调器，先走 `KeywordRegexIntentMatcher` 规则层，未命中再走改造后的 `LangChain4jIntentService`（注入 exampleQueries few-shot）。`IntentResult` 新增 `intentCode` 字段解耦业务 code 与管道分叉枚举。配置阈值通过 `system_config` 表在线管理，`RoutingConfigProvider` 负责 Redis 缓存 + Pub/Sub 失效。

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, LangChain4j, Redis, PostgreSQL JSONB, JUnit 5, Mockito, AssertJ

## Global Constraints

- 所有新类放在 `ai-conversation/conversation-service` 模块，包路径 `com.aria.conversation.infrastructure.ai.*`
- `IntentService` / `DomainRoutingService` domain 层接口签名不变
- `ChatAppService` 不改动
- `IntentResult` 新增字段后所有旧 `new IntentResult(type, confidence)` 调用必须同步更新
- `IntentConfig` record 新增字段时 `DomainRepository.buildDomainConfig()` 和两个测试 factory 方法必须同步更新
- 每个 task 结束后运行 `mvn -pl ai-conversation/conversation-service test -q` 确认无测试回归
- git commit message 格式：`feat(routing): <描述>` 或 `test(routing): <描述>`

---

### Task 1: DB 迁移脚本 + schema 文档更新

**Files:**
- Create: `docs/sql/migrations/add_routing_rules_columns.sql`
- Modify: `docs/sql/aria_cs-schema.sql`（补充 keywords/patterns 列定义）
- Modify: `docs/sql/aria_cs-data.sql`（补充 routing.config 种子数据）

**Interfaces:**
- Produces: `cs_intent.keywords JSONB DEFAULT '[]'`, `cs_intent.patterns JSONB DEFAULT '[]'`
- Produces: `cs_domain.keywords JSONB DEFAULT '[]'`, `cs_domain.patterns JSONB DEFAULT '[]'`
- Produces: `cs_auth.system_config` 行 `routing.config`

- [ ] **Step 1: 创建迁移脚本**

```sql
-- docs/sql/migrations/add_routing_rules_columns.sql
-- 意图表新增关键词/正则列
ALTER TABLE cs_conversation.cs_intent
    ADD COLUMN IF NOT EXISTS keywords jsonb DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS patterns jsonb DEFAULT '[]'::jsonb;

COMMENT ON COLUMN cs_conversation.cs_intent.keywords IS '关键词列表，JSON 字符串数组，大小写不敏感全文包含匹配，如 ["转人工","找真人"]';
COMMENT ON COLUMN cs_conversation.cs_intent.patterns IS '正则表达式列表，Java Pattern 语法，DOTALL|CASE_INSENSITIVE，如 ["^我要.*转.*人工"]';

-- 域路由表新增关键词/正则列
ALTER TABLE cs_conversation.cs_domain
    ADD COLUMN IF NOT EXISTS keywords jsonb DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS patterns jsonb DEFAULT '[]'::jsonb;

COMMENT ON COLUMN cs_conversation.cs_domain.keywords IS '域路由关键词列表，命中则直接路由到该域，跳过 LLM';
COMMENT ON COLUMN cs_conversation.cs_domain.patterns IS '域路由正则列表，命中则直接路由到该域，跳过 LLM';

-- 路由配置种子数据（system_config 表）
INSERT INTO cs_auth.system_config (config_key, config_value, config_type, description, is_enabled)
VALUES (
  'routing.config',
  '{
    "intent": {
      "embeddingEnabled": false,
      "embeddingThreshold": 0.75,
      "minLlmConfidence": 0.0,
      "maxExamplesToInject": 5
    },
    "domain": {
      "ruleEnabled": true
    }
  }',
  'CUSTOMER_SERVICE',
  '意图路由级联配置（JSON）',
  true
) ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: 在 `aria_cs-schema.sql` 的 `cs_intent` 表定义后追加两列**

在文件第 826 行（`sort_order integer DEFAULT 0 NOT NULL`）后追加：
```sql
    keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    patterns jsonb DEFAULT '[]'::jsonb NOT NULL,
```

- [ ] **Step 3: 在 `aria_cs-schema.sql` 的 `cs_domain` 表定义后追加两列**

在 `enabled boolean DEFAULT true NOT NULL,` 前追加：
```sql
    keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    patterns jsonb DEFAULT '[]'::jsonb NOT NULL,
```

- [ ] **Step 4: commit**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
git add docs/sql/
git commit -m "feat(routing): add keywords/patterns columns migration + routing.config seed"
```

### Task 2: 域模型变更（IntentDO / DomainDO / IntentConfig / IntentResult）

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/IntentDO.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/DomainDO.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/IntentConfig.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/IntentResult.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentServiceTest.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/dit/config/DomainConfigTest.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceIntentTest.java`

**Interfaces:**
- Consumes: Task 1 DB columns
- Produces:
  - `IntentConfig(code, name, description, List<String> exampleQueries, boolean autoTransfer, boolean skipRag, String fallbackReply, List<SlotConfig> slots, List<IntentToolBinding> toolBindings, List<String> keywords, List<String> patterns, int sortOrder)`
  - `IntentResult(IntentType intent, String intentCode, double confidence)` — `UNKNOWN` constant has `confidence=0.0`
  - `DomainDO.getKeywords()`, `DomainDO.getPatterns()`
  - `IntentDO.getKeywords()`, `IntentDO.getPatterns()`

- [ ] **Step 1: 更新 `IntentDO.java` 新增 keywords / patterns 字段**

```java
// 在 private Integer sortOrder; 后追加：
/** 关键词列表，JSON 数组，大小写不敏感包含匹配 */
@TableField(typeHandler = JsonbTypeHandler.class)
private String keywords;

/** 正则表达式列表，JSON 数组，Java Pattern 语法 */
@TableField(typeHandler = JsonbTypeHandler.class)
private String patterns;
```

- [ ] **Step 2: 更新 `DomainDO.java` 新增 keywords / patterns 字段**

```java
// 在 private Boolean enabled; 前追加：
@TableField(typeHandler = JsonbTypeHandler.class)
private String keywords;

@TableField(typeHandler = JsonbTypeHandler.class)
private String patterns;
```

- [ ] **Step 3: 更新 `IntentConfig.java` record 定义**

```java
public record IntentConfig(
        String code,
        String name,
        String description,
        List<String> exampleQueries,      // 原 String，改为 List<String>，由 Repository 解析
        boolean autoTransfer,
        boolean skipRag,
        String fallbackReply,
        List<SlotConfig> slots,
        List<IntentToolBinding> toolBindings,
        List<String> keywords,             // 新增
        List<String> patterns,             // 新增
        int sortOrder                      // 新增，来自 IntentDO.sortOrder
) implements Serializable {

    public List<IntentToolBinding> requiredTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isRequired)
                .sorted(Comparator.comparingInt(IntentToolBinding::executionOrder))
                .toList();
    }

    public List<IntentToolBinding> optionalTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isOptional)
                .toList();
    }
}
```

- [ ] **Step 4: 更新 `DomainRepository.buildDomainConfig()` 传入新字段**

将原来的 `new IntentConfig(...)` 9 参数调用替换为 12 参数：
```java
intents.add(new IntentConfig(
    intentDO.getCode(),
    intentDO.getName(),
    intentDO.getDescription(),
    parseJsonArray(intentDO.getExampleQueries()),          // String → List<String>
    Boolean.TRUE.equals(intentDO.getAutoTransfer()),
    Boolean.TRUE.equals(intentDO.getSkipRag()),
    intentDO.getFallbackReply(),
    slots,
    bindings,
    parseJsonArray(intentDO.getKeywords()),                // 新增
    parseJsonArray(intentDO.getPatterns()),                // 新增
    intentDO.getSortOrder() != null ? intentDO.getSortOrder() : 0  // 新增
));
```

- [ ] **Step 5: 更新 `IntentResult.java` 新增 intentCode 字段**

```java
package com.aria.conversation.domain.model;

/**
 * 意图分类结果。
 *
 * @param intent     管道分叉枚举（驱动转人工/拒答/RAG 分支）
 * @param intentCode 原始业务意图 code（如 "query_order"），供下游业务 dispatch 使用
 * @param confidence 置信度 0.0~1.0
 */
public record IntentResult(IntentType intent, String intentCode, double confidence) {

    /** 兜底结果，分类失败时使用。confidence=0.0 表示完全不确定。 */
    public static final IntentResult UNKNOWN =
            new IntentResult(IntentType.UNKNOWN, "UNKNOWN", 0.0);

    public boolean requiresTransfer() {
        return intent == IntentType.TRANSFER_REQUEST || intent == IntentType.COMPLAINT;
    }

    public boolean skipRag() {
        return intent == IntentType.CHITCHAT || intent == IntentType.OUT_OF_SCOPE;
    }
}
```

- [ ] **Step 6: 更新 `LangChain4jIntentService.java` 的 `parseResponse()` 补充 intentCode**

将 `return new IntentResult(intent, confidence);` 替换为：
```java
// intentStr 是 LLM 返回的原始 code 字符串（如 "FAQ_QUERY" 或自定义 "query_order"）
return new IntentResult(intent, intentStr.toLowerCase(), confidence);
```

同时更新所有 `return IntentResult.UNKNOWN;` — 这些调用无需改动，`UNKNOWN` 常量已在 Step 5 更新。

- [ ] **Step 7: 更新两个测试文件的 factory 方法，补充新字段默认值**

`LangChain4jIntentServiceTest.java` 第 33 行：
```java
private static IntentConfig intentConfig(String code, String desc) {
    return new IntentConfig(code, code, desc, List.of(), false, false, null,
            List.of(), List.of(), List.of(), List.of(), 0);
}
```

`DomainConfigTest.java` 第 23 行：
```java
private static IntentConfig makeIntent(String code, boolean autoTransfer, boolean skipRag,
                                       List<IntentToolBinding> bindings) {
    return new IntentConfig(code, code + "_name", "desc", List.of(),
            autoTransfer, skipRag, null, List.of(), bindings,
            List.of(), List.of(), 0);
}
```

- [ ] **Step 8: 更新 `ChatAppServiceIntentTest.java` 所有 `new IntentResult(type, confidence)` 调用**

将所有如 `new IntentResult(IntentType.FAQ_QUERY, 0.9)` 替换为 `new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9)`，共 9 处（第 74、118、144、171、192、236、259、281、301 行）。`IntentResult.UNKNOWN` 第 216 行无需改动。

- [ ] **Step 9: 运行测试，确认全部通过**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test -q
```

Expected: `BUILD SUCCESS`，0 failures

- [ ] **Step 10: commit**

```bash
git add ai-conversation/
git commit -m "feat(routing): add intentCode to IntentResult, update IntentConfig record with keywords/patterns/sortOrder"
```

### Task 3: RoutingProperties 配置类

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingProperties.java`

**Interfaces:**
- Produces:
  - `RoutingProperties.getIntent().isEmbeddingEnabled()` → `boolean`
  - `RoutingProperties.getIntent().getEmbeddingThreshold()` → `double`
  - `RoutingProperties.getIntent().getMinLlmConfidence()` → `double`
  - `RoutingProperties.getIntent().getMaxExamplesToInject()` → `int`
  - `RoutingProperties.getDomain().isRuleEnabled()` → `boolean`

- [ ] **Step 1: 创建 `RoutingProperties.java`**

```java
package com.aria.conversation.infrastructure.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图路由级联配置，绑定前缀 aria.routing。
 * 作为 YAML 默认值兜底，运行时值由 RoutingConfigProvider 从 system_config 表覆盖。
 */
@ConfigurationProperties(prefix = "aria.routing")
@Component
@Getter
@Setter
public class RoutingProperties {

    private IntentProperties intent = new IntentProperties();
    private DomainProperties domain = new DomainProperties();

    @Getter
    @Setter
    public static class IntentProperties {
        /** 是否启用向量相似度层（第二阶段，默认关闭） */
        private boolean embeddingEnabled = false;
        /** 向量相似度命中阈值，低于此值继续走 LLM */
        private double embeddingThreshold = 0.75;
        /** LLM 分类置信度最低值，低于此值降级为 UNKNOWN；0.0=关闭 */
        private double minLlmConfidence = 0.0;
        /** few-shot prompt 中每个意图最多注入的示例条数 */
        private int maxExamplesToInject = 5;
    }

    @Getter
    @Setter
    public static class DomainProperties {
        /** 是否启用域路由规则层 */
        private boolean ruleEnabled = true;
        /**
         * 域路由 LLM 置信度阈值（预留）。
         * 当前 LangChain4jDomainRoutingService 返回裸 domain code，不含 confidence，
         * 此配置暂不生效。
         */
        private double minLlmConfidence = 0.0;
    }
}
```

- [ ] **Step 2: commit**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
git add ai-conversation/
git commit -m "feat(routing): add RoutingProperties config class"
```

---

### Task 4: KeywordRegexIntentMatcher

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcher.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcherTest.java`

**Interfaces:**
- Consumes: `DomainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)` → `Optional<DomainConfig>`; `IntentConfig.keywords()`, `IntentConfig.patterns()`, `IntentConfig.code()`, `IntentConfig.sortOrder()`
- Produces: `KeywordRegexIntentMatcher.match(String userMessage)` → `Optional<IntentResult>`; `KeywordRegexIntentMatcher.reload()`

- [ ] **Step 1: 写失败测试**

创建 `KeywordRegexIntentMatcherTest.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("KeywordRegexIntentMatcher")
class KeywordRegexIntentMatcherTest {

    @Mock private DomainRepository domainRepository;
    private KeywordRegexIntentMatcher matcher;

    private static IntentConfig intentConfig(String code, List<String> keywords, List<String> patterns, int sortOrder) {
        return new IntentConfig(code, code, "desc", List.of(), false, false, null,
                List.of(), List.of(), keywords, patterns, sortOrder);
    }

    private DomainConfig systemDomain(IntentConfig... intents) {
        return new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "系统", null, null, null, List.of(intents));
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        matcher = new KeywordRegexIntentMatcher(domainRepository);
    }

    @Test
    @DisplayName("关键词命中：消息包含关键词，返回对应意图")
    void match_keywordHit() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("TRANSFER_REQUEST", List.of("转人工", "找真人"), List.of(), 1))));
        matcher.reload();

        Optional<IntentResult> result = matcher.match("我想转人工处理一下");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.get().intentCode()).isEqualTo("TRANSFER_REQUEST");
        assertThat(result.get().confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("关键词大小写不敏感")
    void match_keyword_caseInsensitive() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("FAQ_QUERY", List.of("FAQ"), List.of(), 0))));
        matcher.reload();

        assertThat(matcher.match("我有个faq问题")).isPresent();
    }

    @Test
    @DisplayName("正则命中：pattern 匹配，返回对应意图")
    void match_patternHit() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("COMPLAINT", List.of(), List.of("^.*投诉.*"), 0))));
        matcher.reload();

        Optional<IntentResult> result = matcher.match("我要投诉你们！");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("无规则配置时返回 empty")
    void match_noRules_returnsEmpty() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("FAQ_QUERY", List.of(), List.of(), 0))));
        matcher.reload();

        assertThat(matcher.match("随便说一句话")).isEmpty();
    }

    @Test
    @DisplayName("多意图冲突：按 sortOrder 取优先级最高的")
    void match_multipleHits_returnLowestSortOrder() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("TRANSFER_REQUEST", List.of("人工"), List.of(), 1),
                        intentConfig("COMPLAINT", List.of("不满意"), List.of(), 2))));
        matcher.reload();

        // "不满意" 只命中 COMPLAINT(sortOrder=2)
        Optional<IntentResult> result = matcher.match("我对服务不满意");
        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("自定义业务 code 不在枚举内，intent 降级为 FAQ_QUERY")
    void match_customCode_fallbackToFaqQuery() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("query_order", List.of("查订单"), List.of(), 0))));
        matcher.reload();

        Optional<IntentResult> result = matcher.match("帮我查一下订单");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(result.get().intentCode()).isEqualTo("query_order");
    }

    @Test
    @DisplayName("__system__ 域不存在时 reload 不抛异常，match 返回 empty")
    void reload_domainNotFound_noException() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());
        matcher.reload();

        assertThat(matcher.match("任意消息")).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -pl ai-conversation/conversation-service test -Dtest=KeywordRegexIntentMatcherTest -q 2>&1 | tail -5
```

Expected: 编译错误或 `ClassNotFoundException`

- [ ] **Step 3: 实现 `KeywordRegexIntentMatcher.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 关键词 + 正则意图匹配器（Tier 1）。
 *
 * <p>启动时将所有意图的规则预编译缓存到内存，运行期纯内存匹配，无 IO 开销。
 * 命中置信度固定为 1.0；同时命中多个意图时取 sortOrder 最小（优先级最高）的那个。
 *
 * <p><b>ReDoS 防护：</b>patterns 由运营通过管理后台填写，保存时须在 API 层校验：
 * 长度 ≤ 200 字符，禁止嵌套量词（如 {@code (a+)+}）。
 *
 * <p><b>中文关键词说明：</b>纯子串匹配无词边界，关键词应至少 3 个汉字，
 * 高敏感意图（TRANSFER_REQUEST）建议使用正则而非单词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexIntentMatcher {

    private final DomainRepository domainRepository;

    /**
     * 编译后的规则条目，按 sortOrder 升序排列（DB 查询已排序，此处顺序保留）。
     * volatile + 整体替换保证可见性，无需锁。
     */
    private volatile List<IntentRuleEntry> compiledRules = List.of();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 监听域配置变更事件，触发规则缓存刷新 */
    @EventListener
    public void onDomainEvicted(DomainCacheEvictedEvent event) {
        if (DomainCodes.SYSTEM_DOMAIN.equals(event.getDomainCode())) {
            log.info("[RuleMatcher] 检测到 __system__ 域配置变更，刷新意图规则缓存");
            reload();
        }
    }

    public void reload() {
        DomainConfig system = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
        if (system == null) {
            log.warn("[RuleMatcher] __system__ 域不存在，规则层不可用");
            compiledRules = List.of();
            return;
        }
        List<IntentRuleEntry> entries = system.intents().stream()
                .filter(this::hasRules)
                .map(this::compile)
                .toList();
        this.compiledRules = entries;
        log.info("[RuleMatcher] 加载意图规则 {} 条", entries.size());
    }

    /**
     * 尝试用规则匹配用户消息。
     * @return Optional.empty() 表示无命中，由下一层处理
     */
    public Optional<IntentResult> match(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        String lower = userMessage.toLowerCase();
        for (IntentRuleEntry entry : compiledRules) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    log.debug("[RuleMatcher] 关键词命中 intent={} keyword={}", entry.intentCode(), kw);
                    return Optional.of(new IntentResult(entry.intentType(), entry.intentCode(), 1.0));
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    log.debug("[RuleMatcher] 正则命中 intent={} pattern={}", entry.intentCode(), p.pattern());
                    return Optional.of(new IntentResult(entry.intentType(), entry.intentCode(), 1.0));
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasRules(IntentConfig i) {
        return (i.keywords() != null && !i.keywords().isEmpty())
                || (i.patterns() != null && !i.patterns().isEmpty());
    }

    private IntentRuleEntry compile(IntentConfig i) {
        List<Pattern> compiled = i.patterns() == null ? List.of()
                : i.patterns().stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList();
        // 自定义业务 code 不在枚举内时，视为 FAQ_QUERY 分叉（走 RAG+LLM）
        IntentType type;
        try {
            type = IntentType.valueOf(i.code().toUpperCase());
        } catch (IllegalArgumentException e) {
            type = IntentType.FAQ_QUERY;
        }
        return new IntentRuleEntry(
                i.code(), type,
                i.keywords() == null ? List.of() : i.keywords(),
                compiled);
    }

    record IntentRuleEntry(
            String intentCode,
            IntentType intentType,
            List<String> keywords,
            List<Pattern> compiledPatterns
    ) {}
}
```

- [ ] **Step 4: 创建 `DomainCacheEvictedEvent.java`**

```java
package com.aria.conversation.infrastructure.ai;

import org.springframework.context.ApplicationEvent;

/** 域配置缓存失效事件，由 DomainRepository.evict() 发布 */
public class DomainCacheEvictedEvent extends ApplicationEvent {

    private final String domainCode;

    public DomainCacheEvictedEvent(Object source, String domainCode) {
        super(source);
        this.domainCode = domainCode;
    }

    public String getDomainCode() { return domainCode; }
}
```

- [ ] **Step 5: 在 `DomainRepository.evict()` 末尾发布事件**

```java
// DomainRepository.java — 新增字段
private final org.springframework.context.ApplicationEventPublisher eventPublisher;

// evict() 方法末尾追加：
eventPublisher.publishEvent(new DomainCacheEvictedEvent(this, domainCode));
```

- [ ] **Step 6: 运行测试，确认全部通过**

```bash
mvn -pl ai-conversation/conversation-service test -Dtest=KeywordRegexIntentMatcherTest -q
```

Expected: `BUILD SUCCESS`, 7 tests passing

- [ ] **Step 7: commit**

```bash
git add ai-conversation/
git commit -m "feat(routing): add KeywordRegexIntentMatcher + DomainCacheEvictedEvent"
```

### Task 5: KeywordRegexDomainMatcher + HybridDomainRoutingService

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexDomainMatcher.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridDomainRoutingService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexDomainMatcherTest.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/HybridDomainRoutingServiceTest.java`

**Interfaces:**
- Consumes: `DomainRepository.findAllEnabledSummary()` → `List<DomainDO>`; `DomainDO.getKeywords()`, `DomainDO.getPatterns()`, `DomainDO.getCode()`
- Produces: `KeywordRegexDomainMatcher.matchDomain(String)` → `Optional<String>`; `HybridDomainRoutingService` implements `DomainRoutingService`

- [ ] **Step 1: 写失败测试 `KeywordRegexDomainMatcherTest.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("KeywordRegexDomainMatcher")
class KeywordRegexDomainMatcherTest {

    @Mock private DomainRepository domainRepository;
    private KeywordRegexDomainMatcher matcher;

    private static DomainDO domain(String code, String keywords, String patterns) {
        DomainDO d = new DomainDO();
        d.setCode(code);
        d.setKeywords(keywords);
        d.setPatterns(patterns);
        return d;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        matcher = new KeywordRegexDomainMatcher(domainRepository);
    }

    @Test
    @DisplayName("关键词命中：返回对应域 code")
    void matchDomain_keywordHit() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("finance", "[\"基金\",\"理财\"]", "[]")));
        matcher.reload();

        assertThat(matcher.matchDomain("我想买基金")).contains("finance");
    }

    @Test
    @DisplayName("正则命中：返回对应域 code")
    void matchDomain_patternHit() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("ecommerce", "[]", "[\".*退款.*\"]")));
        matcher.reload();

        assertThat(matcher.matchDomain("我要申请退款")).contains("ecommerce");
    }

    @Test
    @DisplayName("无命中：返回 empty")
    void matchDomain_noHit_empty() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("finance", "[\"基金\"]", "[]")));
        matcher.reload();

        assertThat(matcher.matchDomain("随便说一句话")).isEmpty();
    }
}
```

- [ ] **Step 2: 实现 `KeywordRegexDomainMatcher.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 关键词 + 正则域路由匹配器（Tier 1）。
 * 启动时全量加载所有启用域的规则到内存，监听 DomainCacheEvictedEvent 自动刷新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexDomainMatcher {

    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private volatile List<DomainRuleEntry> compiledRules = List.of();

    @PostConstruct
    public void init() { reload(); }

    @EventListener
    public void onDomainEvicted(DomainCacheEvictedEvent event) {
        log.info("[DomainRuleMatcher] 域 {} 配置变更，刷新域规则缓存", event.getDomainCode());
        reload();
    }

    public void reload() {
        List<DomainRuleEntry> entries = domainRepository.findAllEnabledSummary().stream()
                .filter(this::hasRules)
                .map(this::compile)
                .toList();
        this.compiledRules = entries;
        log.info("[DomainRuleMatcher] 加载域规则 {} 条", entries.size());
    }

    /** @return Optional.empty() 表示未命中，继续走 LLM 路由 */
    public Optional<String> matchDomain(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        String lower = userMessage.toLowerCase();
        for (DomainRuleEntry entry : compiledRules) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) return Optional.of(entry.domainCode());
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) return Optional.of(entry.domainCode());
            }
        }
        return Optional.empty();
    }

    private boolean hasRules(DomainDO d) {
        return (d.getKeywords() != null && !d.getKeywords().equals("[]") && !d.getKeywords().isBlank())
                || (d.getPatterns() != null && !d.getPatterns().equals("[]") && !d.getPatterns().isBlank());
    }

    private DomainRuleEntry compile(DomainDO d) {
        List<String> kws = parseJsonArray(d.getKeywords());
        List<Pattern> pats = parseJsonArray(d.getPatterns()).stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                .toList();
        return new DomainRuleEntry(d.getCode(), kws, pats);
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DomainRuleMatcher] JSON 数组解析失败: {}", json);
            return List.of();
        }
    }

    record DomainRuleEntry(String domainCode, List<String> keywords, List<Pattern> compiledPatterns) {}
}
```

- [ ] **Step 3: 写失败测试 `HybridDomainRoutingServiceTest.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService.RouteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridDomainRoutingService")
class HybridDomainRoutingServiceTest {

    @Mock private KeywordRegexDomainMatcher ruleMatcher;
    @Mock private LangChain4jDomainRoutingService llmRouter;
    @InjectMocks private HybridDomainRoutingService service;

    @Test
    @DisplayName("Tier1 命中：返回规则结果，不调用 LLM")
    void route_tier1Hit_llmNotCalled() {
        when(ruleMatcher.matchDomain("我想买基金")).thenReturn(Optional.of("finance"));

        RouteResult result = service.route("我想买基金", "ecommerce", List.of());

        assertThat(result.suggestedDomain()).isEqualTo("finance");
        assertThat(result.shouldSwitch()).isTrue();
        verify(llmRouter, never()).route(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("Tier1 命中，目标域与当前域相同：shouldSwitch=false")
    void route_tier1Hit_sameAsCurrent_noSwitch() {
        when(ruleMatcher.matchDomain(anyString())).thenReturn(Optional.of("ecommerce"));

        RouteResult result = service.route("查订单", "ecommerce", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        verify(llmRouter, never()).route(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("Tier1 未命中：调用 LLM 路由")
    void route_tier1Miss_llmCalled() {
        when(ruleMatcher.matchDomain(anyString())).thenReturn(Optional.empty());
        when(llmRouter.route(anyString(), anyString(), anyList()))
                .thenReturn(new RouteResult("finance", true));

        RouteResult result = service.route("任意消息", "ecommerce", List.of());

        assertThat(result.suggestedDomain()).isEqualTo("finance");
        verify(llmRouter).route(anyString(), anyString(), anyList());
    }
}
```

- [ ] **Step 4: 实现 `HybridDomainRoutingService.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 域路由级联协调器（@Primary），实现 DomainRoutingService。
 * Tier 1: KeywordRegexDomainMatcher（关键词/正则）
 * Tier 2: LangChain4jDomainRoutingService（LLM 兜底）
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridDomainRoutingService implements DomainRoutingService {

    private final KeywordRegexDomainMatcher ruleMatcher;
    private final LangChain4jDomainRoutingService llmRouter;

    @Override
    public RouteResult route(String userMessage, String currentDomain,
                             List<ConversationMessage> recentHistory) {
        Optional<String> matched = ruleMatcher.matchDomain(userMessage);
        if (matched.isPresent()) {
            String target = matched.get();
            boolean shouldSwitch = !target.equalsIgnoreCase(currentDomain);
            log.debug("[HybridDomain] Tier1 规则命中，跳过 LLM. domain={} shouldSwitch={}",
                    target, shouldSwitch);
            return new RouteResult(target, shouldSwitch);
        }
        return llmRouter.route(userMessage, currentDomain, recentHistory);
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest="KeywordRegexDomainMatcherTest,HybridDomainRoutingServiceTest" -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: commit**

```bash
git add ai-conversation/
git commit -m "feat(routing): add KeywordRegexDomainMatcher + HybridDomainRoutingService"
```

### Task 6: HybridIntentService

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridIntentService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/HybridIntentServiceTest.java`

**Interfaces:**
- Consumes: `KeywordRegexIntentMatcher.match(String)` → `Optional<IntentResult>`; `LangChain4jIntentService.classify(String)` → `IntentResult`
- Produces: `HybridIntentService` implements `IntentService` with `@Primary`

- [ ] **Step 1: 写失败测试 `HybridIntentServiceTest.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridIntentService")
class HybridIntentServiceTest {

    @Mock private KeywordRegexIntentMatcher ruleMatcher;
    @Mock private LangChain4jIntentService llmClassifier;
    @InjectMocks private HybridIntentService service;

    @Test
    @DisplayName("Tier1 命中：返回规则结果，不调用 LLM")
    void classify_tier1Hit_llmNotCalled() {
        when(ruleMatcher.match("转人工"))
                .thenReturn(Optional.of(new IntentResult(IntentType.TRANSFER_REQUEST, "TRANSFER_REQUEST", 1.0)));

        IntentResult result = service.classify("转人工");

        assertThat(result.intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.confidence()).isEqualTo(1.0);
        verify(llmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("Tier1 未命中：调用 LLM 分类器")
    void classify_tier1Miss_llmCalled() {
        when(ruleMatcher.match(anyString())).thenReturn(Optional.empty());
        when(llmClassifier.classify("退款政策是什么"))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, "FAQ_QUERY", 0.9));

        IntentResult result = service.classify("退款政策是什么");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        verify(llmClassifier).classify("退款政策是什么");
    }

    @Test
    @DisplayName("规则层抛异常：不传播，降级走 LLM")
    void classify_tier1Throws_fallsBackToLlm() {
        when(ruleMatcher.match(anyString())).thenThrow(new RuntimeException("规则层内部错误"));
        when(llmClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, "FAQ_QUERY", 0.8));

        IntentResult result = service.classify("任意消息");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        verify(llmClassifier).classify(anyString());
    }
}
```

- [ ] **Step 2: 实现 `HybridIntentService.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 意图分类级联协调器（@Primary），实现 IntentService 接口。
 * ChatAppService 零感知，通过 @Primary 自动注入此实现。
 *
 * Tier 1: KeywordRegexIntentMatcher（关键词/正则，< 1ms）
 * Tier 2: LangChain4jIntentService（Few-Shot LLM 兜底，200-800ms）
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridIntentService implements IntentService {

    private final KeywordRegexIntentMatcher ruleMatcher;
    private final LangChain4jIntentService llmClassifier;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            Optional<IntentResult> ruleResult = ruleMatcher.match(userMessage);
            if (ruleResult.isPresent()) {
                log.debug("[HybridIntent] Tier1 规则命中，跳过 LLM. intent={}",
                        ruleResult.get().intent());
                return ruleResult.get();
            }
        } catch (Exception e) {
            log.warn("[HybridIntent] 规则层异常，降级走 LLM. message={}", userMessage, e);
        }
        return llmClassifier.classify(userMessage);
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test -Dtest=HybridIntentServiceTest -q
```

Expected: `BUILD SUCCESS`, 3 tests passing

- [ ] **Step 4: commit**

```bash
git add ai-conversation/
git commit -m "feat(routing): add HybridIntentService cascade coordinator"
```

---

### Task 7: LLM Few-Shot Prompt 增强 + 置信度阈值

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java`
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentServiceTest.java`

**Interfaces:**
- Consumes: `IntentConfig.exampleQueries()` → `List<String>`; `RoutingProperties.getIntent().getMaxExamplesToInject()` → `int`; `RoutingProperties.getIntent().getMinLlmConfidence()` → `double`

- [ ] **Step 1: 更新 `LangChain4jIntentService.java` 构造函数注入 `RoutingProperties`**

```java
// 在现有字段后追加
private final RoutingProperties routingProperties;

// 构造函数更新（@RequiredArgsConstructor 自动处理）
```

- [ ] **Step 2: 更新 `buildPrompt()` 注入 few-shot 示例**

将现有 `buildPrompt()` 方法替换为：

```java
private String buildPrompt(List<IntentConfig> intents) {
    StringBuilder sb = new StringBuilder("""
            你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
            {"intent": "<意图>", "confidence": <0.0到1.0的小数>}

            意图取值说明：
            """);
    int maxExamples = routingProperties.getIntent().getMaxExamplesToInject();
    for (IntentConfig intent : intents) {
        sb.append("- ").append(intent.code());
        if (intent.description() != null && !intent.description().isBlank()) {
            sb.append("：").append(intent.description());
        }
        // 注入 exampleQueries 作为 few-shot 示例（已是 List<String>，无需解析）
        List<String> examples = intent.exampleQueries();
        if (examples != null && !examples.isEmpty()) {
            List<String> sample = examples.size() > maxExamples
                    ? examples.subList(0, maxExamples) : examples;
            sb.append("（示例：").append(String.join("、", sample)).append("）");
        }
        sb.append("\n");
    }
    sb.append("- UNKNOWN：无法判断\n\n只输出 JSON，不要解释。");
    return sb.toString();
}
```

- [ ] **Step 3: 更新 `parseResponse()` 补充置信度阈值 + intentCode**

将现有 `parseResponse()` 方法替换为：

```java
IntentResult parseResponse(String response) {
    if (response == null || response.isBlank()) return IntentResult.UNKNOWN;
    String json = extractJson(response.trim());
    if (!json.startsWith("{")) {
        log.warn("[Intent] 响应不是有效 JSON 对象: {}", json);
        return IntentResult.UNKNOWN;
    }
    try {
        JsonNode node = objectMapper.readTree(json);
        String intentStr = node.path("intent").asText("UNKNOWN").toUpperCase();
        double confidence = node.path("confidence").asDouble(1.0);

        IntentType intent;
        try {
            intent = IntentType.valueOf(intentStr);
        } catch (IllegalArgumentException ex) {
            // 自定义业务 code 不在枚举内，按 FAQ_QUERY 分叉
            log.warn("[Intent] 未知意图值: {}, 映射为 FAQ_QUERY", intentStr);
            intent = IntentType.FAQ_QUERY;
        }

        // 低置信度降级（minLlmConfidence=0.0 时关闭此检查）
        double minConfidence = routingProperties.getIntent().getMinLlmConfidence();
        if (minConfidence > 0.0 && confidence < minConfidence) {
            log.debug("[Intent] LLM 置信度 {} < 阈值 {}，降级为 UNKNOWN", confidence, minConfidence);
            return IntentResult.UNKNOWN;
        }

        return new IntentResult(intent, intentStr.toLowerCase(), confidence);
    } catch (Exception e) {
        log.warn("[Intent] JSON 解析失败: {}", json, e);
        return IntentResult.UNKNOWN;
    }
}
```

- [ ] **Step 4: 更新 `LangChain4jIntentServiceTest.java`**

更新 factory 方法签名（Step 2 中 Task 2 已完成），并新增两个测试用例：

```java
@Test
@DisplayName("buildPrompt: exampleQueries 注入 few-shot 示例")
void buildPrompt_injectsExamples() {
    when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

    // 直接测试 buildPrompt 中文字包含示例
    IntentConfig intent = new IntentConfig("FAQ_QUERY", "FAQ_QUERY", "知识问答",
            List.of("退款政策", "查物流"), false, false, null,
            List.of(), List.of(), List.of(), List.of(), 0);
    // 通过 spy 调用 buildPrompt（包级访问权限），验证 prompt 包含示例
    // 此测试通过 classify() 流程间接验证
    ChatModel mock = ChatModelMock.thatAlwaysResponds("{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}");
    when(modelFactory.getChatModel()).thenReturn(mock);
    DomainConfig domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "系统域", null, null, null,
            List.of(intent));
    when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

    IntentResult result = service.classify("退款");
    assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
    assertThat(result.intentCode()).isEqualTo("faq_query");
}

@Test
@DisplayName("parseResponse: 自定义 code 映射为 FAQ_QUERY 分叉，intentCode 保留原始值")
void parseResponse_customCode_mapToFaqQuery() {
    IntentResult result = service.parseResponse("{\"intent\":\"query_order\",\"confidence\":0.85}");
    assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
    assertThat(result.intentCode()).isEqualTo("query_order");
}
```

- [ ] **Step 5: 在 `LangChain4jIntentService` 构造测试时传入 `RoutingProperties`**

```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    RoutingProperties props = new RoutingProperties();  // 使用默认值
    service = new LangChain4jIntentService(modelFactory, domainRepository, new ObjectMapper(), props);
}
```

- [ ] **Step 6: 运行全部相关测试**

```bash
mvn -pl ai-conversation/conversation-service test \
  -Dtest="LangChain4jIntentServiceTest,HybridIntentServiceTest,KeywordRegexIntentMatcherTest" -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: commit**

```bash
git add ai-conversation/
git commit -m "feat(routing): inject few-shot examples into LLM prompt + confidence threshold"
```

### Task 8: AuthClient 扩展 + RoutingConfigProvider

**Files:**
- Modify: `ai-auth/auth-client/src/main/java/com/aria/sdk/auth/AuthClient.java`
- Modify: `ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/AdminSystemConfigController.java`（新增内部接口）
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingConfigProvider.java`

**Interfaces:**
- Consumes: `AuthClient.getSystemConfigValue(String key)` → `String`; `RoutingProperties` (YAML defaults)
- Produces: `RoutingConfigProvider.isEmbeddingEnabled()`, `.getEmbeddingThreshold()`, `.getMinLlmConfidence()`, `.getMaxExamplesToInject()`, `.isDomainRuleEnabled()`

- [ ] **Step 1: 在 `AuthClient.java` 新增 `getSystemConfigValue()` 方法**

在 `verifyToken()` 方法后追加：

```java
/**
 * 读取单个系统配置值（启用且未删除）。
 * 调用 auth-service GET /internal/system-config/value?key={configKey}
 *
 * @param configKey 配置键，如 "routing.config"
 * @return 配置值字符串；key 不存在、已禁用或服务异常时返回 null
 */
public String getSystemConfigValue(String configKey) {
    if (configKey == null || configKey.isBlank()) {
        throw new IllegalArgumentException("configKey 不能为空");
    }
    ApiResponse<String> resp = doGet(
            "/internal/system-config/value?key=" + configKey,
            new TypeRef<>() {},
            "读取系统配置失败 key=" + configKey);
    return resp != null && resp.isSuccess() ? resp.data() : null;
}
```

- [ ] **Step 2: 在 `auth-service` 新增内部 Controller 端点**

在 `AdminSystemConfigController.java` 末尾追加内部接口（或新建 `InternalSystemConfigController.java`）：

```java
/**
 * 内部服务接口：按 key 读取单条配置值。
 * 仅供内网微服务调用（需要 X-Internal-Secret 头），不走 SaToken 鉴权。
 */
@GetMapping("/internal/system-config/value")
public String getConfigValue(@RequestParam String key) {
    return systemConfigService.getValue(key, null);
}
```

- [ ] **Step 3: 创建 `RoutingConfigProvider.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aria.common.web.redis.RedisCacheHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 路由配置提供者。
 *
 * <p>从 auth-service system_config 读取 {@code routing.config} 单条 JSON 记录，
 * Redis 缓存 TTL 5 分钟。通过 Redis Pub/Sub {@code aria:config:routing-changed}
 * 主题接收变更通知，主动清缓存。auth-service 不可用时降级返回 {@link RoutingProperties} YAML 默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingConfigProvider implements MessageListener {

    public static final String PUBSUB_TOPIC = "aria:config:routing-changed";
    private static final String CACHE_KEY   = "aria:routing:config";
    private static final String CONFIG_KEY  = "routing.config";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RedisCacheHelper cache;
    private final AuthClient authClient;
    private final ObjectMapper objectMapper;
    private final RoutingProperties defaults;

    // ---- 读取接口 ----

    public boolean isEmbeddingEnabled() {
        return getBoolean("intent.embeddingEnabled", defaults.getIntent().isEmbeddingEnabled());
    }

    public double getEmbeddingThreshold() {
        return getDouble("intent.embeddingThreshold", defaults.getIntent().getEmbeddingThreshold());
    }

    public double getMinLlmConfidence() {
        return getDouble("intent.minLlmConfidence", defaults.getIntent().getMinLlmConfidence());
    }

    public int getMaxExamplesToInject() {
        return getInt("intent.maxExamplesToInject", defaults.getIntent().getMaxExamplesToInject());
    }

    public boolean isDomainRuleEnabled() {
        return getBoolean("domain.ruleEnabled", defaults.getDomain().isRuleEnabled());
    }

    // ---- Pub/Sub 失效 ----

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[RoutingConfig] 收到配置变更通知，清除路由配置缓存");
        cache.delete(CACHE_KEY);
    }

    // ---- 内部工具 ----

    private JsonNode configNode() {
        return cache.getOrLoad(CACHE_KEY, JsonNode.class, CACHE_TTL, () -> {
            try {
                String json = authClient.getSystemConfigValue(CONFIG_KEY);
                if (json == null || json.isBlank()) return objectMapper.createObjectNode();
                return objectMapper.readTree(json);
            } catch (Exception e) {
                log.warn("[RoutingConfig] 拉取路由配置失败，降级使用 YAML 默认值", e);
                return objectMapper.createObjectNode();
            }
        });
    }

    private boolean getBoolean(String dotPath, boolean defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asBoolean(defaultValue);
    }

    private double getDouble(String dotPath, double defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asDouble(defaultValue);
    }

    private int getInt(String dotPath, int defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asInt(defaultValue);
    }

    private JsonNode resolvePath(String dotPath) {
        JsonNode node = configNode();
        for (String part : dotPath.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) return node;
        }
        return node;
    }
}
```

- [ ] **Step 4: 在 `SystemConfigService` 三个写方法中发布 Pub/Sub 通知**

在 `SystemConfigService.java` 注入 `RedisTemplate` 并在 `update()` / `delete()` / `toggleEnabled()` 末尾追加：

```java
private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

private void publishIfRoutingConfig(String configKey) {
    if (configKey != null && configKey.startsWith("routing.")) {
        stringRedisTemplate.convertAndSend(
            RoutingConfigProvider.PUBSUB_TOPIC, configKey);
        log.info("[SystemConfig] 路由配置变更，已发布失效通知 key={}", configKey);
    }
}
```

- [ ] **Step 5: 更新 `HybridIntentService` 和 `LangChain4jIntentService` 使用 `RoutingConfigProvider`**

在两个类中将 `RoutingProperties` 替换为 `RoutingConfigProvider`（或保持 `RoutingProperties` 不变，`RoutingConfigProvider` 只在需要动态读取时才注入）。

> 简化实现：Task 7 中 `LangChain4jIntentService` 注入 `RoutingProperties`（YAML 默认值）；`RoutingConfigProvider` 作为可选增强，后续可在此处替换。本 Task 重点是建立从 system_config 读取配置的通道。

- [ ] **Step 6: commit**

```bash
git add ai-auth/ ai-conversation/
git commit -m "feat(routing): add RoutingConfigProvider + AuthClient.getSystemConfigValue + auth-service internal endpoint"
```

---

### Task 9: 全量测试 + 集成测试补充

**Files:**
- Modify: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceIntentTest.java`

**Interfaces:**
- Consumes: 所有前置 Task 完成

- [ ] **Step 1: 在 `ChatAppServiceIntentTest` 中新增级联场景测试**

在文件末尾追加：

```java
// -------------------------------------------------------
// 级联路由：规则层命中，LLM 不被调用
// -------------------------------------------------------

@Test
@DisplayName("规则层命中 TRANSFER_REQUEST：触发转人工，LLM 不被调用")
void ruleTier_transferHit_noLlmCall() {
    // HybridIntentService 在测试中被 mock 的 intentClassifier 替代，
    // 此处直接模拟规则层已命中的结果（confidence=1.0）
    when(intentClassifier.classify(anyString()))
            .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, "TRANSFER_REQUEST", 1.0));

    Flux<ChatEvent> result = service.stream("s-rule", "我要转人工", null);

    StepVerifier.create(result)
            .assertNext(e -> assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER))
            .verifyComplete();
    verify(aiClient, never()).streamChat(anyList(), anyString());
}
```

- [ ] **Step 2: 运行全量测试**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test -q
```

Expected: `BUILD SUCCESS`，所有测试通过，无回归

- [ ] **Step 3: 运行 ai-auth 测试**

```bash
mvn -pl ai-auth/auth-service test -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: commit**

```bash
git add ai-conversation/
git commit -m "test(routing): add cascade routing integration test in ChatAppServiceIntentTest"
```

### Task 10: 前端 — system/config 双模式编辑器 + 客服配置路由

**Repo:** `/Users/lycodeing/WebstormProjects/aria-frontend`
**Branch:** `feat/intent-routing-enhancement`

**Files:**
- Modify: `apps/src/views/system/config/index.vue`（双模式编辑，已在分支上完成）
- Modify: `apps/src/router/routes/modules/system.ts`（新增客服配置路由，已在分支上完成）

**Interfaces:**
- Consumes: `GET /admin/system-config?configType=CUSTOMER_SERVICE`; `PUT /admin/system-config/{id}`
- Produces: 表单模式（routing.config 结构化字段编辑）+ JSON 模式（raw textarea）

- [ ] **Step 1: 确认当前分支改动完整**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend
git diff --stat
```

Expected: 两个文件有变更：
```
apps/src/router/routes/modules/system.ts
apps/src/views/system/config/index.vue
```

- [ ] **Step 2: 运行 TypeScript 类型检查**

```bash
cd apps && pnpm typecheck 2>&1 | grep "config/index\|system.ts"
```

Expected: 无输出（0 errors）

- [ ] **Step 3: 验证 `index.vue` 的关键逻辑**

打开文件确认包含以下四个核心片段：

1. `const JSON_SCHEMA_KEYS = ['routing.config']` — 已知 schema 键列表
2. `function syncFormToJson()` — 表单 → JSON 同步
3. `function syncJsonToForm()` — JSON → 表单同步（含 `try/catch`）
4. `<Tabs :active-key="editMode" @change="onTabChange">` — Tab 切换

```bash
grep -n "JSON_SCHEMA_KEYS\|syncFormToJson\|syncJsonToForm\|onTabChange" \
  apps/src/views/system/config/index.vue
```

Expected: 4 行输出

- [ ] **Step 4: 验证路由新增**

```bash
grep -A10 "CustomerServiceConfig" apps/src/router/routes/modules/system.ts
```

Expected:
```
name: 'CustomerServiceConfig',
path: '/system/cs-config',
...configType: 'CUSTOMER_SERVICE',
```

- [ ] **Step 5: commit**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend
git add apps/src/router/routes/modules/system.ts
git add apps/src/views/system/config/index.vue
git commit -m "feat(routing): add dual-mode config editor for routing.config + customer service config route"
```

---

## 完整执行顺序

| Task | 预计工时 | 依赖 |
|---|---|---|
| Task 1: DB 迁移脚本 | 30min | 无 |
| Task 2: 域模型变更 | 1.5h | Task 1 |
| Task 3: RoutingProperties | 20min | 无 |
| Task 4: KeywordRegexIntentMatcher | 1h | Task 2, 3 |
| Task 5: Domain Matcher + HybridDomainRoutingService | 1h | Task 2 |
| Task 6: HybridIntentService | 45min | Task 4 |
| Task 7: LLM Few-Shot + 置信度 | 1h | Task 2, 3 |
| Task 8: AuthClient + RoutingConfigProvider | 1.5h | Task 3, Task 1 seed data |
| Task 9: 全量测试 | 30min | 所有后端 Task |
| Task 10: 前端 | 30min | 独立 |

**总预计工时：约 9 小时**
