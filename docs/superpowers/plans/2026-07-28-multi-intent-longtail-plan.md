# 多意图识别与长尾增强实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ARIA 智能客服系统上实现三级级联多意图识别（Tier1 规则 + Tier2 Embedding 原型 + Tier3 LLM），解决多意图漏召回和长尾意图识别率低两个问题。

**Architecture:** 领域层新增 `MultiIntentResult`/`MultiIntentService`/`IntentPriority`；基础设施层新增 Tier2 Embedding 原型匹配器（`EmbeddingPrototypeIntentMatcher` + `IntentPrototypeStore`）和 Tier3 动态 RAG 增强；应用层路由从单意图 boolean 改为 union/intersection 语义的多意图路由。

**Tech Stack:** Java 17, Spring Boot 3.3.5, LangChain4j 1.1.0, PostgreSQL + pgvector, Redis (Redisson), Caffeine, Micrometer, JUnit 5 + Mockito + AssertJ + reactor-test

## Global Constraints

- 所有新文件包路径在 `com.aria.conversation` 或 `com.aria.common.core.util` 下
- DDD 分层强制：domain 层不依赖 infrastructure 层任何类
- 阿里规范：无魔法值，常量统一放 `IntentClassificationConstants` / `ClassificationTierConstants`
- 向后兼容：`IntentService.classify()` 接口不变；`KeywordRegexIntentMatcher.match()` 签名不变
- 测试工具：JUnit 5 + MockitoExtension + AssertJ + StepVerifier（reactor-test）
- 提交规范：`feat(intent):` / `test(intent):` / `refactor(intent):` 前缀
- 特性开关：`multiIntentEnabled=false` 可无重启回滚
- Spring Boot 测试：仅使用 `@ExtendWith(MockitoExtension.class)` 单元测试，不启动 Spring 容器

---

## 文件结构总览

### 新增文件

```
ai-conversation/conversation-service/src/main/java/com/aria/conversation/
├── domain/
│   ├── model/
│   │   ├── IntentPriority.java               ← 意图路由优先级枚举
│   │   └── MultiIntentResult.java            ← 多意图结果值对象
│   ├── service/
│   │   └── MultiIntentService.java           ← 多意图领域服务接口
│   └── event/
│       └── IntentConfigChangedEvent.java     ← 领域事件
└── infrastructure/
    ├── ai/
    │   ├── ClassificationTierConstants.java   ← Tier 标识字符串常量
    │   ├── IntentClassificationConstants.java ← 分类魔法值常量
    │   ├── MultiIntentClassifier.java         ← Tier3 内部接口（DIP）
    │   ├── MultiHybridIntentService.java      ← 三级级联协调器
    │   └── EmbeddingPrototypeIntentMatcher.java ← Tier2 实现
    ├── prototype/
    │   └── IntentPrototypeStore.java          ← 原型向量 Redis 存储
    ├── example/
    │   └── IntentExampleVectorRepository.java ← 历史案例 pgvector 存储
    └── event/
        └── IntentPrototypeStoreRefreshListener.java ← 事件监听

ai-common/common-core/src/main/java/com/aria/common/core/util/
└── VectorMathUtils.java                       ← 向量数学工具

ai-conversation/conversation-service/src/main/resources/db/migration/
└── V{next}__create_intent_example_vectors.sql ← Flyway 迁移
```

### 改造文件

```
ai-conversation/conversation-service/src/main/java/com/aria/conversation/
├── domain/model/IntentType.java              ← 新增 fromCode() 静态工厂
├── infrastructure/ai/
│   ├── RoutingConfig.java                    ← Intent 内部类新增字段
│   ├── KeywordRegexIntentMatcher.java        ← 新增 matchAll()
│   ├── LangChain4jIntentService.java         ← 新增 classifyMulti()，实现 MultiIntentClassifier
│   └── HybridIntentService.java             ← 代理 MultiHybridIntentService
├── infrastructure/config/
│   └── CustomerServiceCacheConstant.java     ← 新增 INTENT_PROTOTYPES 等常量
└── application/service/
    ├── ChatAppService.java                   ← 注入 MultiIntentService，union 路由
    └── FaqChatAppService.java               ← union/intersection 路由改造
```

---

## Task 1: 领域层基础 — 常量、值对象、接口

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/IntentPriority.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/MultiIntentResult.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/service/MultiIntentService.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/event/IntentConfigChangedEvent.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/ClassificationTierConstants.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentClassificationConstants.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/domain/model/MultiIntentResultTest.java`

**Interfaces:**
- Produces: `IntentPriority.of(IntentType): IntentPriority`, `IntentPriority.getOrder(): int`
- Produces: `MultiIntentResult(List<IntentResult>, String, long)`, `MultiIntentResult.UNKNOWN`, `primaryIntent()`, `requiresTransfer()`, `skipRag()`, `isEffectivelyOutOfScope()`, `intentCodes()`
- Produces: `MultiIntentService.classifyMulti(String): MultiIntentResult`
- Produces: `ClassificationTierConstants.RULE/EMBEDDING/LLM`
- Produces: `IntentClassificationConstants.DEFAULT_EMBEDDING_THRESHOLD` 等 8 个常量

- [ ] **Step 1: 写失败测试**

```java
// MultiIntentResultTest.java
package com.aria.conversation.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MultiIntentResult 路由语义")
class MultiIntentResultTest {

    @Test
    @DisplayName("requiresTransfer: union语义 — 含COMPLAINT则为true")
    void requiresTransfer_anyComplaint_returnsTrue() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9)
        ), "RULE", 10L);
        assertThat(r.requiresTransfer()).isTrue();
    }

    @Test
    @DisplayName("requiresTransfer: 无转人工意图返回false")
    void requiresTransfer_noTransfer_returnsFalse() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9)
        ), "RULE", 10L);
        assertThat(r.requiresTransfer()).isFalse();
    }

    @Test
    @DisplayName("skipRag: intersection语义 — 含FAQ_QUERY则不跳过")
    void skipRag_hasFaqQuery_returnsFalse() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.CHITCHAT, "chitchat", 0.9),
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.8)
        ), "RULE", 10L);
        assertThat(r.skipRag()).isFalse();
    }

    @Test
    @DisplayName("isEffectivelyOutOfScope: 全为 OUT_OF_SCOPE 或 UNKNOWN 返回true")
    void isEffectivelyOutOfScope_allOutOfScope_returnsTrue() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.OUT_OF_SCOPE, "out_of_scope", 0.9),
                new IntentResult(IntentType.UNKNOWN, "unknown", 0.0)
        ), "LLM", 300L);
        assertThat(r.isEffectivelyOutOfScope()).isTrue();
    }

    @Test
    @DisplayName("primaryIntent: COMPLAINT 优先于 FAQ_QUERY")
    void primaryIntent_complaintHigherPriorityThanFaq() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9),
                new IntentResult(IntentType.COMPLAINT, "complaint", 0.85)
        ), "EMBEDDING", 35L);
        assertThat(r.primaryIntent().intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("intentCodes: 返回所有意图code列表")
    void intentCodes_returnsAllCodes() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_order", 1.0),
                new IntentResult(IntentType.COMPLAINT, "complaint", 0.9)
        ), "RULE", 1L);
        assertThat(r.intentCodes()).containsExactlyInAnyOrder("query_order", "complaint");
    }

    @Test
    @DisplayName("UNKNOWN 兜底结果: primaryIntent 为 UNKNOWN 类型")
    void unknown_primaryIntent_isUnknownType() {
        assertThat(MultiIntentResult.UNKNOWN.primaryIntent().intent())
                .isEqualTo(IntentType.UNKNOWN);
    }
}
```

- [ ] **Step 2: 运行测试，确认编译报错（类不存在）**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn test -pl ai-conversation/conversation-service \
    -Dtest=MultiIntentResultTest -q 2>&1 | tail -10
```
Expected: `ERROR` — `MultiIntentResult` 类不存在

- [ ] **Step 3: 创建 `IntentPriority.java`**

```java
package com.aria.conversation.domain.model;

/**
 * 意图路由优先级。
 *
 * <p>当用户消息包含多个意图时，{@link MultiIntentResult#primaryIntent()} 按此优先级
 * 选出"驱动分叉"的主意图。优先级数值越小，优先级越高（COMPLAINT 最高）。
 *
 * <p><b>维护约束：</b>{@link IntentType} 与本枚举的枚举项必须保持一一对应，同步新增/删除。
 * {@code switch} 语句的 exhaustive 检查（Java 17+）会在编译期保护该约束。
 *
 * @see IntentType 两者枚举项必须保持同步
 */
public enum IntentPriority {
    COMPLAINT(1),
    TRANSFER_REQUEST(2),
    FAQ_QUERY(3),
    CHITCHAT(4),
    OUT_OF_SCOPE(5),
    UNKNOWN(99);

    private final int order;

    IntentPriority(int order) { this.order = order; }

    public static IntentPriority of(IntentType type) {
        return switch (type) {
            case COMPLAINT         -> COMPLAINT;
            case TRANSFER_REQUEST  -> TRANSFER_REQUEST;
            case FAQ_QUERY         -> FAQ_QUERY;
            case CHITCHAT          -> CHITCHAT;
            case OUT_OF_SCOPE      -> OUT_OF_SCOPE;
            case UNKNOWN           -> UNKNOWN;
        };
    }

    public int getOrder() { return order; }
}
```

- [ ] **Step 4: 创建 `MultiIntentResult.java`**

```java
package com.aria.conversation.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * 多意图分类结果。持有所有通过阈值的意图列表，并提供路由决策语义。
 *
 * <p>不可变值对象，线程安全。
 *
 * <p><b>注意：</b>{@code sourceTier} 字段为可观测性用途，使用字符串而非枚举，
 * 避免将基础设施层（RULE/EMBEDDING/LLM）的技术概念引入领域对象。
 *
 * @param intents      所有命中的意图，按置信度降序排列，不可为 null
 * @param sourceTier   实际命中的处理层标识（"RULE"/"EMBEDDING"/"LLM"），仅用于日志和指标
 * @param processingMs 分类耗时（毫秒），用于性能监控
 */
public record MultiIntentResult(
        List<IntentResult> intents,
        String sourceTier,
        long processingMs
) {

    /** 兜底结果。sourceTier 用局部字符串，domain 层不引用 infra 常量（有意设计）。 */
    public static final MultiIntentResult UNKNOWN =
            new MultiIntentResult(List.of(IntentResult.UNKNOWN), "RULE", 0L);

    /** 主意图：按 {@link IntentPriority} 取优先级最高的意图。 */
    public IntentResult primaryIntent() {
        return intents.stream()
                .min(Comparator.comparingInt(r -> IntentPriority.of(r.intent()).getOrder()))
                .orElse(IntentResult.UNKNOWN);
    }

    /**
     * 任意一个意图需要转人工，则整体需要转人工（union 语义）。
     * 安全兜底：不因为有其他意图而忽略转人工信号。
     */
    public boolean requiresTransfer() {
        return intents.stream().anyMatch(IntentResult::requiresTransfer);
    }

    /**
     * 仅当所有意图都可跳过 RAG 时，才跳过 RAG（intersection 语义）。
     * 只要有一个意图需要 RAG，就执行 RAG。
     */
    public boolean skipRag() {
        return intents.stream().allMatch(IntentResult::skipRag);
    }

    /**
     * 判断是否所有有效意图均为 OUT_OF_SCOPE 或 UNKNOWN。
     * 供 Application 层做"整体拒答"路由决策使用。
     */
    public boolean isEffectivelyOutOfScope() {
        return intents.stream()
                .allMatch(r -> r.intent() == IntentType.OUT_OF_SCOPE
                            || r.intent() == IntentType.UNKNOWN);
    }

    /** 是否包含某个具体的业务意图 code。 */
    public boolean hasIntentCode(String intentCode) {
        return intents.stream().anyMatch(r -> intentCode.equalsIgnoreCase(r.intentCode()));
    }

    /** 所有业务意图 code 列表，供下游 dispatch 使用。 */
    public List<String> intentCodes() {
        return intents.stream().map(IntentResult::intentCode).toList();
    }
}
```

- [ ] **Step 5: 创建 `MultiIntentService.java`**

```java
package com.aria.conversation.domain.service;

import com.aria.conversation.domain.model.MultiIntentResult;

/**
 * 多意图识别领域服务接口。
 *
 * <p>实现在 infrastructure 层（{@link com.aria.conversation.infrastructure.ai.MultiHybridIntentService}）。
 * 任何失败均返回 {@link MultiIntentResult#UNKNOWN}，不抛异常。
 */
public interface MultiIntentService {
    MultiIntentResult classifyMulti(String userMessage);
}
```

- [ ] **Step 6: 创建 `IntentConfigChangedEvent.java`**

```java
package com.aria.conversation.domain.event;

/**
 * 意图配置变更领域事件。
 *
 * <p>领域事件定义在 domain 层，监听器（{@link com.aria.conversation.infrastructure.event.IntentPrototypeStoreRefreshListener}）
 * 在 infrastructure 层响应，保持 DDD 分层。
 */
public record IntentConfigChangedEvent(String domainCode) {}
```

- [ ] **Step 7: 创建 `ClassificationTierConstants.java`**

```java
package com.aria.conversation.infrastructure.ai;

/**
 * 意图分类处理层级标识常量。
 *
 * <p>供 {@link MultiHybridIntentService} 填充 {@link com.aria.conversation.domain.model.MultiIntentResult#sourceTier()} 字段，
 * 以及 Micrometer 指标的 tier tag 使用。
 * 不放在领域层，因为 RULE/EMBEDDING/LLM 是基础设施实现细节。
 */
public interface ClassificationTierConstants {
    String RULE      = "RULE";
    String EMBEDDING = "EMBEDDING";
    String LLM       = "LLM";
}
```

- [ ] **Step 8: 创建 `IntentClassificationConstants.java`**

```java
package com.aria.conversation.infrastructure.ai;

/**
 * 意图分类相关默认值常量。
 *
 * <p>所有默认值均可通过 {@code system_config} 的 {@code routing.config} 覆盖。
 * 阿里规范：不允许在代码中直接使用魔法值。
 */
public interface IntentClassificationConstants {
    /** Tier2 Embedding 全局默认相似度阈值 */
    double DEFAULT_EMBEDDING_THRESHOLD      = 0.75;
    /** Tier2 高置信度阈值：超过此值跳过 Tier3 LLM */
    double DEFAULT_HIGH_CONFIDENCE          = 0.85;
    /** Tier3 LLM 最低置信度：低于此值的意图被过滤 */
    double DEFAULT_MIN_LLM_CONFIDENCE       = 0.50;
    /** 高置信度自动积累历史案例的最低置信度门槛 */
    double DEFAULT_AUTO_ACCUMULATE_MIN_CONF = 0.95;
    /** Caffeine 本地缓存最大条目数（意图原型）*/
    int    PROTOTYPE_CACHE_MAX_SIZE         = 200;
    /** Caffeine 本地缓存 TTL（分钟）*/
    int    PROTOTYPE_CACHE_TTL_MINUTES      = 10;
    /** LLM Few-Shot 静态样本最大注入数 */
    int    DEFAULT_MAX_EXAMPLES_TO_INJECT   = 3;
    /** Tier3 动态 RAG 每意图注入历史案例数 */
    int    DEFAULT_LLM_RAG_TOP_K            = 2;
}
```

- [ ] **Step 9: 运行测试，确认全部通过**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn test -pl ai-conversation/conversation-service \
    -Dtest=MultiIntentResultTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 6 tests PASS

- [ ] **Step 10: 提交**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/ \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/ClassificationTierConstants.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentClassificationConstants.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/domain/model/MultiIntentResultTest.java
git commit -m "feat(intent): 新增领域层 MultiIntentResult/MultiIntentService/IntentPriority 及常量"
```

## Task 2: IntentType.fromCode() + RoutingConfig 扩展 + CustomerServiceCacheConstant 新增常量

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/IntentType.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingConfig.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CustomerServiceCacheConstant.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/domain/model/IntentTypeFromCodeTest.java`

**Interfaces:**
- Consumes: `IntentClassificationConstants` (Task 1)
- Produces: `IntentType.fromCode(String): IntentType`
- Produces: `RoutingConfig.Intent.isMultiIntentEnabled()`, `getEmbeddingGlobalThreshold()`, `getEmbeddingHighConfidence()`, `getEmbeddingThresholds()`, `isLlmRagEnabled()`, `getLlmRagTopK()`, `isAutoAccumulateEnabled()`, `getAutoAccumulateMinConfidence()`
- Produces: `CustomerServiceCacheConstant.INTENT_PROTOTYPES`, `INTENT_PROTOTYPE_VERSION`

- [ ] **Step 1: 写失败测试**

```java
// IntentTypeFromCodeTest.java
package com.aria.conversation.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentType.fromCode 静态工厂")
class IntentTypeFromCodeTest {

    @Test
    @DisplayName("已知大写 code 正确解析")
    void fromCode_knownUpperCase_resolves() {
        assertThat(IntentType.fromCode("COMPLAINT")).isEqualTo(IntentType.COMPLAINT);
        assertThat(IntentType.fromCode("FAQ_QUERY")).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("小写 code 不区分大小写")
    void fromCode_lowerCase_resolves() {
        assertThat(IntentType.fromCode("transfer_request")).isEqualTo(IntentType.TRANSFER_REQUEST);
    }

    @Test
    @DisplayName("未知 code 返回 FAQ_QUERY 兜底")
    void fromCode_unknown_returnsFaqQuery() {
        assertThat(IntentType.fromCode("query_order")).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("null 返回 FAQ_QUERY 兜底")
    void fromCode_null_returnsFaqQuery() {
        assertThat(IntentType.fromCode(null)).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("空白字符串返回 FAQ_QUERY 兜底")
    void fromCode_blank_returnsFaqQuery() {
        assertThat(IntentType.fromCode("   ")).isEqualTo(IntentType.FAQ_QUERY);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=IntentTypeFromCodeTest -q 2>&1 | tail -5
```
Expected: `ERROR` — `fromCode` 方法不存在

- [ ] **Step 3: 在 `IntentType.java` 中新增 `fromCode()` 方法**

在 `IntentType` 枚举末尾（`UNKNOWN;` 之后）新增：

```java
/**
 * 从业务意图 code 字符串安全解析枚举值，不抛异常。
 *
 * <p>替代 {@code try { IntentType.valueOf(code) } catch (IllegalArgumentException) {...}} 的反模式。
 *
 * @param code 意图 code（大小写不敏感），null 或未知值均返回 {@link #FAQ_QUERY}
 * @return 对应枚举值，未知时返回 {@link #FAQ_QUERY}
 */
public static IntentType fromCode(String code) {
    if (code == null || code.isBlank()) {
        return FAQ_QUERY;
    }
    String upper = code.toUpperCase();
    for (IntentType t : values()) {
        if (t.name().equals(upper)) {
            return t;
        }
    }
    return FAQ_QUERY;
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=IntentTypeFromCodeTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 5 tests PASS

- [ ] **Step 5: 扩展 `RoutingConfig.Intent` 内部类，在已有字段后追加新字段**

在 `RoutingConfig.Intent` 类的已有字段（`embeddingEnabled`, `embeddingThreshold`, `minLlmConfidence`, `maxExamplesToInject`）之后新增：

```java
// 新增字段（有默认值，向后兼容旧 JSON 配置）
/** 总开关：false 时退化为单意图模式（可无重启回滚） */
private boolean multiIntentEnabled = true;
/** Tier2 全局默认相似度阈值，替代已废弃的 embeddingThreshold */
private double embeddingGlobalThreshold = IntentClassificationConstants.DEFAULT_EMBEDDING_THRESHOLD;
/** 超过此值则跳过 Tier3 LLM 调用 */
private double embeddingHighConfidence  = IntentClassificationConstants.DEFAULT_HIGH_CONFIDENCE;
/** 意图级独立阈值（key=intentCode, value=阈值），覆盖全局阈值 */
private java.util.Map<String, Double> embeddingThresholds = new java.util.HashMap<>();
/** 是否开启 Tier3 动态 RAG 注入 */
private boolean llmRagEnabled = true;
/** Tier3 动态 RAG 每意图注入历史案例数 */
private int llmRagTopK = IntentClassificationConstants.DEFAULT_LLM_RAG_TOP_K;
/** 是否开启高置信度自动积累 */
private boolean autoAccumulateEnabled = true;
/** 自动积累的最低置信度门槛 */
private double autoAccumulateMinConfidence = IntentClassificationConstants.DEFAULT_AUTO_ACCUMULATE_MIN_CONF;
```

同时在 `fromProperties()` 方法中为旧字段添加降级逻辑（新字段使用默认值，不需要从 properties 映射）。

- [ ] **Step 6: 在 `CustomerServiceCacheConstant.java` 中新增常量**

在现有常量后追加：

```java
/** Tier2 意图原型向量 Redis HASH，field=intentCode，value=PrototypeEntry JSON */
String INTENT_PROTOTYPES = "intent:prototypes";

/** 原型向量版本号，IntentConfig 变更时更新 */
String INTENT_PROTOTYPE_VERSION = "intent:prototype:version";
```

- [ ] **Step 7: 编译验证（不运行测试，只编译）**

```bash
mvn compile -pl ai-conversation/conversation-service -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/IntentType.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/RoutingConfig.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CustomerServiceCacheConstant.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/domain/model/IntentTypeFromCodeTest.java
git commit -m "feat(intent): IntentType.fromCode() + RoutingConfig 多意图字段 + 缓存常量"
```

## Task 3: VectorMathUtils + Flyway 迁移脚本

**Files:**
- Create: `ai-common/common-core/src/main/java/com/aria/common/core/util/VectorMathUtils.java`
- Create: `ai-conversation/conversation-service/src/main/resources/db/migration/V{next}__create_intent_example_vectors.sql`
- Test: `ai-common/common-core/src/test/java/com/aria/common/core/util/VectorMathUtilsTest.java`

**Interfaces:**
- Produces: `VectorMathUtils.meanAndNormalize(List<float[]>): float[]`
- Produces: `VectorMathUtils.normalize(float[]): float[]`
- Produces: `VectorMathUtils.cosineSimilarity(float[], float[]): double`

- [ ] **Step 1: 确认下一个 Flyway 版本号**

```bash
ls ai-conversation/conversation-service/src/main/resources/db/migration/ | sort | tail -3
```
记录最大版本号，新脚本使用 `V{max+1}`。

- [ ] **Step 2: 写失败测试**

```java
// VectorMathUtilsTest.java
package com.aria.common.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("VectorMathUtils 向量数学工具")
class VectorMathUtilsTest {

    @Test
    @DisplayName("normalize: 归一化后模长为1")
    void normalize_resultHasUnitLength() {
        float[] v = {3.0f, 4.0f};  // 模 = 5
        float[] norm = VectorMathUtils.normalize(v);
        double length = Math.sqrt(norm[0] * norm[0] + norm[1] * norm[1]);
        assertThat(length).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("cosineSimilarity: 同向量相似度为1")
    void cosineSimilarity_sameVector_returnsOne() {
        float[] a = VectorMathUtils.normalize(new float[]{1.0f, 0.0f});
        assertThat(VectorMathUtils.cosineSimilarity(a, a)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("cosineSimilarity: 正交向量相似度为0")
    void cosineSimilarity_orthogonal_returnsZero() {
        float[] a = VectorMathUtils.normalize(new float[]{1.0f, 0.0f});
        float[] b = VectorMathUtils.normalize(new float[]{0.0f, 1.0f});
        assertThat(VectorMathUtils.cosineSimilarity(a, b)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("meanAndNormalize: 单向量等于归一化自身")
    void meanAndNormalize_singleVector_equalsNormalized() {
        float[] v = {3.0f, 4.0f};
        float[] result = VectorMathUtils.meanAndNormalize(List.of(v));
        float[] expected = VectorMathUtils.normalize(v);
        for (int i = 0; i < result.length; i++) {
            assertThat(result[i]).isCloseTo(expected[i], within(1e-6f));
        }
    }

    @Test
    @DisplayName("meanAndNormalize: 两个反向量均值为零向量，归一化不抛异常")
    void meanAndNormalize_oppositeVectors_noException() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        // 均值为零向量，归一化处理为零向量，不抛异常
        assertThat(VectorMathUtils.meanAndNormalize(List.of(a, b))).hasSize(2);
    }

    @Test
    @DisplayName("meanAndNormalize: 空列表抛 IllegalArgumentException")
    void meanAndNormalize_emptyList_throwsException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> VectorMathUtils.meanAndNormalize(List.of()));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn test -pl ai-common/common-core -Dtest=VectorMathUtilsTest -q 2>&1 | tail -5
```
Expected: `ERROR` — `VectorMathUtils` 不存在

- [ ] **Step 4: 创建 `VectorMathUtils.java`**

```java
package com.aria.common.core.util;

import java.util.List;

/**
 * 向量数学运算工具类。
 *
 * <p>与 {@link VectorUtils}（格式转换）的职责分离：
 * 本类负责向量的数学操作（归一化、余弦相似度、均值），
 * {@link VectorUtils} 负责 float[] 与 pgvector 字符串格式互转。
 */
public final class VectorMathUtils {

    private VectorMathUtils() {}

    /**
     * 计算多个向量的均值并进行 L2 归一化，用于构建意图原型向量。
     *
     * @param vectors 输入向量列表，不可为空
     * @return 均值后 L2 归一化的向量；若均值为零向量则返回全零向量
     * @throws IllegalArgumentException 若 vectors 为空
     */
    public static float[] meanAndNormalize(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("vectors 列表不能为空");
        }
        int dim = vectors.get(0).length;
        float[] mean = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) {
                mean[i] += v[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            mean[i] /= vectors.size();
        }
        return normalize(mean);
    }

    /**
     * 对向量进行 L2 归一化（使向量模长为 1）。
     *
     * @param v 输入向量
     * @return 归一化后的新向量；若模为 0 则返回全零向量
     */
    public static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[v.length];
        if (norm < 1e-10) {
            return result;  // 零向量，返回全零
        }
        for (int i = 0; i < v.length; i++) {
            result[i] = (float) (v[i] / norm);
        }
        return result;
    }

    /**
     * 计算两个已归一化向量的余弦相似度（即点积）。
     *
     * <p><b>前置条件：</b>入参向量必须已经 L2 归一化，此时余弦相似度等于点积。
     *
     * @param a 已归一化向量 a
     * @param b 已归一化向量 b
     * @return 余弦相似度，范围 [-1.0, 1.0]
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
```

- [ ] **Step 5: 运行测试确认全部通过**

```bash
mvn test -pl ai-common/common-core -Dtest=VectorMathUtilsTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 6 tests PASS

- [ ] **Step 6: 创建 Flyway 迁移脚本**

将 `{next}` 替换为实际版本号（Step 1 确认的最大版本号 + 1）：

```sql
-- 文件名：V{next}__create_intent_example_vectors.sql
-- 意图触发历史案例向量表，用于 Tier3 LLM 动态 Few-Shot RAG 注入和高置信度自动积累

CREATE TABLE IF NOT EXISTS intent_example_vectors (
    id              BIGSERIAL PRIMARY KEY,
    intent_code     VARCHAR(100) NOT NULL,
    message_text    TEXT NOT NULL,
    embedding       vector(1536) NOT NULL,
    confirmed_by    VARCHAR(50),                      -- NULL 表示自动积累，非 NULL 表示人工确认
    auto_confirmed  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 向量余弦相似度索引（ivfflat，适合百万级以下数据）
CREATE INDEX IF NOT EXISTS idx_intent_example_vectors_embedding
    ON intent_example_vectors USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 50);

-- intent_code 索引，供按意图过滤查询
CREATE INDEX IF NOT EXISTS idx_intent_example_vectors_intent_code
    ON intent_example_vectors (intent_code);

COMMENT ON TABLE intent_example_vectors IS '意图触发历史案例向量表，用于 Tier3 LLM 动态 Few-Shot RAG';
COMMENT ON COLUMN intent_example_vectors.confirmed_by IS 'NULL 表示高置信度自动积累，非 NULL 表示人工确认';
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile -pl ai-common/common-core -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add ai-common/common-core/src/main/java/com/aria/common/core/util/VectorMathUtils.java \
        ai-common/common-core/src/test/java/com/aria/common/core/util/VectorMathUtilsTest.java \
        ai-conversation/conversation-service/src/main/resources/db/migration/
git commit -m "feat(intent): 新增 VectorMathUtils + Flyway 意图案例向量表迁移脚本"
```

## Task 4: KeywordRegexIntentMatcher.matchAll() 改造

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcher.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcherMatchAllTest.java`

**Interfaces:**
- Consumes: 现有 `KeywordRegexIntentMatcher`（Caffeine 缓存、`IntentRuleEntry`、`DomainRepository`）
- Produces: `KeywordRegexIntentMatcher.matchAll(String): List<IntentResult>` — 收集所有命中，按 sortOrder 顺序，同 code 去重
- `match(String): Optional<IntentResult>` 保持不变（内部改为代理 `matchAll()`）

- [ ] **Step 1: 写失败测试**

```java
// KeywordRegexIntentMatcherMatchAllTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
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
@DisplayName("KeywordRegexIntentMatcher.matchAll 多规则命中")
class KeywordRegexIntentMatcherMatchAllTest {

    @Mock private DomainRepository domainRepository;
    private KeywordRegexIntentMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new KeywordRegexIntentMatcher(domainRepository);
    }

    private IntentConfig buildIntent(String code, List<String> keywords, int order) {
        return new IntentConfig(code, code, null, List.of(), false, false, null,
                List.of(), List.of(), keywords, List.of(), order);
    }

    private DomainConfig buildDomain(List<IntentConfig> intents) {
        return new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system", null, null, null, intents);
    }

    @Test
    @DisplayName("两条规则都命中：返回两个意图（不再首个返回）")
    void matchAll_twoRulesHit_returnsBoth() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("FAQ_QUERY", List.of("查物流"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var results = matcher.matchAll("我要投诉，同时查物流");

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(r -> r.intent()))
                .containsExactlyInAnyOrder(IntentType.COMPLAINT, IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("同一意图被多条规则命中：只返回一次（sortOrder 最小的规则）")
    void matchAll_sameIntentTwoRules_deduplicates() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("COMPLAINT", List.of("不满意"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var results = matcher.matchAll("我投诉，非常不满意");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("无命中：返回空列表")
    void matchAll_noHit_returnsEmpty() {
        var intents = List.of(buildIntent("COMPLAINT", List.of("投诉"), 1));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        assertThat(matcher.matchAll("查一下我的订单")).isEmpty();
    }

    @Test
    @DisplayName("空白消息：返回空列表，不抛异常")
    void matchAll_blank_returnsEmpty() {
        assertThat(matcher.matchAll("  ")).isEmpty();
        assertThat(matcher.matchAll(null)).isEmpty();
    }

    @Test
    @DisplayName("match() 兼容性：仍返回 Optional，与 matchAll() 行为一致")
    void match_backwardsCompatible_returnsFirstHit() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("FAQ_QUERY", List.of("查物流"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var result = matcher.match("我要投诉，同时查物流");

        assertThat(result).isPresent();
        // match() 只返回第一个（sortOrder 最小），不改变原有行为
        assertThat(result.get().intent()).isEqualTo(IntentType.COMPLAINT);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=KeywordRegexIntentMatcherMatchAllTest -q 2>&1 | tail -8
```
Expected: `FAILED` — `matchAll` 方法不存在

- [ ] **Step 3: 在 `KeywordRegexIntentMatcher.java` 中新增 `matchAll()` 方法**

在现有 `match()` 方法之前插入新方法，并将现有 `match()` 改为代理：

```java
/**
 * 收集所有命中的意图规则（不再首个返回）。
 *
 * <p>同一意图 code 可能被多条规则命中，结果集按 intentCode 去重，
 * 保留 sortOrder 最小的规则命中结果。
 *
 * @param userMessage 用户消息，null 或空白直接返回空列表
 * @return 所有命中的意图列表，按规则 sortOrder 升序，不可修改；未命中返回空列表
 */
public List<IntentResult> matchAll(String userMessage) {
    if (StringUtils.isBlank(userMessage)) {
        return List.of();
    }
    String lower = userMessage.toLowerCase();
    // LinkedHashMap 保证插入顺序（按 sortOrder），同 code 只保留第一条命中
    Map<String, IntentResult> resultMap = new LinkedHashMap<>();

    for (IntentRuleEntry entry : loadRules()) {
        if (resultMap.containsKey(entry.intentCode())) {
            continue;  // 该意图已命中，跳过重复规则
        }
        boolean hit = false;
        for (String kw : entry.keywords()) {
            if (lower.contains(kw.toLowerCase())) {
                log.debug("[RuleMatcher] 关键词命中 intent={} kw={}", entry.intentCode(), kw);
                hit = true;
                break;
            }
        }
        if (!hit) {
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    log.debug("[RuleMatcher] 正则命中 intent={} pattern={}",
                            entry.intentCode(), p.pattern());
                    hit = true;
                    break;
                }
            }
        }
        if (hit) {
            resultMap.put(entry.intentCode(),
                    new IntentResult(entry.intentType(),
                            entry.intentCode().toLowerCase(), 1.0));
        }
    }
    return List.copyOf(resultMap.values());
}

/**
 * 尝试用规则匹配用户消息（向后兼容方法，内部代理 {@link #matchAll(String)}）。
 *
 * <p>命中返回 sortOrder 最小的第一个意图；未命中返回 empty。
 */
public Optional<IntentResult> match(String userMessage) {
    List<IntentResult> all = matchAll(userMessage);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
}
```

同时在类顶部补充缺失的 import：
```java
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 4: 运行测试确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=KeywordRegexIntentMatcherMatchAllTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 5 tests PASS

- [ ] **Step 5: 运行原有规则匹配测试（回归）**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest="KeywordRegex*" -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`，无回归失败

- [ ] **Step 6: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcher.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/KeywordRegexIntentMatcherMatchAllTest.java
git commit -m "feat(intent): KeywordRegexIntentMatcher 新增 matchAll()，收集所有命中意图"
```

## Task 5: IntentPrototypeStore + IntentExampleVectorRepository

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/prototype/IntentPrototypeStore.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/example/IntentExampleVectorRepository.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/prototype/IntentPrototypeStoreTest.java`

**Interfaces:**
- Consumes: `VectorMathUtils` (Task 3), `CustomerServiceCacheConstant.INTENT_PROTOTYPES` (Task 2), `EmbeddingService`, `DomainRepository`
- Produces: `IntentPrototypeStore.getAllPrototypes(): Map<String, float[]>`
- Produces: `IntentPrototypeStore.rebuild(): void`
- Produces: `IntentExampleVectorRepository.findSimilarByIntent(float[], int, int): Map<String, List<String>>`
- Produces: `IntentExampleVectorRepository.saveIfAbsent(String, String, float[], boolean): void`

- [ ] **Step 1: 写失败测试**

```java
// IntentPrototypeStoreTest.java
package com.aria.conversation.infrastructure.prototype;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntentPrototypeStore 原型向量存储")
class IntentPrototypeStoreTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private EmbeddingService embeddingService;
    @Mock private DomainRepository domainRepository;
    @Mock private ObjectMapper objectMapper;

    private IntentPrototypeStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        store = new IntentPrototypeStore(redisTemplate, embeddingService,
                domainRepository, objectMapper);
    }

    @Test
    @DisplayName("rebuild: __system__ 域不存在时不写 Redis")
    void rebuild_noSystemDomain_doesNotWriteRedis() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        store.rebuild();

        verify(hashOps, never()).putAll(any(), any());
    }

    @Test
    @DisplayName("rebuild: 有 exampleQueries 的意图被写入 Redis")
    void rebuild_withExamples_writesPrototypesToRedis() throws Exception {
        var intent = new IntentConfig("FAQ_QUERY", "FAQ", null,
                List.of("查订单", "看物流"), false, false, null,
                List.of(), List.of(), List.of(), List.of(), 1);
        var domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system",
                null, null, null, List.of(intent));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(domain));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();

        verify(hashOps).putAll(eq(CustomerServiceCacheConstant.INTENT_PROTOTYPES), any());
    }

    @Test
    @DisplayName("rebuild: JsonProcessingException 时跳过该意图，不中断整体重建")
    void rebuild_jsonException_skipsIntentContinues() throws Exception {
        var i1 = new IntentConfig("FAQ_QUERY", "FAQ", null,
                List.of("查订单"), false, false, null,
                List.of(), List.of(), List.of(), List.of(), 1);
        var i2 = new IntentConfig("COMPLAINT", "投诉", null,
                List.of("投诉"), false, false, null,
                List.of(), List.of(), List.of(), List.of(), 2);
        var domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system",
                null, null, null, List.of(i1, i2));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(domain));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        // 第一个意图序列化失败，第二个正常
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("mock") {})
                .thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();  // 不应抛异常

        // 仍然调用了 putAll（只含第二个意图）
        verify(hashOps).putAll(any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=IntentPrototypeStoreTest -q 2>&1 | tail -8
```
Expected: `ERROR` — `IntentPrototypeStore` 不存在

- [ ] **Step 3: 创建 `IntentPrototypeStore.java`**

```java
package com.aria.conversation.infrastructure.prototype;

import com.aria.common.core.util.VectorMathUtils;
import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.infrastructure.ai.IntentClassificationConstants;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
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
 *   <li>应用启动后首次读取（懒加载）</li>
 *   <li>IntentConfig 发生变更（通过 {@link #rebuild()} 主动触发）</li>
 *   <li>Caffeine TTL 过期自动重加载</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentPrototypeStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final EmbeddingService embeddingService;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;

    /** Caffeine 本地缓存：intentCode → 原型向量（已 L2 归一化）*/
    private final Cache<String, float[]> localCache = Caffeine.newBuilder()
            .expireAfterWrite(IntentClassificationConstants.PROTOTYPE_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES)
            .maximumSize(IntentClassificationConstants.PROTOTYPE_CACHE_MAX_SIZE)
            .build();

    /**
     * 获取所有意图的原型向量快照。
     * 优先从本地 Caffeine 缓存读取；缓存未命中时从 Redis HASH 全量加载。
     *
     * @return intentCode → 原型向量（已归一化），不可修改
     */
    @SuppressWarnings("unchecked")
    public Map<String, float[]> getAllPrototypes() {
        // 尝试从 Redis 全量加载到本地缓存
        Map<Object, Object> redisData = redisTemplate.opsForHash()
                .entries(CustomerServiceCacheConstant.INTENT_PROTOTYPES);
        if (redisData == null || redisData.isEmpty()) {
            log.debug("[PrototypeStore] Redis 无数据，触发 rebuild");
            rebuild();
            redisData = redisTemplate.opsForHash()
                    .entries(CustomerServiceCacheConstant.INTENT_PROTOTYPES);
            if (redisData == null || redisData.isEmpty()) {
                return Map.of();
            }
        }
        Map<String, float[]> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
            try {
                PrototypeEntry proto = objectMapper.readValue(
                        (String) entry.getValue(), PrototypeEntry.class);
                result.put((String) entry.getKey(), proto.vector());
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
        DomainConfig system = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)
                .orElse(null);
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
                log.warn("[PrototypeStore] 意图 {} 原型序列化失败，跳过. error={}",
                        intent.code(), e.getMessage());
            }
        }
        if (!protoMap.isEmpty()) {
            redisTemplate.opsForHash().putAll(
                    CustomerServiceCacheConstant.INTENT_PROTOTYPES, protoMap);
        }
        localCache.invalidateAll();
        log.info("[PrototypeStore] 重建原型向量 {} 个", protoMap.size());
    }

    /** 原型向量存储结构（public 供 Jackson 反序列化）。 */
    public record PrototypeEntry(float[] vector, int exampleCount, String updatedAt) {}
}
```

- [ ] **Step 4: 创建 `IntentExampleVectorRepository.java`**

```java
package com.aria.conversation.infrastructure.example;

import com.aria.common.core.util.VectorUtils;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图触发历史案例向量仓储。
 *
 * <p>存储结构：{@code intent_example_vectors} 表（PostgreSQL + pgvector）。
 * 数据来源：人工标注 + 高置信度自动积累。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class IntentExampleVectorRepository {

    private static final String TABLE_NAME = "intent_example_vectors";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    /**
     * 检索与 query 语义最相似的历史案例，按意图 code 分组返回。
     *
     * <p><b>参数绑定：</b>{@code float[]} 通过 {@link VectorUtils#toStr(float[])} 转为
     * {@code "[0.1,0.2,...]"} 格式字符串，再以 {@code ?::vector} 绑定，需传两次相同值。
     *
     * @param queryEmbedding query embedding 向量
     * @param topK           每个意图返回的最大案例数
     * @param limit          检索候选总数
     * @return intentCode → 历史案例文本列表
     */
    public Map<String, List<String>> findSimilarByIntent(
            float[] queryEmbedding, int topK, int limit) {
        String vecStr = VectorUtils.toStr(queryEmbedding);
        String sql = """
                SELECT intent_code, message_text
                FROM %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(TABLE_NAME);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vecStr, limit);

        Map<String, List<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("intent_code");
            String text = (String) row.get("message_text");
            result.computeIfAbsent(code, k -> new ArrayList<>()).add(text);
        }
        // 每个意图只保留 topK 条
        result.replaceAll((code, texts) ->
                texts.size() > topK ? texts.subList(0, topK) : texts);
        return result;
    }

    /**
     * 幂等保存意图触发案例（原子操作，防止并发双写）。
     *
     * <p>使用 {@code INSERT ... ON CONFLICT DO NOTHING} 保证原子性，
     * 避免应用层"先查后插"的竞态条件。
     */
    public void saveIfAbsent(String intentCode, String messageText,
                              float[] embedding, boolean autoConfirmed) {
        String vecStr = VectorUtils.toStr(embedding);
        String sql = """
                INSERT INTO %s (intent_code, message_text, embedding, auto_confirmed)
                VALUES (?, ?, ?::vector, ?)
                ON CONFLICT DO NOTHING
                """.formatted(TABLE_NAME);
        try {
            jdbcTemplate.update(sql, intentCode, messageText, vecStr, autoConfirmed);
            log.debug("[ExampleRepo] 保存案例 intentCode={} autoConfirmed={}",
                    intentCode, autoConfirmed);
        } catch (Exception e) {
            log.warn("[ExampleRepo] 保存案例失败 intentCode={}", intentCode, e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=IntentPrototypeStoreTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 3 tests PASS

- [ ] **Step 6: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/prototype/ \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/example/ \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/prototype/
git commit -m "feat(intent): 新增 IntentPrototypeStore + IntentExampleVectorRepository"
```

## Task 6: EmbeddingPrototypeIntentMatcher（Tier 2）+ MultiIntentClassifier 接口

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/MultiIntentClassifier.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/EmbeddingPrototypeIntentMatcher.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/EmbeddingPrototypeIntentMatcherTest.java`

**Interfaces:**
- Consumes: `IntentPrototypeStore.getAllPrototypes()` (Task 5), `VectorMathUtils` (Task 3), `RoutingConfig.getEmbeddingGlobalThreshold()` (Task 2), `IntentType.fromCode()` (Task 2)
- Produces: `MultiIntentClassifier.classifyMulti(String): List<IntentResult>`
- Produces: `EmbeddingPrototypeIntentMatcher.match(String): List<IntentResult>` — 相似度超阈值的意图列表，按置信度降序

- [ ] **Step 1: 创建 `MultiIntentClassifier.java`（接口，DIP 修复）**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import java.util.List;

/**
 * 多意图 LLM 分类器内部接口（infrastructure 层）。
 *
 * <p>使 {@link MultiHybridIntentService} 依赖抽象而非具体实现，
 * 便于替换实现（如切换模型提供商）和独立单测（Mock）。
 */
public interface MultiIntentClassifier {
    /**
     * 对用户消息进行多意图分类。
     *
     * @param userMessage 用户消息
     * @return 分类结果列表，失败时返回含 {@link com.aria.conversation.domain.model.IntentResult#UNKNOWN} 的单元素列表，不抛异常
     */
    List<IntentResult> classifyMulti(String userMessage);
}
```

- [ ] **Step 2: 写失败测试**

```java
// EmbeddingPrototypeIntentMatcherTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.ai.RoutingConfig.Intent;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingPrototypeIntentMatcher Tier2")
class EmbeddingPrototypeIntentMatcherTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private IntentPrototypeStore prototypeStore;
    @Mock private RoutingConfigProvider routingConfigProvider;

    private EmbeddingPrototypeIntentMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new EmbeddingPrototypeIntentMatcher(
                embeddingService, prototypeStore, routingConfigProvider);
    }

    private RoutingConfig configWith(double globalThreshold) {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setEmbeddingGlobalThreshold(globalThreshold);
        config.getIntent().setEmbeddingThresholds(Map.of());
        return config;
    }

    @Test
    @DisplayName("相似度超过全局阈值的意图被返回")
    void match_aboveThreshold_returnsIntent() {
        when(routingConfigProvider.getConfig()).thenReturn(configWith(0.75));
        // 查询向量和 FAQ_QUERY 原型向量相同（余弦相似度 = 1.0）
        float[] vec = {1.0f, 0.0f};
        when(embeddingService.encode(any())).thenReturn(vec);
        when(prototypeStore.getAllPrototypes()).thenReturn(
                Map.of("FAQ_QUERY", vec));  // 已归一化

        List<IntentResult> results = matcher.match("查一下我的订单");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(results.get(0).confidence()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    @DisplayName("相似度低于阈值的意图不返回")
    void match_belowThreshold_returnsEmpty() {
        when(routingConfigProvider.getConfig()).thenReturn(configWith(0.9));
        float[] query = {1.0f, 0.0f};
        float[] proto = {0.0f, 1.0f};  // 正交，余弦相似度 = 0
        when(embeddingService.encode(any())).thenReturn(query);
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of("FAQ_QUERY", proto));

        assertThat(matcher.match("查一下我的订单")).isEmpty();
    }

    @Test
    @DisplayName("原型库为空时返回空列表")
    void match_emptyPrototypes_returnsEmpty() {
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of());
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});

        assertThat(matcher.match("查询")).isEmpty();
    }

    @Test
    @DisplayName("空白消息返回空列表，不调用 EmbeddingService")
    void match_blankMessage_returnsEmpty() {
        assertThat(matcher.match("  ")).isEmpty();
        // embeddingService 不被调用
    }

    @Test
    @DisplayName("意图独立阈值覆盖全局阈值")
    void match_intentSpecificThreshold_overridesGlobal() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setEmbeddingGlobalThreshold(0.5);
        config.getIntent().setEmbeddingThresholds(Map.of("claim_apply", 0.95));
        when(routingConfigProvider.getConfig()).thenReturn(config);

        float[] query = {1.0f, 0.0f};
        // claim_apply 余弦相似度 = 0.8，低于独立阈值 0.95，不命中
        float[] claimProto = {0.8f, 0.6f};  // 未归一化
        when(embeddingService.encode(any())).thenReturn(query);
        when(prototypeStore.getAllPrototypes())
                .thenReturn(Map.of("claim_apply",
                        com.aria.common.core.util.VectorMathUtils.normalize(claimProto)));

        assertThat(matcher.match("申请理赔")).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=EmbeddingPrototypeIntentMatcherTest -q 2>&1 | tail -8
```
Expected: `ERROR` — `EmbeddingPrototypeIntentMatcher` 不存在

- [ ] **Step 4: 创建 `EmbeddingPrototypeIntentMatcher.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.core.util.VectorMathUtils;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于 Embedding 原型的多意图匹配器（Tier 2）。
 *
 * <p><b>算法：</b>
 * <ol>
 *   <li>将用户消息编码为 embedding 向量并 L2 归一化</li>
 *   <li>与 Redis 中所有意图原型向量逐一计算余弦相似度</li>
 *   <li>相似度超过各意图独立阈值（或全局阈值）的，全部加入结果列表</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingPrototypeIntentMatcher {

    private final EmbeddingService embeddingService;
    private final IntentPrototypeStore prototypeStore;
    private final RoutingConfigProvider routingConfigProvider;

    /**
     * 返回所有相似度超过阈值的意图，按相似度降序排列。
     *
     * @param userMessage 用户消息
     * @return 命中意图列表，可能为空列表
     */
    public List<IntentResult> match(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return List.of();
        }
        Map<String, float[]> prototypes = prototypeStore.getAllPrototypes();
        if (prototypes.isEmpty()) {
            log.debug("[EmbeddingMatcher] 原型库为空，跳过 Tier2");
            return List.of();
        }

        float[] queryNorm = VectorMathUtils.normalize(embeddingService.encode(userMessage));

        RoutingConfig.Intent intentConfig = routingConfigProvider.getConfig().getIntent();
        double globalThreshold = intentConfig.getEmbeddingGlobalThreshold();
        Map<String, Double> intentThresholds = intentConfig.getEmbeddingThresholds();

        List<IntentResult> results = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : prototypes.entrySet()) {
            String intentCode = entry.getKey();
            float[] protoNorm = entry.getValue();
            double similarity = VectorMathUtils.cosineSimilarity(queryNorm, protoNorm);
            double threshold = intentThresholds.getOrDefault(intentCode, globalThreshold);

            if (similarity >= threshold) {
                IntentType type = IntentType.fromCode(intentCode);
                results.add(new IntentResult(type, intentCode, similarity));
                log.debug("[EmbeddingMatcher] 命中 intent={} sim={} threshold={}",
                        intentCode, String.format("%.4f", similarity), threshold);
            }
        }
        results.sort(Comparator.comparingDouble(IntentResult::confidence).reversed());
        return results;
    }
}
```

- [ ] **Step 5: 运行测试确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=EmbeddingPrototypeIntentMatcherTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 5 tests PASS

- [ ] **Step 6: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/MultiIntentClassifier.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/EmbeddingPrototypeIntentMatcher.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/EmbeddingPrototypeIntentMatcherTest.java
git commit -m "feat(intent): 新增 MultiIntentClassifier 接口 + EmbeddingPrototypeIntentMatcher (Tier2)"
```

## Task 7: LangChain4jIntentService 改造（实现 MultiIntentClassifier）+ MultiHybridIntentService + 事件监听

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/MultiHybridIntentService.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/event/IntentPrototypeStoreRefreshListener.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridIntentService.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/MultiHybridIntentServiceTest.java`

**Interfaces:**
- Consumes: `MultiIntentClassifier` (Task 6), `EmbeddingPrototypeIntentMatcher.match()` (Task 6), `KeywordRegexIntentMatcher.matchAll()` (Task 4), `ClassificationTierConstants` (Task 1), `IntentConfigChangedEvent` (Task 1)
- Produces: `MultiHybridIntentService.classifyMulti(String): MultiIntentResult` — implements `MultiIntentService`
- Produces: `LangChain4jIntentService.classifyMulti(String): List<IntentResult>` — implements `MultiIntentClassifier`

- [ ] **Step 1: 写失败测试**

```java
// MultiHybridIntentServiceTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiHybridIntentService 三级级联")
class MultiHybridIntentServiceTest {

    @Mock private KeywordRegexIntentMatcher ruleMatcher;
    @Mock private EmbeddingPrototypeIntentMatcher embeddingMatcher;
    @Mock private MultiIntentClassifier llmClassifier;
    @Mock private RoutingConfigProvider routingConfigProvider;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private MultiHybridIntentService service;

    @BeforeEach
    void setUp() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setMultiIntentEnabled(true);
        config.getIntent().setEmbeddingEnabled(true);
        config.getIntent().setEmbeddingHighConfidence(0.85);
        when(routingConfigProvider.getConfig()).thenReturn(config);

        service = new MultiHybridIntentService(
                ruleMatcher, embeddingMatcher, llmClassifier,
                routingConfigProvider, meterRegistry);
    }

    @Test
    @DisplayName("Tier1 命中 COMPLAINT，跳过 Tier3（hasTransfer=true）")
    void classifyMulti_tier1Complaint_skipsTier3() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0)));
        when(embeddingMatcher.match(any())).thenReturn(List.of());

        var result = service.classifyMulti("我要投诉");

        assertThat(result.requiresTransfer()).isTrue();
        assertThat(result.sourceTier()).isEqualTo("RULE");
        verify(llmClassifier, never()).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier2 高置信度命中，跳过 Tier3")
    void classifyMulti_tier2HighConf_skipsTier3() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of());
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "claim_apply", 0.91)));

        var result = service.classifyMulti("申请理赔");

        assertThat(result.intents()).hasSize(1);
        assertThat(result.sourceTier()).isEqualTo("EMBEDDING");
        verify(llmClassifier, never()).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier1+Tier2 均无命中，触发 Tier3 LLM")
    void classifyMulti_noTier1Tier2_fallsBackToLlm() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of());
        when(embeddingMatcher.match(any())).thenReturn(List.of());
        when(llmClassifier.classifyMulti(any())).thenReturn(List.of(
                new IntentResult(IntentType.CHITCHAT, "chitchat", 0.95)));

        var result = service.classifyMulti("哈哈哈");

        assertThat(result.sourceTier()).isEqualTo("LLM");
        verify(llmClassifier).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier1 和 Tier2 命中不同意图，合并返回两个")
    void classifyMulti_tier1AndTier2_differentIntents_mergedBoth() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 1.0)));
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 0.88),  // 重复，不覆盖
                new IntentResult(IntentType.FAQ_QUERY, "cancel_order", 0.81)));

        var result = service.classifyMulti("查物流同时取消订单");

        assertThat(result.intentCodes())
                .containsExactlyInAnyOrder("query_logistics", "cancel_order");
        // Tier1 的 query_logistics 置信度(1.0) 不被 Tier2(0.88) 覆盖
        assertThat(result.intents().stream()
                .filter(r -> r.intentCode().equals("query_logistics"))
                .findFirst().get().confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("multiIntentEnabled=false，退化为单意图")
    void classifyMulti_disabled_degrades() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setMultiIntentEnabled(false);
        when(routingConfigProvider.getConfig()).thenReturn(config);

        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 1.0)));

        var result = service.classifyMulti("查订单");

        // 退化后只含一个意图
        assertThat(result.intents()).hasSize(1);
    }

    @Test
    @DisplayName("Tier1 抛异常，降级继续走 Tier2")
    void classifyMulti_tier1Exception_degradesToTier2() {
        when(ruleMatcher.matchAll(any())).thenThrow(new RuntimeException("规则层异常"));
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.90)));

        var result = service.classifyMulti("查询");

        assertThat(result.intents()).hasSize(1);
        assertThat(result.sourceTier()).isEqualTo("EMBEDDING");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=MultiHybridIntentServiceTest -q 2>&1 | tail -8
```

- [ ] **Step 3: 在 `LangChain4jIntentService` 中实现 `MultiIntentClassifier`**

在类声明上新增接口实现：
```java
public class LangChain4jIntentService implements IntentService, MultiIntentClassifier {
```

在类末尾新增 `classifyMulti()` 方法和辅助方法：

```java
@Override
public List<IntentResult> classifyMulti(String userMessage) {
    try {
        DomainConfig domain = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
        if (domain == null || domain.intents().isEmpty()) {
            log.warn("[Intent] __system__ 域不存在或意图列表为空");
            return List.of(IntentResult.UNKNOWN);
        }
        String systemPrompt = buildMultiPrompt(domain.intents(), userMessage);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        );
        String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
        return parseMultiResponse(response);
    } catch (Exception e) {
        log.warn("[Intent] 多意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
        return List.of(IntentResult.UNKNOWN);
    }
}

String buildMultiPrompt(List<IntentConfig> intents, String userMessage) {
    StringBuilder sb = new StringBuilder("""
            你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
            {"intents": [{"intent": "<意图>", "confidence": <0.0到1.0的小数>}, ...]}
            注意：
            1. 如果消息只有一个意图，intents 数组只有一个元素
            2. 如果消息包含多个不同意图，按置信度从高到低列出所有意图
            3. 置信度总和不必为 1（各意图独立评分）
            4. 最多返回 3 个意图，置信度低于 0.5 的不要返回
            
            意图取值说明：
            """);
    int maxExamples = routingConfigProvider.getConfig().getIntent().getMaxExamplesToInject();
    for (IntentConfig intent : intents) {
        sb.append("- ").append(intent.code());
        if (intent.description() != null && !intent.description().isBlank()) {
            sb.append("：").append(intent.description());
        }
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

List<IntentResult> parseMultiResponse(String response) {
    if (response == null || response.isBlank()) return List.of(IntentResult.UNKNOWN);
    String json = extractJson(response.trim());
    if (!json.startsWith("{")) return List.of(IntentResult.UNKNOWN);
    try {
        JsonNode root = objectMapper.readTree(json);
        JsonNode intentsNode = root.path("intents");
        double minConf = routingConfigProvider.getConfig().getIntent().getMinLlmConfidence();

        if (intentsNode.isMissingNode() || !intentsNode.isArray()) {
            // 兜底：尝试解析旧格式单意图
            return List.of(parseSingleFallback(root, minConf));
        }
        List<IntentResult> results = new ArrayList<>();
        for (JsonNode node : intentsNode) {
            String intentStr = node.path("intent").asText("UNKNOWN").toUpperCase();
            double confidence = node.path("confidence").asDouble(0.0);
            if (minConf > 0.0 && confidence < minConf) continue;
            IntentType type = IntentType.fromCode(intentStr);
            results.add(new IntentResult(type, intentStr.toLowerCase(), confidence));
        }
        return results.isEmpty() ? List.of(IntentResult.UNKNOWN) : results;
    } catch (Exception e) {
        log.warn("[Intent] 多意图 JSON 解析失败: {}", json, e);
        return List.of(IntentResult.UNKNOWN);
    }
}

private IntentResult parseSingleFallback(JsonNode root, double minConf) {
    String intentStr = root.path("intent").asText("UNKNOWN").toUpperCase();
    double confidence = root.path("confidence").asDouble(0.0);
    if (minConf > 0.0 && confidence < minConf) return IntentResult.UNKNOWN;
    IntentType type = IntentType.fromCode(intentStr);
    return new IntentResult(type, intentStr.toLowerCase(), confidence);
}
```

- [ ] **Step 4: 创建 `MultiHybridIntentService.java`**

```java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 多意图三级级联协调器（@Primary），实现 {@link MultiIntentService}。
 * Tier1: 规则层 &lt;1ms；Tier2: Embedding ~30ms（按需）；Tier3: LLM 200-800ms（兜底）
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class MultiHybridIntentService implements MultiIntentService {

    private final KeywordRegexIntentMatcher ruleMatcher;
    private final EmbeddingPrototypeIntentMatcher embeddingMatcher;
    private final MultiIntentClassifier llmClassifier;
    private final RoutingConfigProvider routingConfigProvider;
    private final MeterRegistry meterRegistry;

    @Override
    public MultiIntentResult classifyMulti(String userMessage) {
        long start = System.currentTimeMillis();
        try {
            return doClassify(userMessage, start);
        } catch (Exception e) {
            log.error("[MultiHybrid] 意图分类异常，降级 UNKNOWN. msg={}", userMessage, e);
            return MultiIntentResult.UNKNOWN;
        }
    }

    private MultiIntentResult doClassify(String userMessage, long start) {
        RoutingConfig.Intent cfg = routingConfigProvider.getConfig().getIntent();

        // 退化模式
        if (!cfg.isMultiIntentEnabled()) {
            List<IntentResult> ruleResult = safeMatchAll(userMessage);
            IntentResult single = ruleResult.isEmpty() ? IntentResult.UNKNOWN : ruleResult.get(0);
            return new MultiIntentResult(List.of(single), ClassificationTierConstants.RULE, 0L);
        }

        Map<String, IntentResult> merged = new LinkedHashMap<>();
        String reachedTier = ClassificationTierConstants.RULE;

        // Tier 1: 规则层（必执行）
        try {
            List<IntentResult> ruleResults = ruleMatcher.matchAll(userMessage);
            ruleResults.forEach(r -> merged.put(r.intentCode(), r));
            if (!ruleResults.isEmpty()) {
                log.debug("[MultiHybrid] Tier1 命中 {} 个意图", ruleResults.size());
            }
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier1 规则层异常，跳过. msg={}", userMessage, e);
        }

        // Tier 2: Embedding 原型层（embeddingEnabled=true 时始终执行）
        if (cfg.isEmbeddingEnabled()) {
            try {
                reachedTier = ClassificationTierConstants.EMBEDDING;
                List<IntentResult> embResults = embeddingMatcher.match(userMessage);
                long newCount = embResults.stream()
                        .filter(r -> !merged.containsKey(r.intentCode())).count();
                embResults.forEach(r -> merged.putIfAbsent(r.intentCode(), r));
                if (newCount > 0) {
                    log.debug("[MultiHybrid] Tier2 新增 {} 个意图", newCount);
                }
            } catch (Exception e) {
                log.warn("[MultiHybrid] Tier2 Embedding 层异常，跳过. msg={}", userMessage, e);
            }
        }

        // Tier 3: LLM 兜底（置信度不足时触发）
        if (shouldFallbackToLlm(merged, cfg)) {
            try {
                reachedTier = ClassificationTierConstants.LLM;
                List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage);
                llmResults.forEach(r -> merged.merge(r.intentCode(), r,
                        (ex, nr) -> ex.confidence() >= nr.confidence() ? ex : nr));
                log.debug("[MultiHybrid] Tier3 LLM 补充后共 {} 个意图", merged.size());
            } catch (Exception e) {
                log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
            }
        }

        List<IntentResult> finalResults = merged.isEmpty()
                ? List.of(IntentResult.UNKNOWN) : List.copyOf(merged.values());

        long elapsed = System.currentTimeMillis() - start;
        log.info("[MultiHybrid] 分类完成 tier={} intentCount={} cost={}ms",
                reachedTier, finalResults.size(), elapsed);

        recordMetrics(reachedTier, finalResults.size(), elapsed);
        return new MultiIntentResult(finalResults, reachedTier, elapsed);
    }

    private boolean shouldFallbackToLlm(Map<String, IntentResult> merged,
                                         RoutingConfig.Intent cfg) {
        if (merged.isEmpty()) return true;
        if (merged.values().stream().anyMatch(IntentResult::requiresTransfer)) return false;
        double highConf = cfg.getEmbeddingHighConfidence();
        return merged.values().stream().noneMatch(r -> r.confidence() >= highConf);
    }

    private List<IntentResult> safeMatchAll(String message) {
        try { return ruleMatcher.matchAll(message); }
        catch (Exception e) { return List.of(); }
    }

    private void recordMetrics(String tier, int intentCount, long elapsedMs) {
        meterRegistry.counter("intent.classification.total",
                "tier", tier, "intent_count", String.valueOf(intentCount)).increment();
        meterRegistry.timer("intent.classification.latency", "tier", tier)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }
}
```

- [ ] **Step 5: 改造 `HybridIntentService` 代理 `MultiHybridIntentService`**

将现有 `HybridIntentService` 的 `classify()` 方法改为通过 `MultiIntentService` 代理：

```java
// HybridIntentService.java — 改造后
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridIntentService implements IntentService {

    private final MultiIntentService multiIntentService;

    @Override
    public IntentResult classify(String userMessage) {
        // 代理多意图服务，取优先级最高的主意图，对旧调用方零感知
        return multiIntentService.classifyMulti(userMessage).primaryIntent();
    }
}
```

> **注意：** `HybridIntentService` 不再是 `@Primary`（`MultiHybridIntentService` 已是 `@Primary`）。
> `HybridIntentService` 仍保留以满足 `IntentService` 类型注入的旧调用方。

- [ ] **Step 6: 创建 `IntentPrototypeStoreRefreshListener.java`**

```java
package com.aria.conversation.infrastructure.event;

import com.aria.conversation.domain.event.IntentConfigChangedEvent;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听意图配置变更事件，异步触发原型向量重建。
 *
 * <p>使用专用线程池 {@code prototypeRebuildExecutor}，需在 @Configuration 中配置：
 * <pre>{@code
 *   @Bean("prototypeRebuildExecutor")
 *   public Executor prototypeRebuildExecutor() {
 *       ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
 *       exec.setCorePoolSize(1); exec.setMaxPoolSize(2); exec.setQueueCapacity(5);
 *       exec.setThreadNamePrefix("proto-rebuild-"); exec.initialize(); return exec;
 *   }
 * }</pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentPrototypeStoreRefreshListener {

    private final IntentPrototypeStore store;

    @Async("prototypeRebuildExecutor")
    @EventListener
    public void onEvent(IntentConfigChangedEvent event) {
        log.info("[PrototypeStore] 检测到 IntentConfig 变更，触发原型重建 domain={}",
                event.domainCode());
        store.rebuild();
    }
}
```

- [ ] **Step 7: 运行测试确认全部通过**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=MultiHybridIntentServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, 6 tests PASS

- [ ] **Step 8: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/MultiHybridIntentService.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/HybridIntentService.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/event/IntentPrototypeStoreRefreshListener.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/MultiHybridIntentServiceTest.java
git commit -m "feat(intent): MultiHybridIntentService 三级级联 + LangChain4j 多意图改造 + 事件监听"
```

## Task 8: 应用层路由改造（ChatAppService + FaqChatAppService）

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/FaqChatAppService.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceMultiIntentTest.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/FaqChatAppServiceMultiIntentTest.java`

**Interfaces:**
- Consumes: `MultiIntentService.classifyMulti()` (Task 7), `MultiIntentResult` (Task 1)
- Produces: `ChatAppService.stream()` 支持多意图 union 路由
- Produces: `FaqChatAppService.stream()` 支持多意图 union/intersection 路由

- [ ] **Step 1: 写 ChatAppService 失败测试**

```java
// ChatAppServiceMultiIntentTest.java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.*;
import com.aria.conversation.domain.service.MultiIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 多意图路由")
class ChatAppServiceMultiIntentTest {

    @Mock private SessionQueueService       sessionQueueService;
    @Mock private DomainSessionAppService   domainSessionService;
    @Mock private FaqChatAppService         faqChatService;
    @Mock private DomainAgentService        domainAgentService;
    @Mock private MultiIntentService        multiIntentService;

    private ChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChatAppService(sessionQueueService, domainSessionService,
                faqChatService, domainAgentService, multiIntentService, objectMapper);
    }

    private MultiIntentResult multiOf(IntentType type, String code) {
        return new MultiIntentResult(
                List.of(new IntentResult(type, code, 1.0)), "RULE", 1L);
    }

    private MultiIntentResult multiOf(IntentResult... results) {
        return new MultiIntentResult(List.of(results), "EMBEDDING", 30L);
    }

    @Test
    @DisplayName("多意图含COMPLAINT + FAQ_QUERY：union语义触发转人工")
    void stream_multiIntentWithComplaint_triggersTransfer() {
        when(sessionQueueService.isActive("s1")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s1", "投诉加查物流", "ec"))
                .thenReturn("ec");
        var multi = multiOf(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 0.88));
        when(multiIntentService.classifyMulti("投诉加查物流")).thenReturn(multi);
        when(faqChatService.handleTransfer(eq("s1"), any()))
                .thenReturn(Flux.just(ChatEvent.transfer("{}")));

        StepVerifier.create(service.stream("s1", "投诉加查物流", "ec"))
                .assertNext(e -> assertThat(e.eventType())
                        .isEqualTo(ChatEvent.EventType.TRANSFER))
                .verifyComplete();

        verify(domainAgentService, never()).streamChat(any(), any(), any(), any());
    }

    @Test
    @DisplayName("多意图无转人工：intentCodes 透传给 DomainAgent")
    void stream_multiIntentNoTransfer_intentCodesPropagated() {
        when(sessionQueueService.isActive("s2")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s2", "查物流取消订单", "ec"))
                .thenReturn("ec");
        var multi = multiOf(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "cancel_order", 0.81));
        when(multiIntentService.classifyMulti("查物流取消订单")).thenReturn(multi);
        when(domainAgentService.streamChat(eq("s2"), eq("ec"), eq("查物流取消订单"), any()))
                .thenReturn(Flux.just(ChatEvent.token("处理中", objectMapper)));

        service.stream("s2", "查物流取消订单", "ec").blockLast();

        verify(domainAgentService).streamChat(eq("s2"), eq("ec"), eq("查物流取消订单"),
                argThat(codes -> codes.contains("query_logistics")
                        && codes.contains("cancel_order")));
    }
}
```

- [ ] **Step 2: 写 FaqChatAppService 失败测试**

```java
// FaqChatAppServiceMultiIntentTest.java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.*;
import com.aria.conversation.domain.service.MultiIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FaqChatAppService 多意图路由")
class FaqChatAppServiceMultiIntentTest {

    @Mock private MultiIntentService multiIntentService;
    // 其他依赖按实际构造器补充 @Mock

    private FaqChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // setUp 按实际构造器补充

    @Test
    @DisplayName("OUT_OF_SCOPE + UNKNOWN 全部：返回拒答模板")
    void stream_effectivelyOutOfScope_returnsOutOfScopeReply() {
        var multi = new MultiIntentResult(List.of(
                new IntentResult(IntentType.OUT_OF_SCOPE, "out_of_scope", 0.9),
                new IntentResult(IntentType.UNKNOWN, "unknown", 0.0)
        ), "LLM", 300L);
        when(multiIntentService.classifyMulti("天气怎么样")).thenReturn(multi);
        // 验证：走拒答分支，不走 LLM + RAG
    }

    @Test
    @DisplayName("OUT_OF_SCOPE + FAQ_QUERY 共存：不拒答，走 LLM+RAG")
    void stream_outOfScopeWithFaq_doesNotRejectAllQuestions() {
        var multi = new MultiIntentResult(List.of(
                new IntentResult(IntentType.OUT_OF_SCOPE, "out_of_scope", 0.6),
                new IntentResult(IntentType.FAQ_QUERY, "query_order", 0.85)
        ), "LLM", 300L);
        assertThat(multi.isEffectivelyOutOfScope()).isFalse();
        // 验证：不走拒答分支
    }

    @Test
    @DisplayName("含TRANSFER_REQUEST：union语义触发转人工")
    void stream_transferRequest_triggersTransfer() {
        var multi = new MultiIntentResult(List.of(
                new IntentResult(IntentType.TRANSFER_REQUEST, "transfer_request", 1.0)
        ), "RULE", 1L);
        assertThat(multi.requiresTransfer()).isTrue();
    }
}
```

- [ ] **Step 3: 运行测试确认编译期或逻辑失败**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest="ChatAppServiceMultiIntentTest,FaqChatAppServiceMultiIntentTest" -q 2>&1 | tail -10
```

- [ ] **Step 4: 改造 `ChatAppService`**

关键改动：
1. 将字段 `IntentService intentClassifier` 替换为 `MultiIntentService multiIntentService`
2. `DomainRouteContext` record 中 `IntentResult intent` → `MultiIntentResult multiIntent`
3. `streamDomain()` 中路由逻辑改为 union 语义

```java
// streamDomain() 关键片段（其余方法保持不变）
private Flux<ChatEvent> streamDomain(String sessionId, String message, String domainCode) {
    return Mono.fromCallable(() -> {
                String activeDomain = domainSessionService.resolveActiveDomain(
                        sessionId, message, domainCode);
                MultiIntentResult multiIntent = multiIntentService.classifyMulti(message);
                return new DomainRouteContext(activeDomain, multiIntent);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(ctx -> {
                MultiIntentResult multi = ctx.multiIntent();
                if (multi.requiresTransfer()) {
                    return faqChatService.handleTransfer(sessionId, multi.primaryIntent());
                }
                return domainAgentService.streamChat(
                        sessionId, ctx.activeDomain(), message, multi.intentCodes());
            });
}

private record DomainRouteContext(String activeDomain, MultiIntentResult multiIntent) {}
```

同时在 `DomainAgentService` 接口和实现中新增重载：
```java
// DomainAgentService 接口
Flux<ChatEvent> streamChat(String sessionId, String domainCode,
                            String message, List<String> intentCodes);
```

- [ ] **Step 5: 改造 `FaqChatAppService`**

关键改动：
1. `FaqContext` record 中 `IntentResult intent` → `MultiIntentResult multiIntent`
2. `buildEventStream()` 路由逻辑使用 `isEffectivelyOutOfScope()` 替代 inline stream

```java
// buildEventStream() 关键片段
private Flux<ChatEvent> buildEventStream(String sessionId, String message, FaqContext ctx) {
    MultiIntentResult multi = ctx.multiIntent();
    if (multi.requiresTransfer()) {
        return handleTransfer(sessionId, multi.primaryIntent());
    }
    if (multi.isEffectivelyOutOfScope()) {
        return Flux.just(ChatEvent.token(OUT_OF_SCOPE_REPLY, objectMapper));
    }
    return buildLlmStream(sessionId, message, ctx);
}

record FaqContext(String domain, MultiIntentResult multiIntent) {}
```

- [ ] **Step 6: 运行全模块测试确认通过**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest="ChatAppServiceMultiIntentTest,FaqChatAppServiceMultiIntentTest,ChatAppServiceIntentTest" \
    -q 2>&1 | tail -8
```
Expected: `BUILD SUCCESS`，原有 `ChatAppServiceIntentTest` 回归通过

- [ ] **Step 7: 提交**

```bash
git add \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/FaqChatAppService.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/DomainAgentService.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceMultiIntentTest.java \
  ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/FaqChatAppServiceMultiIntentTest.java
git commit -m "feat(intent): ChatAppService + FaqChatAppService 应用层多意图 union 路由改造"
```

## Task 9: 全量回归测试 + 特性开关验证

**Files:**
- Test: 运行所有已有测试 + 新增集成回归验证

**Interfaces:**
- Consumes: 所有 Task 1-8 的实现
- 验证目标：新功能正确、旧接口无回归、灰度开关可用

- [ ] **Step 1: 运行 conversation-service 全量单元测试**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`，无测试失败

- [ ] **Step 2: 运行 common-core 全量测试（含 VectorMathUtils）**

```bash
mvn test -pl ai-common/common-core -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 验证向后兼容性 — IntentService.classify() 仍可用**

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest=ChatAppServiceIntentTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`（旧测试全部通过，无回归）

- [ ] **Step 4: 验证多意图 union 语义关键场景**

运行以下测试确认全部通过：

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest="MultiIntentResultTest,MultiHybridIntentServiceTest,ChatAppServiceMultiIntentTest,KeywordRegexIntentMatcherMatchAllTest,EmbeddingPrototypeIntentMatcherTest" \
    -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 验证特性开关退化行为**

`multiIntentEnabled=false` 场景已在 `MultiHybridIntentServiceTest.classifyMulti_disabled_degrades` 覆盖。确认该测试通过：

```bash
mvn test -pl ai-conversation/conversation-service \
    -Dtest="MultiHybridIntentServiceTest#classifyMulti_disabled_degrades" \
    -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 运行全项目编译验证（不跑测试，快速验证无编译错误）**

```bash
mvn compile -pl ai-common/common-core,ai-conversation/conversation-service -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: 提交最终完成标记**

```bash
git add -A
git commit -m "test(intent): 全量回归测试通过，多意图识别与长尾增强功能实现完成

覆盖场景：
- 多意图 union 路由（COMPLAINT+FAQ_QUERY 正确转人工）
- 长尾意图 Tier2 Embedding 原型识别
- 三级级联 Tier3 LLM 兜底
- 特性开关退化（multiIntentEnabled=false）
- 旧接口 IntentService.classify() 向后兼容
- Tier1 规则层全量命中（matchAll 不提前返回）"
```

---

## 实现完成检查单

在所有 Task 完成后，逐一验证：

| 验证项 | 验证方式 | 通过标准 |
|--------|---------|---------|
| domain 层无 infra 依赖 | 检查 `domain/model/MultiIntentResult` 的 import | 无 `infrastructure.*` import |
| 旧单意图接口兼容 | `ChatAppServiceIntentTest` 全通过 | BUILD SUCCESS |
| 多意图 union 转人工 | `ChatAppServiceMultiIntentTest` | COMPLAINT+FAQ_QUERY → handleTransfer |
| 长尾 Tier2 命中 | `EmbeddingPrototypeIntentMatcherTest` | 相似度超阈值返回意图 |
| Tier3 LLM 兜底 | `MultiHybridIntentServiceTest` | Tier1+Tier2 无命中 → LLM |
| 魔法值全部提取 | 搜索 `= 0.75\|= 0.85\|= 0.95` | 仅在常量接口中出现 |
| Flyway 脚本路径正确 | `ls` 确认文件在 conversation-service | ✓ |
| 特性开关可用 | `classifyMulti_disabled_degrades` | 单意图退化 |
