# 意图识别分层改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有单一 LLM 意图分类替换为三层混合架构（规则 → BERT FastAPI → LLM 兜底），降低意图分类延迟 90% 以上，LLM token 消耗减少 ≥ 90%。

**Architecture:** `HybridIntentService`（`@Primary`）串联三层分类器：Layer 1 关键词规则（<1ms）、Layer 2 BERT FastAPI HTTP（~10ms）、Layer 3 现有 `LangChain4jIntentService` 兜底。BERT 服务地址通过新增的 INTENT 模型角色在管理后台统一配置，与 ROUTER 角色并列管理，支持运行期热切换。`ChatAppService` 零改动。

**Tech Stack:** Java 21, Spring Boot 3.3.5, WebFlux WebClient, LangChain4j, MyBatis-Plus, PostgreSQL, Caffeine Cache, Python FastAPI（外部服务，不在本计划实现范围内）

---

## Global Constraints

- 包路径前缀：`com.aria`
- `ChatAppService.java` 零改动（验收红线）
- `LangChain4jIntentService.java` 零改动，保留为 Layer 3
- 所有新 Spring Bean 使用构造函数注入（`@RequiredArgsConstructor` 或显式构造），不用 `@Autowired` 字段注入
- 日志 tag 统一用 `[Intent]` 前缀
- 异常不向上抛出，所有 classify 方法均降级返回 null 或 `IntentResult.UNKNOWN`
- 测试用 JUnit 5 + Mockito，不启动 Spring 容器
- 提交信息格式：`feat(intent): <内容>`

---

## 涉及文件总览

### 新建
| 文件 | 模块 | 说明 |
|---|---|---|
| `infrastructure/dit/config/KeywordMatchMode.java` | conversation-service | 关键词匹配模式枚举 |
| `infrastructure/ai/RuleIntentClassifier.java` | conversation-service | Layer 1 规则引擎 |
| `infrastructure/ai/BertIntentClassifier.java` | conversation-service | Layer 2 BERT HTTP 客户端 |
| `infrastructure/ai/HybridIntentService.java` | conversation-service | 三层编排主类 `@Primary` |
| `test/.../RuleIntentClassifierTest.java` | conversation-service | Layer 1 测试 |
| `test/.../BertIntentClassifierTest.java` | conversation-service | Layer 2 测试 |
| `test/.../HybridIntentServiceTest.java` | conversation-service | 三层路由测试 |

### 修改
| 文件 | 模块 | 改动 |
|---|---|---|
| `sdk/auth/model/ModelScope.java` | auth-client | 新增 `INTENT` 枚举值 |
| `common/web/ai/AiModelScopeDefaults.java` | common-web | 新增 `INTENT` 枚举值 |
| `common/web/ai/AiModelConfigProvider.java` | common-web | 新增 `getActiveIntent()` + `invalidateIntent()` |
| `common/web/ai/RemoteAiModelConfigProvider.java` | common-web | 实现 `getActiveIntent()` + `invalidateIntent()` |
| `auth/application/ai/LocalAiModelConfigProvider.java` | auth-service | 实现 `getActiveIntent()` + `invalidateIntent()` |
| `auth/application/service/AiModelConfigService.java` | auth-service | 新增 `getActiveIntentConfig()` |
| `auth/interfaces/rest/InternalAiModelController.java` | auth-service | 新增 `GET /active-intent` 端点 |
| `dit/config/IntentConfig.java` | conversation-service | 新增 `keywords` + `keywordMatchMode` 字段 |
| `dit/domain/IntentDO.java` | conversation-service | 新增 `keywords` + `keywordMatchMode` 字段 |
| `dit/repository/DomainRepository.java` | conversation-service | `buildDomainConfig` 传入新字段 |
| `infrastructure/ai/DynamicModelFactory.java` | conversation-service | 新增 `getIntentServiceUrl()` |
| `docs/sql/ai_customerservice-schema.sql` | docs | cs_intent 表新增两列 |

---

## Task 1: INTENT 模型角色基础设施

**Files:**
- Modify: `ai-auth/auth-client/src/main/java/com/aria/sdk/auth/model/ModelScope.java`
- Modify: `ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelScopeDefaults.java`
- Modify: `ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelConfigProvider.java`
- Modify: `ai-common/common-web/src/main/java/com/aria/common/web/ai/RemoteAiModelConfigProvider.java`
- Modify: `ai-auth/auth-service/src/main/java/com/aria/auth/application/ai/LocalAiModelConfigProvider.java`
- Modify: `ai-auth/auth-service/src/main/java/com/aria/auth/application/service/AiModelConfigService.java`
- Modify: `ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/InternalAiModelController.java`

**Interfaces:**
- Produces: `AiModelConfigProvider.getActiveIntent()` → `AiModelConfig`（供 Task 4 `DynamicModelFactory.getIntentServiceUrl()` 使用）

- [ ] **Step 1: ModelScope 新增 INTENT 枚举值**

在 `ModelScope.java` 的 `ROUTER` 后追加：

```java
/** BERT FastAPI 意图分类服务（Layer 2 轻量模型） */
INTENT("/internal/ai-models/active-intent");
```

- [ ] **Step 2: AiModelScopeDefaults 新增 INTENT 枚举值**

在 `AiModelScopeDefaults.java` 的 `ROUTER` 后追加：

```java
/** BERT 意图分类服务缺省参数：temperature 无意义（非生成型），timeoutSec=1s */
INTENT(0.0D, 0, 1);
```

- [ ] **Step 3: AiModelConfigProvider 接口新增两个方法**

在接口末尾追加：

```java
/**
 * 获取当前激活的 INTENT 模型配置（BERT FastAPI 意图分类服务地址）。
 * baseUrl 指向 Python FastAPI 服务，如 http://bert-intent-svc:8090。
 * 服务未配置时返回 null（不抛异常），调用方自行跳过 Layer 2。
 *
 * @return INTENT 模型配置；未配置时返回 null
 */
AiModelConfig getActiveIntent();

/**
 * 主动失效 INTENT 配置本地缓存（收到 Redis Pub/Sub 通知时调用）。
 */
void invalidateIntent();
```

- [ ] **Step 4: RemoteAiModelConfigProvider 实现新增方法**

在 `ROUTER_CACHE_KEY` 常量后追加：

```java
private static final String INTENT_CACHE_KEY = "aria:ai:model:intent:active";
```

在 `invalidateRouter()` 方法后追加：

```java
// ---- INTENT 配置 ----

@Override
public AiModelConfig getActiveIntent() {
    try {
        return cache.getOrLoad(INTENT_CACHE_KEY, AiModelConfig.class, CACHE_TTL,
                () -> loadFromRemote(ModelScope.INTENT, AiModelScopeDefaults.INTENT));
    } catch (Exception e) {
        log.warn("[AiConfig] INTENT 配置获取失败，返回 null（BertIntentClassifier 将跳过 Layer 2）", e);
        return null;
    }
}

@Override
public void invalidateIntent() {
    cache.delete(INTENT_CACHE_KEY);
    log.info("[AiConfig] INTENT 配置缓存已失效，下次请求将重新拉取");
}
```

同时在 `onMessage` 方法中追加 `invalidateIntent()` 调用：

```java
@Override
public void onMessage(Message message, byte[] pattern) {
    log.info("[AiConfig] 收到配置变更通知，清除 CHAT + EMBEDDING + ROUTER + INTENT 缓存");
    invalidate();
    invalidateEmbedding();
    invalidateRouter();
    invalidateIntent();   // 新增
}
```

- [ ] **Step 5: LocalAiModelConfigProvider 实现新增方法**

在 `invalidateRouter()` 方法后追加：

```java
@Override
public AiModelConfig getActiveIntent() {
    AiModelConfigDO active = service.getActiveIntentConfig();
    if (active == null) {
        // INTENT 配置不存在时返回 null，Layer 2 将跳过，不抛异常
        return null;
    }
    return toConfig(active, AiModelScopeDefaults.INTENT);
}

@Override
public void invalidateIntent() {
    // 本地实现无本地缓存；no-op
}
```

- [ ] **Step 6: AiModelConfigService 新增 getActiveIntentConfig()**

参考 `getActiveRouterConfig()` 的实现，在其后追加：

```java
/**
 * 查询当前默认 INTENT 配置（BERT 意图分类服务，供 conversation-service 拉取）。
 * 未配置时返回 null，调用方跳过 Layer 2 即可，不抛异常。
 */
public AiModelConfigDO getActiveIntentConfig() {
    return lambdaQuery()
            .eq(AiModelConfigDO::getModelType, "INTENT")
            .eq(AiModelConfigDO::getIsDefault, true)
            .eq(AiModelConfigDO::getEnabled, true)
            .one();
}
```

- [ ] **Step 7: InternalAiModelController 新增 /active-intent 端点**

在 `getActiveRouter()` 方法后追加：

```java
/**
 * 返回当前激活（默认）的 INTENT 模型配置（BERT FastAPI 服务地址）。
 * 供 conversation-service 拉取意图分类服务连接信息，仅限内网调用。
 * 未配置时返回 404，调用方（RemoteAiModelConfigProvider）应容错处理。
 *
 * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
 */
@GetMapping("/active-intent")
public R<AiModelConfig> getActiveIntent(
        @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
    if (!secretVerifier.matches(secret)) {
        log.warn("[InternalAiModel] 内部密钥校验失败，拒绝访问 /active-intent");
        return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
    }
    AiModelConfigDO d = service.getActiveIntentConfig();
    if (d == null) {
        return R.fail(NOT_FOUND_CODE, "未配置 INTENT 模型，Layer 2 将跳过");
    }
    return R.ok(toConfig(d, AiModelScopeDefaults.INTENT));
}
```

- [ ] **Step 8: 编译验证**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-auth/auth-client,ai-common/common-web,ai-auth/auth-service,ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 9: 提交**

```bash
git add \
  ai-auth/auth-client/src/main/java/com/aria/sdk/auth/model/ModelScope.java \
  ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelScopeDefaults.java \
  ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelConfigProvider.java \
  ai-common/common-web/src/main/java/com/aria/common/web/ai/RemoteAiModelConfigProvider.java \
  ai-auth/auth-service/src/main/java/com/aria/auth/application/ai/LocalAiModelConfigProvider.java \
  ai-auth/auth-service/src/main/java/com/aria/auth/application/service/AiModelConfigService.java \
  ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/InternalAiModelController.java
git commit -m "feat(intent): add INTENT model scope to auth/common infrastructure"
```

---

## Task 2: DB 扩展 + IntentDO/IntentConfig/DomainRepository

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/KeywordMatchMode.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/IntentDO.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/IntentConfig.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java`
- Modify: `docs/sql/ai_customerservice-schema.sql`

**Interfaces:**
- Consumes: 无（此 Task 独立）
- Produces: `IntentConfig.keywords()` → `List<String>`；`IntentConfig.keywordMatchMode()` → `KeywordMatchMode`（供 Task 3 `RuleIntentClassifier` 使用）

- [ ] **Step 1: 执行 DB 迁移 SQL**

在数据库执行以下 SQL（在 `cs_conversation` schema 下）：

```sql
-- cs_intent 表新增关键词列
ALTER TABLE cs_conversation.cs_intent
    ADD COLUMN IF NOT EXISTS keywords          jsonb   DEFAULT '[]'::jsonb NOT NULL,
    ADD COLUMN IF NOT EXISTS keyword_match_mode varchar(32) DEFAULT 'ANY_CONTAINS' NOT NULL;

COMMENT ON COLUMN cs_conversation.cs_intent.keywords           IS '关键词列表，用于 Layer 1 规则分类，JSON 数组，如 ["转人工","找真人"]';
COMMENT ON COLUMN cs_conversation.cs_intent.keyword_match_mode IS '关键词匹配模式：ANY_CONTAINS / ALL_CONTAINS / REGEX';
```

- [ ] **Step 2: 更新 docs/sql/ai_customerservice-schema.sql**

在 `cs_intent` 表的 `sort_order` 列定义后追加两列（保持与 DB 实际结构一致）：

```sql
    keywords            jsonb   DEFAULT '[]'::jsonb NOT NULL,
    keyword_match_mode  character varying(32) DEFAULT 'ANY_CONTAINS' NOT NULL,
```

并在该表的 COMMENT 块末尾追加：

```sql
COMMENT ON COLUMN cs_conversation.cs_intent.keywords           IS '关键词列表，用于 Layer 1 规则分类，JSON 数组，如 ["转人工","找真人"]';
COMMENT ON COLUMN cs_conversation.cs_intent.keyword_match_mode IS '关键词匹配模式：ANY_CONTAINS / ALL_CONTAINS / REGEX';
```

- [ ] **Step 3: 创建 KeywordMatchMode 枚举**

新建文件 `infrastructure/dit/config/KeywordMatchMode.java`：

```java
package com.aria.conversation.infrastructure.dit.config;

/**
 * 意图关键词匹配模式。
 *
 * <p>用于 Layer 1 规则分类器，控制关键词列表的匹配语义。
 */
public enum KeywordMatchMode {

    /**
     * 任意包含：用户消息包含关键词列表中的任意一个即命中（默认）。
     * 适合：转人工、投诉等单词即可识别的意图。
     */
    ANY_CONTAINS,

    /**
     * 全部包含：用户消息必须包含关键词列表中的全部关键词才命中。
     * 适合：需要多个词同时出现才能确认意图的场景。
     */
    ALL_CONTAINS,

    /**
     * 正则匹配：将 keywords[0] 作为 Java 正则表达式，对用户消息进行 find 匹配。
     * 适合：需要模式匹配的高级场景。
     */
    REGEX
}
```

- [ ] **Step 4: 修改 IntentDO 新增两个字段**

在 `IntentDO.java` 的 `fallbackReply` 字段后追加：

```java
/**
 * 关键词列表，JSON 数组字符串，如 ["转人工","找真人","人工客服"]。
 * 非空时 Layer 1 规则分类器启用关键词匹配。
 */
@TableField(typeHandler = JsonbTypeHandler.class)
private String keywords;

/**
 * 关键词匹配模式，对应 {@link com.aria.conversation.infrastructure.dit.config.KeywordMatchMode}。
 * 默认 ANY_CONTAINS。
 */
private String keywordMatchMode;
```

- [ ] **Step 5: 修改 IntentConfig record 新增两个字段**

将 `IntentConfig.java` 整体替换为：

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;

/**
 * 意图配置（只读，从 cs_intent 映射，含关联的槽位和工具绑定）。
 *
 * @param code             意图标识，如 "query_order"
 * @param name             意图名称，如 "查询订单"
 * @param description      给 LLM 的意图说明
 * @param exampleQueries   少样本示例（JSON 数组字符串）
 * @param autoTransfer     是否自动转人工
 * @param skipRag          是否跳过 RAG
 * @param fallbackReply    工具失败兜底回复
 * @param slots            槽位列表（按 sort_order 排序）
 * @param toolBindings     工具绑定列表（按 execution_order 排序）
 * @param keywords         关键词列表，非空时 Layer 1 规则分类器启用匹配
 * @param keywordMatchMode 关键词匹配模式，null 时默认 ANY_CONTAINS
 */
public record IntentConfig(
        String code,
        String name,
        String description,
        String exampleQueries,
        boolean autoTransfer,
        boolean skipRag,
        String fallbackReply,
        List<SlotConfig> slots,
        List<IntentToolBinding> toolBindings,
        List<String> keywords,
        KeywordMatchMode keywordMatchMode
) implements Serializable {

    /** 获取所有 REQUIRED 工具绑定，按 executionOrder 升序排列 */
    public List<IntentToolBinding> requiredTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isRequired)
                .sorted(Comparator.comparingInt(IntentToolBinding::executionOrder))
                .toList();
    }

    /** 获取所有 OPTIONAL 工具绑定 */
    public List<IntentToolBinding> optionalTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isOptional)
                .toList();
    }

    /** 是否启用关键词规则匹配（keywords 非空） */
    public boolean hasKeywords() {
        return keywords != null && !keywords.isEmpty();
    }

    /** 获取匹配模式，null 时返回 ANY_CONTAINS */
    public KeywordMatchMode resolvedMatchMode() {
        return keywordMatchMode != null ? keywordMatchMode : KeywordMatchMode.ANY_CONTAINS;
    }
}
```

- [ ] **Step 6: 修改 DomainRepository.buildDomainConfig 传入新字段**

在 `DomainRepository.java` 的 `buildDomainConfig` 方法中，找到 `new IntentConfig(...)` 调用，替换为：

```java
intents.add(new IntentConfig(
        intentDO.getCode(),
        intentDO.getName(),
        intentDO.getDescription(),
        intentDO.getExampleQueries(),
        Boolean.TRUE.equals(intentDO.getAutoTransfer()),
        Boolean.TRUE.equals(intentDO.getSkipRag()),
        intentDO.getFallbackReply(),
        slots,
        bindings,
        parseJsonArray(intentDO.getKeywords()),          // 新增
        parseKeywordMatchMode(intentDO.getKeywordMatchMode()) // 新增
));
```

在 `DomainRepository.java` 末尾的私有方法区追加：

```java
/**
 * 安全解析 keywordMatchMode 字符串为枚举，未知值降级为 null（调用方用 ANY_CONTAINS 兜底）。
 */
private KeywordMatchMode parseKeywordMatchMode(String value) {
    if (value == null || value.isBlank()) return null;
    try {
        return KeywordMatchMode.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
        log.warn("[DIT] 未知 keywordMatchMode 值: {}，降级为 ANY_CONTAINS", value);
        return null;
    }
}
```

需要在文件顶部追加 import：

```java
import com.aria.conversation.infrastructure.dit.config.KeywordMatchMode;
```

- [ ] **Step 7: 编译验证**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`（若有其他测试文件引用了 IntentConfig 构造，需同步修改传入 `null, null`）

- [ ] **Step 8: 修复受影响的测试文件（如有）**

运行：

```bash
mvn test-compile -pl ai-conversation/conversation-service -q 2>&1 | grep "error:" | head -20
```

若出现 `IntentConfig` 构造参数不匹配，找到对应测试文件，在原有参数末尾追加 `List.of(), null`（表示无关键词配置）。例如：

```java
// 原来 9 个参数
new IntentConfig("FAQ_QUERY", "问答", "...", null, false, false, null, List.of(), List.of())
// 改为 11 个参数
new IntentConfig("FAQ_QUERY", "问答", "...", null, false, false, null, List.of(), List.of(), List.of(), null)
```

- [ ] **Step 9: 运行现有测试**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`，0 failures

- [ ] **Step 10: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/KeywordMatchMode.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/IntentDO.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/IntentConfig.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java \
  docs/sql/ai_customerservice-schema.sql
git commit -m "feat(intent): extend cs_intent with keywords/keyword_match_mode for Layer 1 rule classifier"
```

---

## Task 3: Layer 1 — RuleIntentClassifier

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RuleIntentClassifier.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/RuleIntentClassifierTest.java`

**Interfaces:**
- Consumes: `DomainRepository.findByCode(String)` → `Optional<DomainConfig>`；`IntentConfig.hasKeywords()`；`IntentConfig.resolvedMatchMode()`；`KeywordMatchMode`
- Produces: `RuleIntentClassifier.classify(String)` → `IntentResult`（null = 未命中，供 `HybridIntentService` 判断）

- [ ] **Step 1: 写失败测试**

新建 `RuleIntentClassifierTest.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.KeywordMatchMode;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RuleIntentClassifier Layer 1 规则分类器")
class RuleIntentClassifierTest {

    @Mock
    private DomainRepository domainRepository;

    private RuleIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RuleIntentClassifier(domainRepository);
    }

    // --- 辅助方法 ---

    private IntentConfig intentWithKeywords(String code, List<String> keywords, KeywordMatchMode mode) {
        return new IntentConfig(code, code, "desc", null, false, false, null,
                List.of(), List.of(), keywords, mode);
    }

    private void mockDomain(IntentConfig... intents) {
        DomainConfig domain = new DomainConfig("__system__", "系统域", null, null, null, List.of(intents));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));
    }

    // --- ANY_CONTAINS 测试 ---

    @Test
    @DisplayName("ANY_CONTAINS: 消息包含关键词之一 → 命中对应意图")
    void anyContains_hit() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("转人工", "找真人", "人工客服"), KeywordMatchMode.ANY_CONTAINS));

        IntentResult result = classifier.classify("我想找真人客服");

        assertThat(result).isNotNull();
        assertThat(result.intent().name()).isEqualTo("TRANSFER_REQUEST");
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ANY_CONTAINS: 消息不包含任何关键词 → 返回 null")
    void anyContains_miss() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("转人工", "找真人"), KeywordMatchMode.ANY_CONTAINS));

        assertThat(classifier.classify("退款政策是什么")).isNull();
    }

    @Test
    @DisplayName("ANY_CONTAINS: 全角字符归一化后命中")
    void anyContains_fullWidthNormalized() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("转人工"), KeywordMatchMode.ANY_CONTAINS));

        // 全角"转人工"应归一化为半角后命中
        assertThat(classifier.classify("我要转人工")).isNotNull();
    }

    // --- ALL_CONTAINS 测试 ---

    @Test
    @DisplayName("ALL_CONTAINS: 消息包含所有关键词 → 命中")
    void allContains_hit() {
        mockDomain(intentWithKeywords("COMPLAINT",
                List.of("投诉", "退款"), KeywordMatchMode.ALL_CONTAINS));

        IntentResult result = classifier.classify("我要投诉，申请退款");

        assertThat(result).isNotNull();
        assertThat(result.intent().name()).isEqualTo("COMPLAINT");
    }

    @Test
    @DisplayName("ALL_CONTAINS: 消息只包含部分关键词 → 未命中")
    void allContains_partialMiss() {
        mockDomain(intentWithKeywords("COMPLAINT",
                List.of("投诉", "退款"), KeywordMatchMode.ALL_CONTAINS));

        assertThat(classifier.classify("我要投诉")).isNull();
    }

    // --- REGEX 测试 ---

    @Test
    @DisplayName("REGEX: 正则匹配成功 → 命中")
    void regex_hit() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("(转|找).*(人工|真人)"), KeywordMatchMode.REGEX));

        assertThat(classifier.classify("帮我转接真人")).isNotNull();
    }

    @Test
    @DisplayName("REGEX: 正则不匹配 → 未命中")
    void regex_miss() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("(转|找).*(人工|真人)"), KeywordMatchMode.REGEX));

        assertThat(classifier.classify("普通问题")).isNull();
    }

    // --- 边界场景 ---

    @Test
    @DisplayName("意图无关键词配置 → 跳过，返回 null")
    void noKeywords_skipped() {
        mockDomain(intentWithKeywords("FAQ_QUERY", List.of(), null));

        assertThat(classifier.classify("退款政策")).isNull();
    }

    @Test
    @DisplayName("domain 不存在 → 返回 null，不抛异常")
    void domainNotFound_returnsNull() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        assertThat(classifier.classify("转人工")).isNull();
    }

    @Test
    @DisplayName("DomainRepository 抛异常 → 降级返回 null，不抛出")
    void repositoryException_returnsNull() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenThrow(new RuntimeException("Redis 连接失败"));

        assertThat(classifier.classify("转人工")).isNull();
    }

    @Test
    @DisplayName("REGEX 非法正则 → 降级返回 null，不抛出")
    void invalidRegex_returnsNull() {
        mockDomain(intentWithKeywords("TRANSFER_REQUEST",
                List.of("[invalid regex"), KeywordMatchMode.REGEX));

        assertThat(classifier.classify("转人工")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试，确认全部失败**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service -Dtest=RuleIntentClassifierTest -q 2>&1 | tail -5
```

预期：编译失败（`RuleIntentClassifier` 不存在）

- [ ] **Step 3: 实现 RuleIntentClassifier**

新建 `RuleIntentClassifier.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.KeywordMatchMode;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Layer 1 关键词规则分类器。
 *
 * <p>从 {@code __system__} 域的 {@link IntentConfig#keywords()} 加载规则，
 * 支持 ANY_CONTAINS / ALL_CONTAINS / REGEX 三种匹配模式。
 *
 * <p>命中返回 confidence=1.0 的 {@link IntentResult}；未命中或异常返回 {@code null}，
 * 让 {@link HybridIntentService} 继续 Layer 2。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleIntentClassifier {

    private final DomainRepository domainRepository;

    /**
     * 对用户消息进行关键词规则分类。
     *
     * @param userMessage 用户原始消息
     * @return 命中的意图结果，未命中或异常返回 null
     */
    public IntentResult classify(String userMessage) {
        try {
            DomainConfig domain = domainRepository
                    .findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
            if (domain == null || domain.intents().isEmpty()) {
                return null;
            }
            String normalized = normalize(userMessage);
            for (IntentConfig config : domain.intents()) {
                if (!config.hasKeywords()) continue;
                if (matches(normalized, config.keywords(), config.resolvedMatchMode(), config.code())) {
                    log.debug("[Intent] Layer1-Rule 命中 intent={}", config.code());
                    IntentType intentType = parseIntentType(config.code());
                    return new IntentResult(intentType, 1.0);
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[Intent] Layer1-Rule 分类异常，降级返回 null", e);
            return null;
        }
    }

    /**
     * 消息归一化：trim + 全角转半角。
     * 防止全角字符差异导致关键词漏匹配。
     */
    private String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.trim());
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            // 全角字符范围 \uFF01-\uFF5E，对应半角 \u0021-\u007E
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.setCharAt(i, (char) (c - 0xFEE0));
            }
            // 全角空格 → 半角
            if (c == '\u3000') sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }

    private boolean matches(String text, List<String> keywords,
                            KeywordMatchMode mode, String intentCode) {
        return switch (mode) {
            case ANY_CONTAINS -> keywords.stream().anyMatch(text::contains);
            case ALL_CONTAINS -> keywords.stream().allMatch(text::contains);
            case REGEX        -> matchRegex(text, keywords, intentCode);
        };
    }

    private boolean matchRegex(String text, List<String> keywords, String intentCode) {
        if (keywords.isEmpty()) return false;
        try {
            return Pattern.compile(keywords.get(0)).matcher(text).find();
        } catch (PatternSyntaxException e) {
            log.warn("[Intent] Layer1-Rule 非法正则 intent={} pattern={}", intentCode, keywords.get(0));
            return false;
        }
    }

    private IntentType parseIntentType(String code) {
        try {
            return IntentType.valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[Intent] Layer1-Rule 未知 IntentType code={}，降级 UNKNOWN", code);
            return IntentType.UNKNOWN;
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=RuleIntentClassifierTest -q
```

预期：`BUILD SUCCESS`，11 tests passed

- [ ] **Step 5: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RuleIntentClassifier.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/RuleIntentClassifierTest.java
git commit -m "feat(intent): add Layer 1 RuleIntentClassifier with ANY_CONTAINS/ALL_CONTAINS/REGEX support"
```

---

## Task 4: Layer 2 — BertIntentClassifier + DynamicModelFactory 扩展

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/BertIntentClassifier.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/BertIntentClassifierTest.java`

**Interfaces:**
- Consumes: `AiModelConfigProvider.getActiveIntent()` → `AiModelConfig`（Task 1 产出）
- Produces: `DynamicModelFactory.getIntentServiceUrl()` → `String`（nullable）；`BertIntentClassifier.classify(String)` → `IntentResult`（null = 跳过，供 Task 5 使用）

- [ ] **Step 1: DynamicModelFactory 新增 getIntentServiceUrl()**

在 `DynamicModelFactory.java` 的 `getRouterModel()` 方法后追加：

```java
/**
 * 获取 INTENT 模型的 baseUrl（BERT FastAPI 服务地址）。
 * 供 {@link BertIntentClassifier} 构建 WebClient 时使用，支持运行期热切换。
 *
 * <p>BERT FastAPI 不是 LangChain4j ChatModel，不需要通过 LlmModelBuilder 构建，
 * 只需暴露服务地址供 HTTP 客户端使用。
 *
 * @return BERT 服务 baseUrl；未配置或获取异常时返回 null
 */
public String getIntentServiceUrl() {
    try {
        AiModelConfig cfg = configProvider.getActiveIntent();
        return cfg != null ? cfg.baseUrl() : null;
    } catch (Exception e) {
        log.warn("[AI] 获取 INTENT 模型配置失败，返回 null", e);
        return null;
    }
}
```

- [ ] **Step 2: 写 BertIntentClassifier 失败测试**

新建 `BertIntentClassifierTest.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BertIntentClassifier Layer 2 BERT HTTP 客户端")
class BertIntentClassifierTest {

    @Mock
    private DynamicModelFactory modelFactory;

    private BertIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new BertIntentClassifier(modelFactory);
    }

    @Test
    @DisplayName("URL 未配置（null）→ 返回 null，跳过 Layer 2")
    void urlNull_returnsNull() {
        when(modelFactory.getIntentServiceUrl()).thenReturn(null);

        assertThat(classifier.classify("我要找真人客服")).isNull();
    }

    @Test
    @DisplayName("URL 为空字符串 → 返回 null，跳过 Layer 2")
    void urlBlank_returnsNull() {
        when(modelFactory.getIntentServiceUrl()).thenReturn("  ");

        assertThat(classifier.classify("我要找真人客服")).isNull();
    }

    @Test
    @DisplayName("parseResponse: 标准 JSON 正确解析")
    void parseResponse_standard() {
        IntentResult result = classifier.parseResponse(
                Map.of("intent", "FAQ_QUERY", "confidence", 0.94));

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(result.confidence()).isEqualTo(0.94);
    }

    @Test
    @DisplayName("parseResponse: 未知意图值 → 降级 UNKNOWN")
    void parseResponse_unknownIntent() {
        IntentResult result = classifier.parseResponse(
                Map.of("intent", "BANANA", "confidence", 0.9));

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("parseResponse: confidence 缺失 → 默认 1.0")
    void parseResponse_missingConfidence() {
        IntentResult result = classifier.parseResponse(Map.of("intent", "CHITCHAT"));

        assertThat(result.intent()).isEqualTo(IntentType.CHITCHAT);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("parseResponse: response 为 null → 返回 null")
    void parseResponse_null() {
        assertThat(classifier.parseResponse(null)).isNull();
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=BertIntentClassifierTest -q 2>&1 | tail -5
```

预期：编译失败（`BertIntentClassifier` 不存在）

- [ ] **Step 4: 实现 BertIntentClassifier**

新建 `BertIntentClassifier.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Layer 2 BERT FastAPI 意图分类器。
 *
 * <p>通过 HTTP POST 调用 Python FastAPI 服务（fine-tuned chinese-roberta-wwm-ext），
 * 服务地址从 {@link DynamicModelFactory#getIntentServiceUrl()} 动态读取，
 * 支持管理后台热切换（INTENT 模型配置 baseUrl 变更后下次请求即生效）。
 *
 * <p>返回约定：
 * <ul>
 *   <li>正常分类：返回 {@link IntentResult}（confidence 由 HybridIntentService 判断阈值）</li>
 *   <li>URL 未配置 / 服务超时 / 连接失败：返回 {@code null}，触发 Layer 3 兜底</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BertIntentClassifier {

    /** HTTP 调用超时，超时后降级 Layer 3 */
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final DynamicModelFactory modelFactory;

    /**
     * 对用户消息进行意图分类。
     *
     * @param userMessage 用户原始消息
     * @return 意图分类结果；URL 未配置或服务异常返回 null
     */
    public IntentResult classify(String userMessage) {
        String url = modelFactory.getIntentServiceUrl();
        if (url == null || url.isBlank()) {
            log.debug("[Intent] BERT 服务未配置，跳过 Layer 2");
            return null;
        }
        try {
            Map<?, ?> resp = WebClient.create(url)
                    .post()
                    .uri("/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", userMessage))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            return parseResponse(resp);
        } catch (Exception e) {
            log.warn("[Intent] BERT 服务异常，跳过 Layer 2: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 FastAPI 响应 Map 为 IntentResult。
     * 包内可见，供测试直接调用。
     *
     * @param resp 响应 Map，格式 {"intent": "FAQ_QUERY", "confidence": 0.94}
     * @return 解析结果；resp 为 null 时返回 null
     */
    IntentResult parseResponse(Map<?, ?> resp) {
        if (resp == null) return null;
        try {
            String intentStr = String.valueOf(resp.getOrDefault("intent", "UNKNOWN"))
                    .toUpperCase();
            double confidence = resp.containsKey("confidence")
                    ? ((Number) resp.get("confidence")).doubleValue()
                    : 1.0;
            IntentType intent;
            try {
                intent = IntentType.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                log.warn("[Intent] BERT 返回未知意图值: {}，降级 UNKNOWN", intentStr);
                intent = IntentType.UNKNOWN;
            }
            return new IntentResult(intent, confidence);
        } catch (Exception e) {
            log.warn("[Intent] BERT 响应解析失败: {}", resp, e);
            return null;
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=BertIntentClassifierTest -q
```

预期：`BUILD SUCCESS`，6 tests passed

- [ ] **Step 6: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/BertIntentClassifier.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/BertIntentClassifierTest.java
git commit -m "feat(intent): add Layer 2 BertIntentClassifier and DynamicModelFactory.getIntentServiceUrl()"
```

---

## Task 5: Layer 3 编排 — HybridIntentService

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java`（仅去掉 `@Primary` 如有）
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridIntentService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/HybridIntentServiceTest.java`

**Interfaces:**
- Consumes: `RuleIntentClassifier.classify(String)`（Task 3）；`BertIntentClassifier.classify(String)`（Task 4）；`LangChain4jIntentService.classify(String)`（已有）
- Produces: `HybridIntentService.classify(String)` → `IntentResult`（实现 `IntentService`，`@Primary`，供 `ChatAppService` 使用）

- [ ] **Step 1: 确认 LangChain4jIntentService 没有 @Primary**

检查 `LangChain4jIntentService.java` 类注解，若存在 `@Primary` 则删除（通常不存在，仅确认）：

```bash
grep -n "@Primary" \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java
```

预期：无输出（无 `@Primary`）。若有则删除该注解。

- [ ] **Step 2: 写 HybridIntentService 失败测试**

新建 `HybridIntentServiceTest.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridIntentService 三层编排路由")
class HybridIntentServiceTest {

    @Mock private RuleIntentClassifier ruleClassifier;
    @Mock private BertIntentClassifier bertClassifier;
    @Mock private LangChain4jIntentService llmClassifier;

    private HybridIntentService service;

    // 阈值与生产默认值一致
    private static final double ACCEPT    = 0.80;
    private static final double FALLBACK  = 0.65;

    @BeforeEach
    void setUp() {
        service = new HybridIntentService(ruleClassifier, bertClassifier, llmClassifier,
                ACCEPT, FALLBACK);
    }

    // --- Layer 1 命中 ---

    @Test
    @DisplayName("Layer1 规则命中 → 直接返回，不调 BERT 和 LLM")
    void layer1Hit_shortCircuits() {
        IntentResult rule = new IntentResult(IntentType.TRANSFER_REQUEST, 1.0);
        when(ruleClassifier.classify(anyString())).thenReturn(rule);

        IntentResult result = service.classify("我要转人工");

        assertThat(result.intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        verifyNoInteractions(bertClassifier, llmClassifier);
    }

    // --- Layer 2 高置信度 ---

    @Test
    @DisplayName("Layer1 未命中，Layer2 conf≥0.80 → 直接返回，不调 LLM")
    void layer2HighConf_shortCircuits() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.92));

        IntentResult result = service.classify("退款政策是什么");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(result.confidence()).isEqualTo(0.92);
        verifyNoInteractions(llmClassifier);
    }

    @Test
    @DisplayName("Layer2 conf 恰好等于 accept 阈值（0.80）→ 直接返回")
    void layer2ExactlyAtThreshold_accepted() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.CHITCHAT, 0.80));

        assertThat(service.classify("你好").intent()).isEqualTo(IntentType.CHITCHAT);
        verifyNoInteractions(llmClassifier);
    }

    // --- Layer 2 中等置信度 → 走 LLM ---

    @Test
    @DisplayName("Layer2 0.65≤conf<0.80 → 交 LLM 二次判断")
    void layer2MediumConf_fallsToLlm() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.72));
        when(llmClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.OUT_OF_SCOPE, 0.88));

        IntentResult result = service.classify("帮我算个题");

        assertThat(result.intent()).isEqualTo(IntentType.OUT_OF_SCOPE);
        verify(llmClassifier).classify(anyString());
    }

    // --- Layer 2 低置信度 → 直接 UNKNOWN ---

    @Test
    @DisplayName("Layer2 conf<0.65 → 返回 UNKNOWN，不调 LLM")
    void layer2LowConf_returnsUnknown() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.50));

        IntentResult result = service.classify("asdfqwer");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
        verifyNoInteractions(llmClassifier);
    }

    @Test
    @DisplayName("Layer2 conf 恰好低于 fallback 阈值（0.6499）→ 返回 UNKNOWN")
    void layer2JustBelowFallback_returnsUnknown() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.COMPLAINT, 0.6499));

        assertThat(service.classify("...").intent()).isEqualTo(IntentType.UNKNOWN);
        verifyNoInteractions(llmClassifier);
    }

    // --- Layer 2 不可用 → 走 LLM ---

    @Test
    @DisplayName("Layer2 返回 null（服务不可用）→ 直接走 Layer3 LLM")
    void layer2Unavailable_fallsToLlm() {
        when(ruleClassifier.classify(anyString())).thenReturn(null);
        when(bertClassifier.classify(anyString())).thenReturn(null);
        when(llmClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.95));

        IntentResult result = service.classify("退款流程");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        verify(llmClassifier).classify(anyString());
    }

    // --- 全链路降级 ---

    @Test
    @DisplayName("所有层均降级（LLM 也抛异常）→ 返回 UNKNOWN，不抛出")
    void allLayersFail_returnsUnknown() {
        when(ruleClassifier.classify(anyString())).thenThrow(new RuntimeException("Redis 挂了"));

        IntentResult result = service.classify("测试消息");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=HybridIntentServiceTest -q 2>&1 | tail -5
```

预期：编译失败（`HybridIntentService` 不存在）

- [ ] **Step 4: 实现 HybridIntentService**

新建 `HybridIntentService.java`：

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 三层混合意图识别服务（主实现）。
 *
 * <p>串联执行顺序：
 * <ol>
 *   <li>Layer 1 {@link RuleIntentClassifier} — 关键词规则，< 1ms</li>
 *   <li>Layer 2 {@link BertIntentClassifier} — BERT FastAPI，~ 10ms</li>
 *   <li>Layer 3 {@link LangChain4jIntentService} — LLM 兜底，~ 400ms，触发率 ≤ 10%</li>
 * </ol>
 *
 * <p>置信度路由（Layer 2）：
 * <pre>
 *   conf ≥ acceptThreshold (0.80)  → 直接返回
 *   fallbackThreshold ≤ conf < acceptThreshold → 走 Layer 3
 *   conf < fallbackThreshold (0.65) → 返回 UNKNOWN，不走 Layer 3
 * </pre>
 *
 * <p>{@link com.aria.conversation.application.service.ChatAppService} 零改动，
 * Spring 自动选择本类（{@code @Primary}）注入。
 */
@Slf4j
@Primary
@Component
public class HybridIntentService implements IntentService {

    private final RuleIntentClassifier ruleClassifier;
    private final BertIntentClassifier bertClassifier;
    private final LangChain4jIntentService llmClassifier;
    private final double acceptThreshold;
    private final double fallbackThreshold;

    public HybridIntentService(
            RuleIntentClassifier ruleClassifier,
            BertIntentClassifier bertClassifier,
            LangChain4jIntentService llmClassifier,
            @Value("${intent.confidence.accept:0.80}") double acceptThreshold,
            @Value("${intent.confidence.fallback:0.65}") double fallbackThreshold) {
        this.ruleClassifier    = ruleClassifier;
        this.bertClassifier    = bertClassifier;
        this.llmClassifier     = llmClassifier;
        this.acceptThreshold   = acceptThreshold;
        this.fallbackThreshold = fallbackThreshold;
    }

    @Override
    public IntentResult classify(String userMessage) {
        try {
            // Layer 1: 关键词规则
            IntentResult ruleResult = ruleClassifier.classify(userMessage);
            if (ruleResult != null) {
                log.debug("[Intent] Layer1-Rule 命中 intent={}", ruleResult.intent());
                return ruleResult;
            }

            // Layer 2: BERT FastAPI
            IntentResult bertResult = bertClassifier.classify(userMessage);
            if (bertResult != null) {
                if (bertResult.confidence() >= acceptThreshold) {
                    log.debug("[Intent] Layer2-BERT 命中 intent={} conf={}",
                            bertResult.intent(), bertResult.confidence());
                    return bertResult;
                }
                if (bertResult.confidence() < fallbackThreshold) {
                    log.debug("[Intent] Layer2-BERT 置信度过低 conf={}，降级 UNKNOWN",
                            bertResult.confidence());
                    return IntentResult.UNKNOWN;
                }
                log.debug("[Intent] Layer2-BERT 置信度不足 conf={}，交 LLM 二次判断",
                        bertResult.confidence());
            } else {
                log.debug("[Intent] Layer2-BERT 不可用，直接走 Layer3-LLM");
            }

            // Layer 3: LLM 兜底（低频）
            log.debug("[Intent] Layer3-LLM 兜底");
            return llmClassifier.classify(userMessage);

        } catch (Exception e) {
            log.warn("[Intent] 三层分类全部失败，降级 UNKNOWN", e);
            return IntentResult.UNKNOWN;
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=HybridIntentServiceTest -q
```

预期：`BUILD SUCCESS`，10 tests passed

- [ ] **Step 6: 运行全部测试，确认原有测试无回归**

```bash
mvn test -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`，0 failures

- [ ] **Step 7: 验证 ChatAppService 零改动**

```bash
git diff HEAD -- ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java
```

预期：无任何输出（无 diff）

- [ ] **Step 8: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridIntentService.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/HybridIntentServiceTest.java
git commit -m "feat(intent): add HybridIntentService as @Primary orchestrating Rule+BERT+LLM three-layer pipeline"
```

---

## 验收核查清单

运行完所有 Task 后逐项确认：

- [ ] `mvn test -pl ai-conversation/conversation-service -q` → BUILD SUCCESS，0 failures
- [ ] `git diff HEAD -- */ChatAppService.java` → 无输出（零改动）
- [ ] 管理后台"模型配置"可新增 INTENT 角色配置（modelType = INTENT）
- [ ] 管理后台"意图配置"可为 `TRANSFER_REQUEST` 配置 keywords，服务重启后规则层生效
- [ ] BERT FastAPI 服务启动时：日志出现 `[Intent] Layer2-BERT 命中`
- [ ] BERT FastAPI 服务停止时：日志出现 `[Intent] BERT 服务异常，跳过 Layer 2`，对话正常进行
- [ ] INTENT 模型配置禁用时：日志出现 `[Intent] BERT 服务未配置，跳过 Layer 2`
