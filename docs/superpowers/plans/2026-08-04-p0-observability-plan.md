# ARIA P0 可观测性改造技术文档

> 文档版本：v1.0  
> 日期：2026-08-04  
> 分支：feature/p0-observability（建议从 `main` 新建）

---

## 一、背景与目标

### 1.1 问题陈述

ARIA 当前已具备 Micrometer + Brave 全链路追踪，但缺少 AI 系统特有的三类指标：

1. **DIT 意图识别各层命中情况不透明**：三层级联（规则 → 向量原型 → LLM）对外只返回最终意图，运营无法知道 Tier1 实际覆盖了多少流量、Tier3 实际消耗了多少 LLM 调用。
2. **RAG 检索质量缺乏量化**：现有代码记录了 `ChunkHit.score` 和 `source`（VECTOR/FULL_TEXT/RERANK），但这些数据从未写入可查询的存储，无法回答"召回质量是否在退化"。
3. **LLM 纠错无闭环**：坐席发现 AI 回答有误后只能手动补充知识库，缺少结构化的反馈入口，高质量的纠错样本无法自动流向 Tier3 样本库（`intent_example_vectors`）。

### 1.2 改造范围（P0）

| 编号 | 子项 | 核心产出 | 预估工作量 |
|------|------|---------|-----------|
| P0-A | DIT 三层命中率指标 + Admin API | `IntentMetricsRecorder` + `cs_intent_tier_stat` 表 + `/admin/stats/intent-classification` | 1 天 |
| P0-B | RAG 检索 score 记录 + miss_log | `cs_rag_miss_log` 表 + 超阈值未命中写入 + `/admin/stats/rag-quality` | 1 天 |
| P0-C | 坐席纠错反馈写回样本库 | `SessionFeedbackController` + `IntentAccumulationService.manualAccumulate()` | 2 天 |
| P0-D | Token 成本统计 + Admin API | `cs_llm_cost_log` 表 + LangChain4j usage 接入 + `/admin/stats/llm-cost` | 1 天 |

**不在本次范围内：**
- 前端 UI 改造（P0-A/B/D 无前端改动；P0-C 前端只加一个"回答有误"按钮，不含复杂交互）
- Grafana/Prometheus 接入（指标持久化到数据库，管理台 REST API 直接查询，不依赖外部监控组件）

### 1.3 设计原则

- **非侵入**：不修改现有 RAG 检索和意图识别的核心逻辑，只在出口处加观测点
- **异步写入**：`cs_rag_miss_log` 的落库通过独立的 `observabilityExecutor` 线程池执行，不影响主链路 P99
- **幂等安全**：纠错反馈复用 `saveIfAbsent` 的 `ON CONFLICT DO NOTHING` 机制
- **可关闭**：通过 `system_config` 键值开关控制各项指标的写入，不需重启

---

## 二、现有代码基础盘点

### 2.1 已有的 Micrometer 注册点

| 位置 | 指标名 | Tag |
|------|--------|-----|
| `MultiHybridIntentService.recordMetrics()` | `intent.classification.total` | `tier`, `intent_count` |
| `MultiHybridIntentService.recordMetrics()` | `intent.classification.latency` | `tier` |
| `IntentAccumulationService.asyncAccumulate()` | `intent.example.accumulate.total` | `intent_code` |

**现状缺口：** 已有的 `intent.classification.total` 记录的是"到达哪层时结束"，但没有区分"命中/未命中"两种情况。P0-A 要在此基础上增加 `result=hit|miss` tag 以及 Tier1/2/3 独立 Counter。

### 2.2 RAG 检索出口

`KnowledgeSearchAppService.hybridSearch()` 最终返回 `List<ChunkHit>`，每个 `ChunkHit` 已携带：
- `double score`（RRF 融合分或 Reranker 分）
- `HitSource source`（VECTOR / FULL_TEXT / RERANK）

调用方是 `ai-conversation` 的 `knowledge-client`，通过 `KnowledgeSearchClient.search()` 拿到 `SearchResponse`，其中包含 `List<ChunkHitDTO>`（含 `score` 和 `source` 字段）。

**现状缺口：** 检索结果只在会话链路中使用，未写入任何可查询存储。

### 2.3 高置信度自学习回写

`IntentAccumulationService.asyncAccumulate()` 已实现 Tier3 高置信度（≥0.95）自动回写逻辑，目标表 `intent_example_vectors`，幂等写入（`ON CONFLICT DO NOTHING`）。

**现状缺口：** 只有 LLM 自动积累，没有坐席人工纠错的入口；纠错样本进入的应当是 `autoConfirmed=false`（人工确认），质量高于自动积累，应享有更高权重或标记。

## P0-A：DIT 三层命中率指标

### 背景与现状

`MultiHybridIntentService.recordMetrics()` 已埋入两个 Micrometer 指标：

```
intent.classification.total   — Counter，tag: tier, intent_count
intent.classification.latency — Timer，tag: tier
```

但 `tier` 的实际写入值是 `reachedTier`（最终落到哪层），**不是**每层独立命中情况。当 Tier1 命中后直接返回，Tier2/Tier3 的执行次数从现有指标中无法单独统计。面试问"Tier1 命中率是多少"时，现有指标答不上来。

### 改造目标

- 新增每层独立 Counter：`intent.tier.hit.total`（tag: `tier=RULE/EMBEDDING/LLM`）
- 新增每层延迟 Timer：`intent.tier.latency`（tag: `tier`）
- 现有 `intent.classification.total` 保持不变（向后兼容）

### 改造点

**核心思路：** 提取 `IntentMetricsRecorder` 专用组件，将指标名、tag key 集中管理，`MultiHybridIntentService` 的各层方法调用侧只需一行，不再直接操作 `MeterRegistry`。

**Step 1 — 新增 `IntentMetricsRecorder`（新文件）**

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/ai/IntentMetricsRecorder.java
package com.aria.conversation.infrastructure.ai;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * DIT 意图识别各层指标记录器。
 * <p>
 * 将指标名与 tag key 集中在此类，修改指标定义只改这一处。
 * {@link MultiHybridIntentService} 不再直接依赖 {@link MeterRegistry}。
 */
@Component
@RequiredArgsConstructor
public class IntentMetricsRecorder {

    private static final String METRIC_HIT      = "intent.tier.hit.total";
    private static final String METRIC_LATENCY  = "intent.tier.latency";
    private static final String METRIC_FEEDBACK = "intent.feedback.total";
    private static final String METRIC_ACCUM    = "intent.example.accumulate.total";
    private static final String TAG_TIER        = "tier";
    private static final String TAG_HIT         = "hit";
    private static final String TAG_TYPE        = "type";
    private static final String TAG_INTENT_CODE = "intent_code";
    private static final String TAG_SOURCE      = "source";

    private final MeterRegistry registry;

    /**
     * 记录某一层的执行结果。
     *
     * @param tier      层标识，取 {@link ClassificationTierConstants} 中的常量
     * @param hit       true = 本层有命中意图；false = 未命中，将级联下一层
     * @param elapsedMs 本层实际耗时（毫秒）
     */
    public void record(String tier, boolean hit, long elapsedMs) {
        registry.counter(METRIC_HIT, TAG_TIER, tier, TAG_HIT, String.valueOf(hit)).increment();
        registry.timer(METRIC_LATENCY, TAG_TIER, tier).record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录坐席反馈提交（P0-C）。type 取 wrong_intent / wrong_answer / good。
     */
    public void recordFeedback(String type) {
        registry.counter(METRIC_FEEDBACK, TAG_TYPE, type).increment();
    }

    /**
     * 记录意图样本积累（P0-C manual / Tier3 auto 共用）。
     *
     * @param intentCode 意图 code
     * @param source     manual（坐席纠错）/ auto（LLM 高置信）
     */
    public void recordAccumulate(String intentCode, String source) {
        registry.counter(METRIC_ACCUM, TAG_INTENT_CODE, intentCode, TAG_SOURCE, source).increment();
    }
}
```

**Step 2 — 改造 `MultiHybridIntentService`**

将构造参数中的 `MeterRegistry` 替换为 `IntentMetricsRecorder`，删除原有私有方法 `recordMetrics()`，各层出口调用一行 `metricsRecorder.record(...)`：

```java
// 依赖替换（@RequiredArgsConstructor 构造注入）
// 删除：private final MeterRegistry meterRegistry;
// 新增：
private final IntentMetricsRecorder metricsRecorder;

// applyTier1
private List<IntentResult> applyTier1(String userMessage) {
    long start = System.currentTimeMillis();
    List<IntentResult> results = ruleMatcher.matchAll(userMessage);
    metricsRecorder.record(ClassificationTierConstants.RULE,
            !results.isEmpty(), System.currentTimeMillis() - start);
    return results;
}

// applyTier2
private List<IntentResult> applyTier2(String userMessage) {
    long start = System.currentTimeMillis();
    List<IntentResult> results = embeddingMatcher.match(userMessage);
    metricsRecorder.record(ClassificationTierConstants.EMBEDDING,
            !results.isEmpty(), System.currentTimeMillis() - start);
    return results;
}

// applyTier3（原有自学习回写逻辑不变）
private List<IntentResult> applyTier3(String userMessage,
        List<IntentConfig> mergedIntents, RoutingConfig.Intent cfg) {
    long start = System.currentTimeMillis();
    List<IntentResult> results = llmClassifier.classifyMulti(userMessage, mergedIntents);
    metricsRecorder.record(ClassificationTierConstants.LLM,
            !results.isEmpty(), System.currentTimeMillis() - start);
    accumulationService.asyncAccumulate(results, userMessage, cfg);
    return results;
}
```

调用侧每层只有一行，方法主体的业务逻辑清晰可读。`doClassify` 末尾原有的 `recordMetrics()` 调用整体删除。

### 新增指标说明

| 指标名 | 类型 | Tags | 用途 |
|--------|------|------|------|
| `intent.tier.hit.total` | Counter | `tier`, `hit=true/false` | 各层执行次数 + 命中次数，hit=true / total = 命中率 |
| `intent.tier.latency` | Timer | `tier` | 各层 P50/P99 延迟，评估 Tier3 LLM 对整体延迟的贡献 |
| `intent.classification.total` | Counter（原有） | `tier`, `intent_count` | 保持不变，记录最终到达层 |
| `intent.classification.latency` | Timer（原有） | `tier` | 保持不变，记录全链路耗时 |

### 管理台查询 API

**数据来源澄清：** Micrometer Counter/Timer 是 JVM 内存级指标，进程重启即清零，**无法**作为管理台历史趋势的数据源。因此本方案采用「明细落库 + 实时聚合」：`IntentMetricsRecorder.record()` 在打 Micrometer 指标的同时，**异步写一条分层明细**到 `cs_intent_tier_stat`；Admin API 直接对明细表做 `GROUP BY` 聚合，不引入预聚合表，也不依赖定时任务。

- Micrometer 指标：留给 `/actuator/metrics` 做 JVM 级实时观测（进程内命中率、延迟）
- 明细表 `cs_intent_tier_stat`：作为管理台历史趋势与命中率报表的唯一数据源

**接口：** `GET /api/v1/admin/stats/intent-classification`

| 参数 | 类型 | 说明 |
|------|------|------|
| `period` | String（枚举） | `today` / `7d` / `30d`，非法值返回 400 |
| `domainCode` | String（可选） | 按域过滤 |

**响应示例：**

```json
{
  "period": "today",
  "totalClassifications": 12480,
  "tier1HitRate": 0.74,
  "tier2HitRate": 0.18,
  "tier3TriggerRate": 0.08,
  "avgLatencyMs": {
    "RULE": 2,
    "EMBEDDING": 47,
    "LLM": 318
  },
  "hourlyTrend": [
    { "hour": "2026-08-04T09:00", "tier1Hit": 340, "tier2Hit": 62, "tier3Hit": 21 }
  ]
}
```

**明细落库表：** `cs_conversation.cs_intent_tier_stat`（一次分类写一行，记录三层各自是否执行/命中/耗时）。

```sql
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_tier_stat
(
    id                   BIGSERIAL PRIMARY KEY,
    session_id           VARCHAR(64),
    domain_code          VARCHAR(64),
    reached_tier         VARCHAR(20)  NOT NULL,          -- 最终到达层 RULE/EMBEDDING/LLM
    tier1_hit            BOOLEAN      NOT NULL DEFAULT FALSE,
    tier2_executed       BOOLEAN      NOT NULL DEFAULT FALSE,
    tier2_hit            BOOLEAN      NOT NULL DEFAULT FALSE,
    tier3_executed       BOOLEAN      NOT NULL DEFAULT FALSE,
    tier3_hit            BOOLEAN      NOT NULL DEFAULT FALSE,
    tier1_latency_ms     INTEGER,
    tier2_latency_ms     INTEGER,
    tier3_latency_ms     INTEGER,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_intent_tier_stat_time   ON cs_conversation.cs_intent_tier_stat (created_at DESC);
CREATE INDEX idx_intent_tier_stat_domain ON cs_conversation.cs_intent_tier_stat (domain_code, created_at DESC);
```

**明细写入位置：** 在 `MultiHybridIntentService.doClassify()` 末尾（已知各层执行/命中结果与耗时后）调用一次 `intentMetricsRecorder.persistDetail(...)`，异步写入上表。`IntentMetricsRecorder` 因此需要一个 `IntentTierStatMapper` 依赖：

```java
// IntentMetricsRecorder 追加异步落库方法
private final IntentTierStatMapper tierStatMapper;

/**
 * 异步写入单次分类的分层明细，供 Admin API 聚合。
 * 使用 observabilityExecutor 线程池，写失败不影响主流程。
 */
@Async("observabilityExecutor")
public void persistDetail(IntentTierStatEntity detail) {
    try {
        tierStatMapper.insert(detail);
    } catch (Exception e) {
        log.warn("[IntentTierStat] 明细写入失败 session={}", detail.getSessionId(), e);
    }
}
```

> Admin API 的 `tier1HitRate = SUM(tier1_hit) / COUNT(*)`，`tier3TriggerRate = SUM(tier3_executed::int) / COUNT(*)`，`avgLatencyMs` 按各 `tierN_latency_ms` 求平均，全部在明细表上一条 SQL 完成。

### 单测补充

**文件：** `MultiHybridIntentServiceTest.java`（已有，补充以下测试方法）

**关键点：** 改造后 `MultiHybridIntentService` 不再直接持有 `MeterRegistry`，而是持有 `IntentMetricsRecorder`。测试若继续对一个独立的 `meterRegistry` 做断言，会因为与 service 内实际使用的实例不是同一个而恒为 0。两种正确写法二选一：

**写法 A — 用真实 `IntentMetricsRecorder` 包裹 `SimpleMeterRegistry`（推荐，验证真实指标值）：**

```java
private SimpleMeterRegistry meterRegistry;
private IntentMetricsRecorder metricsRecorder;
private MultiHybridIntentService service;

@BeforeEach
void setUp() {
    meterRegistry  = new SimpleMeterRegistry();
    // tierStatMapper 用 mock，避免真实 DB 写入
    metricsRecorder = new IntentMetricsRecorder(meterRegistry, mock(IntentTierStatMapper.class));
    service = new MultiHybridIntentService(
            ruleMatcher, embeddingMatcher, llmClassifier,
            accumulationService, metricsRecorder /* 其余依赖 */);
}

@Test
@DisplayName("Tier1 命中时应记录 tier=RULE hit=true，不触发 Tier2/Tier3")
void shouldRecordTier1HitMetricWhenRuleMatched() {
    when(ruleMatcher.matchAll(anyString()))
        .thenReturn(List.of(new IntentResult("ORDER_QUERY", IntentType.STANDARD, 1.0)));

    service.classify("我想查订单", "DEFAULT");

    // 断言的 registry 与 recorder 内部使用的是同一实例
    assertThat(meterRegistry.counter("intent.tier.hit.total",
            "tier", "RULE", "hit", "true").count()).isEqualTo(1.0);
    verify(embeddingMatcher, never()).match(anyString());
    verify(llmClassifier, never()).classifyMulti(anyString(), anyList());
}
```

**写法 B — mock `IntentMetricsRecorder`，只验证交互（更快，不校验指标值）：**

```java
@Mock private IntentMetricsRecorder metricsRecorder;

@Test
@DisplayName("Tier1 未命中 + Tier2 命中时，应以 EMBEDDING/hit=true 调用 recorder")
void shouldRecordTier2HitMetricWhenEmbeddingMatched() {
    when(ruleMatcher.matchAll(anyString())).thenReturn(Collections.emptyList());
    when(embeddingMatcher.match(anyString()))
        .thenReturn(List.of(new IntentResult("COMPLAINT", IntentType.STANDARD, 0.88)));

    service.classify("这个问题太烦人了", "DEFAULT");

    verify(metricsRecorder).record(eq(ClassificationTierConstants.EMBEDDING), eq(true), anyLong());
    verify(llmClassifier, never()).classifyMulti(anyString(), anyList());
}
```

### 改动范围

- **新增 4 个文件：** `IntentMetricsRecorder.java`（集中指标名/tag key + 异步落库）、`IntentTierStatEntity.java`、`IntentTierStatMapper.java`、`IntentStatsAppService.java`（Admin API 聚合查询）
- **修改 1 个文件：** `MultiHybridIntentService.java`（将 `meterRegistry` 替换为 `intentMetricsRecorder`，删除旧 `recordMetrics()` 方法，在 `applyTier1/2/3` 出口各调用一行 `metricsRecorder.record(...)`，`doClassify()` 末尾调用一次 `persistDetail(...)`）
- **新增 Admin 接口：** `AdminStatsController#getIntentClassificationStats`（对 `cs_intent_tier_stat` 明细表实时聚合）
- **新增 1 张表：** `cs_intent_tier_stat`（Flyway 迁移文件，含分层命中/耗时明细）
- **新增 1 个测试文件：** `MultiHybridIntentServiceTest.java` 补充约 30 行（真实 `IntentMetricsRecorder` 注入，`IntentTierStatMapper` 用 mock）
- 预计工作量：**1 天**

# P0-B：RAG 检索质量记录与 miss_log

## 背景与目标

现有 `KnowledgeSearchAppService.hybridSearch()` 已返回 `ChunkHitDTO.score`（RRF 融合分或 Reranker 分）和 `source`（VECTOR / FULL_TEXT / RERANK），但这些数据仅在内存中流过，未持久化，无法事后分析检索质量。

本改造目标：
1. **检索质量可量化**：记录每次搜索的 top-K score 分布，能回答"RAG 准不准"
2. **知识覆盖度分析**：识别向量距离超过阈值（即未命中）的查询，驱动 KB 补充
3. **指标接入 Micrometer**：score 分布以 histogram 形式暴露，可在 Grafana 可视化

---

## 改造范围

| 服务 | 改动类 | 类型 |
|------|--------|------|
| `ai-conversation/conversation-service` | `ChatAppService` + `FaqChatAppService` | 两条 RAG 检索路径返回后写质量日志 |
| `ai-conversation/conversation-service` | `RagQualityRecorder`（新增） | miss 判定 + 异步落库 |
| `ai-conversation/conversation-service` | `RagMissLogEntity` / `RagMissLogMapper`（新增） | DB 实体 + Mapper |
| `ai-conversation/conversation-service` | `AsyncConfig` | 新增 `observabilityExecutor` 线程池 |
| `ai-knowledge/knowledge-service` | `KnowledgeSearchAppService` | Micrometer histogram 埋点 |
| DB migration | `V{next}__add_rag_miss_log.sql` | 新增 `cs_rag_miss_log` 表 |

---

## 数据模型

### cs_conversation.cs_rag_miss_log 表

```sql
-- ai-conversation/conversation-service/src/main/resources/db/migration/
-- 文件名：V{next}__add_rag_miss_log.sql
CREATE TABLE IF NOT EXISTS cs_conversation.cs_rag_miss_log
(
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(64)              NOT NULL,
    kb_id         VARCHAR(64),
    query_text    TEXT                     NOT NULL,
    top1_score    DOUBLE PRECISION,           -- 最高分 chunk 的 score；NULL 表示完全未命中（0 结果）
    hit_count     SMALLINT        NOT NULL DEFAULT 0, -- 本次检索命中 chunk 数
    is_miss       BOOLEAN         NOT NULL DEFAULT FALSE, -- TRUE = score < 阈值或 hit_count = 0
    source        VARCHAR(20),                -- VECTOR / FULL_TEXT / RERANK
    domain_code   VARCHAR(64),
    intent_codes  TEXT[],                     -- 本次路由到的意图列表
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rag_miss_log_session  ON cs_conversation.cs_rag_miss_log (session_id);
CREATE INDEX idx_rag_miss_log_miss     ON cs_conversation.cs_rag_miss_log (is_miss, created_at DESC);
CREATE INDEX idx_rag_miss_log_kb       ON cs_conversation.cs_rag_miss_log (kb_id, created_at DESC);
```

**字段说明：**
- `top1_score = NULL` + `hit_count = 0`：检索完全返回空（KB 为空或 query embedding 失败）
- `is_miss = TRUE`：`hit_count = 0` 或 `top1_score < RAG_MISS_THRESHOLD`（阈值见配置节）
- `intent_codes`：从 `MultiIntentResult` 中取，用于分析哪类意图 KB 覆盖度差

---

## 配置常量

在 `IntentClassificationConstants.java` 同级新建或追加到 `CustomerServiceCacheConstant.java`：

```java
// ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/
// 追加到已有 IntentClassificationConstants 接口
interface IntentClassificationConstants {
    // ... 已有常量 ...

    /**
     * RAG 检索命中判定阈值。
     * top1_score 低于此值时判定为 miss，写入 cs_rag_miss_log.is_miss = true。
     * 默认 0.5（RRF 分范围约 0–1，实践中 ≥0.5 表示有效命中）。
     * 可通过 system_config key=rag.miss.threshold 覆盖。
     */
    double DEFAULT_RAG_MISS_THRESHOLD = 0.5;
}
```

---

## 新增：RagMissLogEntity

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/rag/RagMissLogEntity.java
package com.aria.conversation.infrastructure.rag;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
@TableName(value = "cs_conversation.cs_rag_miss_log", autoResultMap = true)
public class RagMissLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String kbId;
    private String queryText;
    private Double top1Score;       // nullable：完全未命中时为 null
    private Short hitCount;
    private Boolean isMiss;
    private String source;
    private String domainCode;

    @TableField(typeHandler = com.aria.conversation.infrastructure.persistence.ArrayTypeHandler.class)
    private String[] intentCodes;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

> **注：** `ArrayTypeHandler` 若项目中尚无，参见下方"类型处理器"小节。

---

## 新增：RagQualityRecorder（质量评估 + 异步落库）

> **分层说明（对应评审 Rec1）：** miss 阈值判断属于业务逻辑，不应放在 Repository（持久化职责单一）。因此拆成两层：
> - `RagQualityRecorder`（`infrastructure/rag`，`@Component`）：接收原始检索结果，计算 `isMiss` / `top1Score`，构建 entity，异步落库。
> - `RagMissLogMapper`（MyBatis-Plus `BaseMapper`）：纯持久化，`recorder` 直接注入 mapper，不再继承 `ServiceImpl`。
>
> **不再用构造注入 `@Value`（对应评审修复 3）：** MyBatis-Plus 的 `ServiceImpl` 由框架实例化，自定义构造参数不会触发 `@Value` 注入，`missThreshold` 会静默取 `0.0`，导致所有检索都被判成 miss。`RagQualityRecorder` 是普通 `@Component`，用字段注入即可安全生效。

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/rag/RagQualityRecorder.java
package com.aria.conversation.infrastructure.rag;

import com.aria.sdk.knowledge.dto.ChunkHitDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 检索质量记录器。
 * <p>
 * 负责：miss 判定（业务逻辑）+ 异步落库（不阻塞主会话流程）。
 * 写入使用独立的 observabilityExecutor 线程池（拒绝策略 DiscardPolicy），
 * 队列满时丢弃日志而非拖慢 SSE 主线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagQualityRecorder {

    private final RagMissLogMapper mapper;

    /**
     * miss 判定阈值。字段注入，默认取 {@link IntentClassificationConstants#DEFAULT_RAG_MISS_THRESHOLD}。
     * 可通过 system_config / 配置项 aria.rag.miss-threshold 覆盖。
     */
    @Value("${aria.rag.miss-threshold:0.5}")
    private double missThreshold;

    /**
     * 异步记录一次 RAG 检索质量快照。
     *
     * @param sessionId   当前会话 ID
     * @param kbId        知识库 ID（可为 null）
     * @param query       用户原始问题
     * @param hits        检索命中列表（可为空列表）
     * @param domainCode  当前域 code
     * @param intentCodes 本次识别到的意图 code 列表
     */
    @Async("observabilityExecutor")
    public void record(String sessionId, String kbId, String query,
                       List<ChunkHitDTO> hits, String domainCode, List<String> intentCodes) {
        try {
            boolean empty = hits == null || hits.isEmpty();
            Double top1Score = empty ? null : hits.get(0).getScore();
            boolean isMiss = empty || top1Score < missThreshold;

            RagMissLogEntity entity = RagMissLogEntity.builder()
                    .sessionId(sessionId)
                    .kbId(kbId)
                    .queryText(query)
                    .top1Score(top1Score)
                    .hitCount((short) (empty ? 0 : hits.size()))
                    .isMiss(isMiss)
                    .source(empty ? null : hits.get(0).getSource())
                    .domainCode(domainCode)
                    .intentCodes(intentCodes == null ? new String[0]
                                                     : intentCodes.toArray(String[]::new))
                    .build();

            mapper.insert(entity);
        } catch (Exception e) {
            // 日志写失败不影响主流程
            log.warn("[RagQuality] 写入失败 session={} query={}", sessionId, query, e);
        }
    }
}
```

---

## 新增：RagMissLogMapper

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/rag/RagMissLogMapper.java
package com.aria.conversation.infrastructure.rag;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagMissLogMapper extends BaseMapper<RagMissLogEntity> {
}
```

---

## 新增：observabilityExecutor 线程池

P0-A 明细落库（`IntentMetricsRecorder.persistDetail`）、P0-B 检索质量记录（`RagQualityRecorder.logAsync`）、P0-D 成本日志（`LlmCostLogger.logAsync`）都是「可丢失、绝不能阻塞主链路」的观测性写入。它们**不能**复用 `webhookExecutor`：后者的拒绝策略是 `CallerRunsPolicy`，队列满时会退回调用方线程（SSE 主线程 / WebSocket handler）同步执行 DB 写入，直接拉高响应延迟。

为此新增一个专用线程池，拒绝策略用 `DiscardPolicy`——队列满时**直接丢弃**观测记录（偶发丢点日志可接受），保证主流程零阻塞。

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/config/AsyncConfig.java
// 在已有 AsyncConfig 中追加一个 Bean（与 webhookExecutor / intentAccumulateExecutor 并列）

/**
 * 可观测性写入专用线程池。
 * <p>
 * 用于 P0 观测性明细落库（意图分层明细、RAG 检索质量、LLM 成本），
 * 特性：core 小、队列有界、满时直接丢弃（DiscardPolicy），确保绝不阻塞主链路。
 */
@Bean("observabilityExecutor")
public Executor observabilityExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("observability-");
    // 队列满即丢弃，观测数据允许有损，主流程绝不等待
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
}
```

> **与其它线程池的分工：** `webhookExecutor`（CallerRunsPolicy，通知不可丢）、`intentAccumulateExecutor`（样本积累，保序）、`observabilityExecutor`（观测数据，可丢、不阻塞）职责隔离，互不干扰。

---

## 改造：ChatAppService / FaqChatAppService — 注入质量记录调用

RAG 检索在 conversation-service 有**两条独立路径**：

- `ChatAppService`：DIT 路由 + 工具调用主链路
- `FaqChatAppService`：纯 FAQ 问答路径（不走工具调用）

两条路径都会调用 `knowledgeClient.search`。**两处都要补埋点**，否则 FAQ 流量的检索质量会从统计中缺失，miss 率被系统性低估。

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        application/service/ChatAppService.java（FaqChatAppService.java 同样处理）
// 已有依赖注入字段（构造注入）：
private final RagQualityRecorder ragQualityRecorder;  // ← 新增注入

// 在 hybridSearch 调用处（伪代码定位，match 现有代码结构）：

// ---- 现有代码 ----
List<ChunkHitDTO> hits = knowledgeClient.search(SearchRequest.of(query, kbId, topK));

// ---- 新增：记录检索质量（异步，不阻塞 SSE 流程）----
// isMiss 判定在 Recorder 内完成，阈值由 RagQualityRecorder 统一持有
ragQualityRecorder.record(
        session.getSessionId(),
        kbId,
        query,
        hits,
        routingResult.getDomainCode(),
        routingResult.getIntentCodes()   // MultiIntentResult.getIntentCodes() 已有此方法
);
// ---- 继续已有逻辑 ----
```

> **定位提示**：分别在 `ChatAppService` 与 `FaqChatAppService` 中搜索 `knowledgeClient.search` 或 `knowledgeSearchClient`，在每个调用点后追加上述记录调用。FAQ 路径若无 `routingResult`（未做 DIT 路由），`domainCode` 传 `null`、`intentCodes` 传空列表即可。

---

## 改造：KnowledgeSearchAppService — Micrometer histogram

在 `ai-knowledge/knowledge-service` 的 `KnowledgeSearchAppService` 中，`hybridSearch()` 返回前追加指标记录：

```java
// 路径：ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/
//        application/service/KnowledgeSearchAppService.java

// 已有字段：
private final MeterRegistry meterRegistry;  // ← 若未注入，追加构造参数

// 在 hybridSearch() 返回语句之前：
private List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
    // ... 已有检索逻辑 ...
    List<ChunkHit> finalHits = rerankService.rerank(query, candidates)
                                            .stream().limit(topK)
                                            .toList();

    // ---- 新增：Micrometer 指标 ----
    recordSearchMetrics(finalHits);
    // ---- 结束新增 ----

    return finalHits;
}

/**
 * 记录 RAG 检索质量指标。
 * <p>
 * 暴露两个指标：
 * <ul>
 *   <li>{@code rag.search.hit_count} — Histogram：每次检索的命中 chunk 数</li>
 *   <li>{@code rag.search.top1_score} — Histogram：top-1 chunk 的 score 分布</li>
 *   <li>{@code rag.search.miss_total} — Counter：未命中（空结果）次数</li>
 * </ul>
 */
private void recordSearchMetrics(List<ChunkHit> hits) {
    int hitCount = hits.size();
    meterRegistry.summary("rag.search.hit_count").record(hitCount);

    if (hitCount == 0) {
        meterRegistry.counter("rag.search.miss_total").increment();
    } else {
        double top1Score = hits.get(0).getScore();
        meterRegistry.summary("rag.search.top1_score").record(top1Score);

        // 按来源分类计数
        String source = hits.get(0).getSource().name();  // VECTOR / FULL_TEXT / RERANK
        meterRegistry.counter("rag.search.source_total", "source", source).increment();
    }
}
```

---

## ArrayTypeHandler（如项目尚缺）

若 `cs_conversation` 项目中尚无 `String[]` ↔ PostgreSQL `TEXT[]` 的类型处理器，新增如下：

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/persistence/ArrayTypeHandler.java
package com.aria.conversation.infrastructure.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

@MappedTypes(String[].class)
public class ArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("text", parameter);
        ps.setArray(i, array);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Array array = rs.getArray(columnName);
        return array == null ? new String[0] : (String[]) array.getArray();
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Array array = rs.getArray(columnIndex);
        return array == null ? new String[0] : (String[]) array.getArray();
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Array array = cs.getArray(columnIndex);
        return array == null ? new String[0] : (String[]) array.getArray();
    }
}
```

---

## 数据查询示例

改造上线后，可直接用 SQL 分析 KB 覆盖度：

```sql
-- 过去 7 天 miss 率趋势
SELECT DATE_TRUNC('day', created_at)  AS day,
       COUNT(*)                        AS total,
       COUNT(*) FILTER (WHERE is_miss) AS miss_count,
       ROUND(COUNT(*) FILTER (WHERE is_miss) * 100.0 / COUNT(*), 1) AS miss_rate_pct
FROM cs_conversation.cs_rag_miss_log
WHERE created_at >= NOW() - INTERVAL '7 days'
GROUP BY 1
ORDER BY 1;

-- miss 率最高的 intent（指导 KB 补充优先级）
SELECT UNNEST(intent_codes) AS intent_code,
       COUNT(*)              AS miss_count
FROM cs_conversation.cs_rag_miss_log
WHERE is_miss = TRUE
  AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY 1
ORDER BY 2 DESC
LIMIT 20;

-- top1_score 分布分桶
SELECT WIDTH_BUCKET(top1_score, 0, 1, 10) AS score_bucket,
       COUNT(*)                             AS freq
FROM cs_conversation.cs_rag_miss_log
WHERE top1_score IS NOT NULL
  AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY 1
ORDER BY 1;
```

---

## 验证步骤

1. 执行 Flyway migration，确认 `cs_rag_miss_log` 表创建成功
2. 发起含 RAG 检索的会话，确认 `cs_rag_miss_log` 有行写入
3. 用命中率低的问题（KB 没有相关内容）发起会话，确认 `is_miss = TRUE`
4. 访问 `/actuator/metrics/rag.search.top1_score`，确认 histogram 有数据
5. 访问 `/actuator/metrics/rag.search.miss_total`，确认 counter 递增

# P0-C：坐席纠错反馈写回样本库

## 背景与目标

现有 Tier3 高置信度结果通过 `IntentAccumulationService.asyncAccumulate()` 自动写入 `intent_example_vectors`，但只有机器确认的样本，缺乏人工纠错入口。

当 AI 给出错误回答时，坐席无法将正确答案反馈给系统，导致：
- 错误意图识别反复出现
- 知识库盲点无法被动更新

**本改造目标：**
1. 坐席可对 AI 回答标记"回答有误"并填写正确意图 / 正确答案
2. 正确意图 code + 原始用户消息 → 写入 `intent_example_vectors`（强化 Tier2 原型）
3. 正确答案文本 → 写入知识库审核队列（现有 KB 摄入管道）
4. 指标：`intent.feedback.total` Counter，tag `type=correct|wrong`

---

## 改造范围

| 服务 | 改动类 | 类型 |
|------|--------|------|
| `ai-conversation/conversation-service` | `SessionFeedbackController`（新增） | REST 接口 |
| `ai-conversation/conversation-service` | `SessionFeedbackAppService`（新增） | 应用层用例 |
| `ai-conversation/conversation-service` | `IntentAccumulationService` | 新增 `manualAccumulate()` |
| `ai-conversation/conversation-service` | `SessionFeedbackRepository`（新增） | 持久化反馈记录 |
| DB migration | `V{next}__add_session_feedback.sql` | 新增 `cs_session_feedback` 表 |

---

## 数据模型

### cs_conversation.cs_session_feedback 表

```sql
-- ai-conversation/conversation-service/src/main/resources/db/migration/
-- 文件名：V{next}__add_session_feedback.sql
CREATE TABLE IF NOT EXISTS cs_conversation.cs_session_feedback
(
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64)              NOT NULL,
    message_id      VARCHAR(64),                        -- 被标记的 AI 消息 ID（可为 null）
    feedback_type   VARCHAR(20)  NOT NULL,              -- WRONG_INTENT | WRONG_ANSWER | GOOD
    original_query  TEXT         NOT NULL,              -- 用户原始消息
    correct_intent  VARCHAR(64),                        -- 坐席填写的正确意图 code
    correct_answer  TEXT,                               -- 坐席填写的正确回答
    agent_id        BIGINT,                             -- 操作坐席 ID
    accumulated     BOOLEAN      NOT NULL DEFAULT FALSE, -- 是否已写入 example_vectors
    kb_queued       BOOLEAN      NOT NULL DEFAULT FALSE, -- 是否已推入 KB 审核队列
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_feedback_session   ON cs_conversation.cs_session_feedback (session_id);
CREATE INDEX idx_session_feedback_pending   ON cs_conversation.cs_session_feedback (accumulated, kb_queued)
    WHERE accumulated = FALSE OR kb_queued = FALSE;
```

**字段说明：**
- `feedback_type = WRONG_INTENT`：意图识别错，`correct_intent` 必填
- `feedback_type = WRONG_ANSWER`：回答内容错，`correct_answer` 必填
- `feedback_type = GOOD`：正向反馈，仅计数，不写样本
- `accumulated / kb_queued`：幂等写入标记，防止重复积累
- **`kb_queued` 在 P0 阶段恒为 `FALSE`**：`WRONG_ANSWER` 的 KB 审核队列入队逻辑是 P1 占位（见 `handleWrongAnswer` 的 TODO），P0 只落库不入队。读取本字段做数据分析时需注意这一点，不要误判为"所有回答纠错都未入队处理"。

---

## DTO 定义

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        interfaces/dto/SessionFeedbackRequest.java
package com.aria.conversation.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SessionFeedbackRequest {

    @NotBlank
    private String sessionId;

    /** 被标记的 AI 消息 ID，前端记录后传入 */
    private String messageId;

    /** WRONG_INTENT | WRONG_ANSWER | GOOD */
    @NotNull
    private FeedbackType feedbackType;

    @NotBlank
    private String originalQuery;   // 用户原始消息，前端上下文已有

    /** feedbackType = WRONG_INTENT 时必填 */
    private String correctIntent;

    /** feedbackType = WRONG_ANSWER 时必填 */
    private String correctAnswer;

    public enum FeedbackType {
        WRONG_INTENT, WRONG_ANSWER, GOOD
    }
}
```

---

## 新增：SessionFeedbackController

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        interfaces/rest/SessionFeedbackController.java
package com.aria.conversation.interfaces.rest;

import com.aria.common.core.Result;
import com.aria.conversation.application.service.SessionFeedbackAppService;
import com.aria.conversation.interfaces.dto.SessionFeedbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "坐席反馈")
@RestController
@RequestMapping("/api/v1/sessions/feedback")
@RequiredArgsConstructor
public class SessionFeedbackController {

    private final SessionFeedbackAppService feedbackAppService;

    @Operation(summary = "提交 AI 回答反馈（坐席纠错 / 点赞）")
    @PostMapping
    public Result<Void> submitFeedback(@Valid @RequestBody SessionFeedbackRequest request) {
        feedbackAppService.submitFeedback(request);
        return Result.ok();
    }
}
```

---

## 新增：SessionFeedbackAppService

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        application/service/SessionFeedbackAppService.java
package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.ai.IntentAccumulationService;
import com.aria.conversation.infrastructure.ai.IntentMetricsRecorder;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackRepository;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackEntity;
import com.aria.conversation.interfaces.dto.SessionFeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionFeedbackAppService {

    private final SessionFeedbackRepository feedbackRepository;
    private final IntentAccumulationService  accumulationService;
    // 指标记录统一走 IntentMetricsRecorder，与 P0-A 风格一致，不直接注入 MeterRegistry
    private final IntentMetricsRecorder      metricsRecorder;

    public void submitFeedback(SessionFeedbackRequest req) {
        // 1. 持久化反馈记录
        SessionFeedbackEntity entity = SessionFeedbackEntity.builder()
                .sessionId(req.getSessionId())
                .messageId(req.getMessageId())
                .feedbackType(req.getFeedbackType().name())
                .originalQuery(req.getOriginalQuery())
                .correctIntent(req.getCorrectIntent())
                .correctAnswer(req.getCorrectAnswer())
                .build();
        feedbackRepository.save(entity);

        // 2. 指标计数（统一封装在 recorder 内）
        metricsRecorder.recordFeedback(req.getFeedbackType().name().toLowerCase());

        // 3. 按反馈类型分流处理
        switch (req.getFeedbackType()) {
            case WRONG_INTENT -> handleWrongIntent(entity, req);
            case WRONG_ANSWER -> handleWrongAnswer(entity, req);
            case GOOD         -> { /* 仅计数，无后续写入 */ }
        }
    }

    /**
     * 意图纠错：将正确意图 + 原始 query 写入 intent_example_vectors。
     * 复用 IntentAccumulationService.manualAccumulate()（人工确认，autoConfirmed=false）。
     */
    private void handleWrongIntent(SessionFeedbackEntity entity, SessionFeedbackRequest req) {
        if (req.getCorrectIntent() == null || req.getCorrectIntent().isBlank()) {
            log.warn("[Feedback] WRONG_INTENT 但 correctIntent 为空，session={}", req.getSessionId());
            return;
        }
        accumulationService.manualAccumulate(req.getCorrectIntent(), req.getOriginalQuery(),
                () -> feedbackRepository.markAccumulated(entity.getId()));
    }

    /**
     * 回答纠错：将正确答案推入 KB 审核队列（占位实现，P1 迭代补全）。
     * 当前版本仅打 INFO 日志，kb_queued 维持 false，等 KB 审核队列接口就绪后补全。
     */
    private void handleWrongAnswer(SessionFeedbackEntity entity, SessionFeedbackRequest req) {
        log.info("[Feedback] WRONG_ANSWER 待入 KB 队列 session={} answer={}",
                req.getSessionId(), req.getCorrectAnswer());
        // TODO P1：调用 KnowledgeReviewQueueService.enqueue(req.getCorrectAnswer())
    }
}
```

---

## 改造：IntentAccumulationService — 新增 manualAccumulate

在已有 `asyncAccumulate()` 同类中追加：

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/ai/IntentAccumulationService.java

/**
 * 人工确认样本积累入口。由坐席纠错反馈触发，autoConfirmed = false。
 *
 * @param intentCode   坐席确认的正确意图 code
 * @param messageText  用户原始消息
 * @param onSuccess    写入成功后的回调（用于更新反馈记录的 accumulated 标记）
 */
@Async("intentAccumulateExecutor")
public void manualAccumulate(String intentCode, String messageText, Runnable onSuccess) {
    try {
        float[] embedding = embeddingService.encode(messageText);
        boolean saved = exampleVectorRepo.saveIfAbsent(intentCode, messageText, embedding, false);

        if (saved) {
            // 标记原型需要重建，但不在此处立即 rebuild。
            // rebuild() 会遍历全部域/意图、清空 Caffeine 并重算所有原型向量，
            // 对单条人工纠错来说代价过高；坐席批量纠错时会触发多次全量重建，
            // 且每次清缓存反而让 Tier2 在重建窗口内命中率下降。
            // 改为发布"脏标记"事件，由防抖调度器合并处理（见下方 PrototypeRebuildDebouncer）。
            prototypeRebuildDebouncer.markDirty();

            meterRegistry.counter("intent.example.accumulate.total",
                    "intent_code", intentCode,
                    "source", "manual").increment();

            log.info("[ManualAccumulate] 写入成功 intent={} text={}", intentCode, messageText);
        } else {
            log.debug("[ManualAccumulate] 已存在，跳过 intent={}", intentCode);
        }

        // 无论是否新写入，都标记反馈记录已处理
        if (onSuccess != null) {
            onSuccess.run();
        }
    } catch (Exception e) {
        log.warn("[ManualAccumulate] 失败 intent={} text={}", intentCode, messageText, e);
    }
}
```

**防抖重建器 `PrototypeRebuildDebouncer`（新增）：**

单条纠错只置脏标记，一个定时任务（如每 5 分钟）检查标记，有脏才触发一次 `rebuild()`，把 N 次纠错合并为一次全量重建：

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/prototype/PrototypeRebuildDebouncer.java
@Component
@RequiredArgsConstructor
public class PrototypeRebuildDebouncer {

    private final IntentPrototypeStore prototypeStore;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** 标记原型已过期，等待下一个调度窗口重建。O(1)，线程安全。 */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 每 5 分钟检查一次；仅当有脏标记时才 rebuild，随后清标记。
     * 多条纠错在同一窗口内合并为一次全量重建。
     */
    @Scheduled(fixedDelayString = "${aria.intent.prototype-rebuild-interval-ms:300000}")
    public void rebuildIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            try {
                prototypeStore.rebuild();
                log.info("[PrototypeRebuild] 防抖窗口内完成一次原型重建");
            } catch (Exception e) {
                dirty.set(true);   // 重建失败，保留脏标记等下个窗口重试
                log.warn("[PrototypeRebuild] 重建失败，将在下个窗口重试", e);
            }
        }
    }
}
```

> **多实例注意：** 集群部署时该调度会在每个 Pod 各触发一次 rebuild。因 `rebuild()` 是幂等的（重算结果写同一 Redis HASH），重复执行只是冗余而非错误；若要严格单实例执行，可复用现有 SLA 扫描器的 Redisson 分片锁模式。

**与 `asyncAccumulate` 的差异：**

| 维度 | asyncAccumulate（自动） | manualAccumulate（人工） |
|------|------------------------|------------------------|
| 触发来源 | Tier3 LLM 高置信结果 | 坐席纠错反馈 |
| `autoConfirmed` | `true` | `false` |
| 置信度门槛 | ≥ 0.95 | 无（人工即高置信） |
| rebuild 原型 | 否（不触发） | 置脏标记，防抖窗口内合并重建 |
| 回调 | 无 | `onSuccess`（更新 feedback 记录） |

---

## 新增：SessionFeedbackRepository

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/feedback/SessionFeedbackRepository.java
package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;

@Repository
public class SessionFeedbackRepository extends ServiceImpl<SessionFeedbackMapper, SessionFeedbackEntity> {

    /** 标记反馈记录已成功积累到 example_vectors */
    public void markAccumulated(Long id) {
        SessionFeedbackEntity patch = new SessionFeedbackEntity();
        patch.setId(id);
        patch.setAccumulated(true);
        updateById(patch);
    }

    /** 标记反馈记录已成功入 KB 审核队列 */
    public void markKbQueued(Long id) {
        SessionFeedbackEntity patch = new SessionFeedbackEntity();
        patch.setId(id);
        patch.setKbQueued(true);
        updateById(patch);
    }
}
```

---

## 新增：SessionFeedbackMapper

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/feedback/SessionFeedbackMapper.java
package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionFeedbackMapper extends BaseMapper<SessionFeedbackEntity> {
}
```

---

## SessionFeedbackEntity

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/feedback/SessionFeedbackEntity.java
package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
@TableName("cs_conversation.cs_session_feedback")
public class SessionFeedbackEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String messageId;
    private String feedbackType;
    private String originalQuery;
    private String correctIntent;
    private String correctAnswer;
    private Long   agentId;
    private Boolean accumulated;
    private Boolean kbQueued;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

---

## 指标说明

| 指标名 | 类型 | Tag | 含义 |
|--------|------|-----|------|
| `intent.feedback.total` | Counter | `type=wrong_intent\|wrong_answer\|good` | 各类反馈提交总量 |
| `intent.example.accumulate.total` | Counter | `intent_code=xxx, source=manual\|auto` | 样本积累量（区分人工 vs 自动） |

`source=manual` 的积累量越高，说明系统在从坐席纠错中持续学习；配合 Tier2 命中率指标（P0-A），可量化人工反馈对意图识别准确率的提升贡献。

---

## 验证步骤

1. 执行 Flyway migration，确认 `cs_session_feedback` 表创建成功
2. 调用 `POST /api/v1/sessions/feedback`，`feedbackType=WRONG_INTENT`，确认：
   - `cs_session_feedback` 有行写入
   - `intent_example_vectors` 有对应 intentCode 的新行
   - `cs_session_feedback.accumulated = TRUE`
3. 调用 `POST /api/v1/sessions/feedback`，`feedbackType=GOOD`，确认仅计数，无 DB 写入
4. 访问 `/actuator/metrics/intent.feedback.total`，确认 counter tag 正确
5. 访问 `/actuator/metrics/intent.example.accumulate.total`，确认 `source=manual` tag 存在

# P0-D：LLM Token 成本统计

## 背景与目标

LangChain4j 每次 LLM 调用返回的 `Response` 对象携带 `TokenUsage`（`inputTokenCount` / `outputTokenCount` / `totalTokenCount`），但当前代码丢弃了这部分信息，无法回答"每天消耗多少 Token、多少钱"。

本改造将 Token 消耗落库，并通过管理后台 API 提供按天/按模型的汇总查询，支撑运营决策（选模型、控成本）。

---

## 数据模型

### cs_conversation.cs_llm_cost_log 表

```sql
-- 文件：V{N+1}__add_llm_cost_log.sql
CREATE TABLE IF NOT EXISTS cs_conversation.cs_llm_cost_log
(
    id               BIGSERIAL PRIMARY KEY,
    session_id       VARCHAR(64)     NOT NULL,
    model_name       VARCHAR(128)    NOT NULL,          -- 实际调用的模型名称
    call_type        VARCHAR(32)     NOT NULL,          -- CHAT | INTENT_CLASSIFY | EMBEDDING
    input_tokens     INT             NOT NULL DEFAULT 0,
    output_tokens    INT             NOT NULL DEFAULT 0,
    total_tokens     INT             NOT NULL DEFAULT 0,
    latency_ms       INT,                               -- 本次调用耗时（ms）
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_llm_cost_log_date    ON cs_conversation.cs_llm_cost_log (DATE(created_at));
CREATE INDEX idx_llm_cost_log_model   ON cs_conversation.cs_llm_cost_log (model_name, created_at DESC);
CREATE INDEX idx_llm_cost_log_session ON cs_conversation.cs_llm_cost_log (session_id);
```

**`call_type` 枚举说明：**
- `CHAT`：用户对话 SSE 调用（`ChatAppService`）
- `INTENT_CLASSIFY`：Tier3 LLM 意图分类（`LangChain4jIntentService`）
- `EMBEDDING`：向量化调用（知识库摄入、意图样本计算）

---

## 新增：LlmCostLogEntity & Mapper

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/cost/LlmCostLogEntity.java
@Data
@Builder
@TableName("cs_conversation.cs_llm_cost_log")
public class LlmCostLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String modelName;
    private String callType;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

// Mapper 同 P0-B 模式，BaseMapper<LlmCostLogEntity>
```

---

## 新增：LlmCostLogger（写入工具类）

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        infrastructure/cost/LlmCostLogger.java
@Component
@RequiredArgsConstructor
public class LlmCostLogger {

    private final LlmCostLogMapper mapper;

    /**
     * 异步写入 Token 消耗记录，不阻塞主调用链路。
     *
     * @param sessionId  当前会话 ID（意图分类场景可传 "SYSTEM"）
     * @param modelName  实际模型标识（从 AiModelConfig 取）
     * @param callType   调用类型枚举值
     * @param usage      LangChain4j TokenUsage（可为 null，则跳过写入）
     * @param latencyMs  调用耗时
     */
    @Async("webhookExecutor")
    public void logAsync(String sessionId, String modelName,
                         String callType, TokenUsage usage, long latencyMs) {
        if (usage == null) return;
        try {
            LlmCostLogEntity entity = LlmCostLogEntity.builder()
                    .sessionId(sessionId)
                    .modelName(modelName)
                    .callType(callType)
                    .inputTokens(usage.inputTokenCount())
                    .outputTokens(usage.outputTokenCount())
                    .totalTokens(usage.totalTokenCount())
                    .latencyMs((int) latencyMs)
                    .build();
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("[LlmCostLog] 写入失败 session={} model={}", sessionId, modelName, e);
        }
    }
}
```

---

## 改造：ChatAppService — 拦截 TokenUsage

LangChain4j `StreamingChatLanguageModel` 的流式 SSE 调用在 `onComplete(Response<AiMessage>)` 回调中拿到完整 response，此时取 `TokenUsage`：

```java
// 在 onComplete 回调中（已有代码基础上追加）：
@Override
public void onComplete(Response<AiMessage> response) {
    // ---- 已有逻辑 ----
    // ... 写入消息记录、关闭 SSE ...

    // ---- 新增：Token 成本记录 ----
    llmCostLogger.logAsync(
            session.getSessionId(),
            modelConfig.getModelName(),          // AiModelConfig.getModelName()
            "CHAT",
            response.tokenUsage(),               // LangChain4j TokenUsage
            System.currentTimeMillis() - callStartMs
    );
}
```

同理在 `LangChain4jIntentService.classifyMulti()` 的同步调用出口追加：

```java
Response<AiMessage> response = chatModel.chat(messages);
llmCostLogger.logAsync(sessionId, modelConfig.getModelName(),
        "INTENT_CLASSIFY", response.tokenUsage(), elapsed);
```

> **⚠️ 流式响应 TokenUsage 可能为 null**
> 部分 OpenAI 兼容 endpoint 的流式（streaming）响应默认**不携带** token usage，`response.tokenUsage()` 会返回 `null`，`LlmCostLogger.logAsync` 已对 `null` 做 early-return（静默跳过），因此不会崩溃，但该次调用的 Token 消耗会**丢失**、统计偏低。
> 解决：在请求体中开启 `stream_options.include_usage = true`。LangChain4j 中通过 `OpenAiStreamingChatModel` 的 `.customHeaders(...)` 或对应 builder 参数开启（不同 provider 支持程度不一，天翼云 CtyunAI / 本地 Ollama 需实测）。若目标 endpoint 完全不支持 streaming usage，可退化为在同步（非流式）意图分类路径统计，或改用响应文本长度估算 Token（精度较低）。
> `INTENT_CLASSIFY` 走的是同步 `chat()` 调用，usage 通常完整，不受此限制影响；主要影响 `CHAT` 场景。

---

## 新增：Admin REST API

### 接口定义

```
GET /api/v1/admin/stats/llm-cost?period=today|7d|30d&modelName=xxx
```

**响应结构：**

```json
{
  "period": "today",
  "totalInputTokens": 182400,
  "totalOutputTokens": 54300,
  "totalTokens": 236700,
  "callCount": 1280,
  "avgTokensPerCall": 185,
  "byModel": [
    { "modelName": "gpt-4o",       "totalTokens": 198000, "callCount": 800 },
    { "modelName": "gpt-3.5-turbo","totalTokens":  38700, "callCount": 480 }
  ],
  "byCallType": [
    { "callType": "CHAT",             "totalTokens": 210000 },
    { "callType": "INTENT_CLASSIFY",  "totalTokens":  26700 }
  ],
  "dailyTrend": [
    { "date": "2026-08-01", "totalTokens": 220000 },
    { "date": "2026-08-02", "totalTokens": 195000 }
  ]
}
```

### Controller & AppService 骨架

```java
// GET /api/v1/admin/stats/llm-cost
@GetMapping("/stats/llm-cost")
public Result<LlmCostStatsResponse> getLlmCostStats(
        @RequestParam(defaultValue = "TODAY") StatsPeriod period,
        @RequestParam(required = false) String modelName) {
    return Result.ok(statsAppService.queryLlmCost(period, modelName));
}
```

**`period` 参数用枚举而非裸 String**，非法值由 Spring 自动返回 400，无需手动校验。P0-A / P0-B / P0-D 三个 Admin 接口共用同一枚举：

```java
// 路径：ai-conversation/conversation-service/src/main/java/com/aria/conversation/
//        interfaces/dto/StatsPeriod.java
public enum StatsPeriod {
    TODAY(1),   // 今日 00:00 至今
    D7(7),      // 近 7 天（对应查询参数 period=D7）
    D30(30);    // 近 30 天

    private final int days;
    StatsPeriod(int days) { this.days = days; }

    /** 返回统计起始时间（含），用于 SQL 的 created_at >= ? 过滤 */
    public OffsetDateTime startInclusive() {
        return this == TODAY
                ? OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS)
                : OffsetDateTime.now().minusDays(days);
    }
}
```

> Spring 默认按枚举名（不区分大小写需配 `@InitBinder` 或 `WebMvcConfigurer`）绑定；建议查询参数直接用 `TODAY` / `D7` / `D30`。非法值触发 `MethodArgumentTypeMismatchException`，由现有 `GlobalExceptionHandler` 统一转 400。

底层 SQL 按 `period.startInclusive()` 转换为时间区间，对 `cs_llm_cost_log` 做 `GROUP BY model_name / call_type / DATE(created_at)` 聚合，无需额外缓存（查询量小，可接受实时计算）。

---

## 改动范围

- **新增 2 个文件：** `LlmCostLogEntity.java`、`LlmCostLogger.java`
- **新增 1 个 Mapper：** `LlmCostLogMapper.java`
- **修改 2 个文件：** `ChatAppService.java`（onComplete 回调）、`LangChain4jIntentService.java`（classifyMulti 出口）
- **新增 1 个 DB 迁移：** `V{N+1}__add_llm_cost_log.sql`
- **新增 Admin API：** `AdminStatsController` + `LlmCostStatsAppService`（各约 50 行）
- 预计工作量：**1 天**

---

# 部署验证与测试说明

## 改造汇总

| 改造项 | 所属服务 | 涉及文件 | 改动性质 |
|--------|---------|---------|---------|
| P0-A：DIT 三层命中率 Counter | conversation-service | `IntentMetricsRecorder`（新增）+ `MultiHybridIntentService` | 新 Bean + 调用替换 |
| P0-A：DIT 分层延迟 Timer | conversation-service | `IntentMetricsRecorder` | 封装在新 Bean 内 |
| P0-A：Admin 命中率查询 API | conversation-service | `AdminStatsController` + `IntentStatsAppService`（新增） | 新接口 |
| P0-B：RAG 检索 score 记录 | conversation-service | `ChatAppService` + `FaqChatAppService` + `RagQualityRecorder`（新增） | 新表 + 异步写入 |
| P0-B：RAG miss_log 表 | conversation-service DB | `V{N}__add_rag_miss_log.sql` | 新建表 |
| P0-B：Admin miss 查询 API | conversation-service | `AdminStatsController`（复用） | 新接口 |
| P0-C：坐席反馈接口 | conversation-service | `SessionFeedbackController` + `AppService`（新增） | 新接口 |
| P0-C：人工样本积累 | conversation-service | `IntentAccumulationService.manualAccumulate()` | 新增方法 |
| P0-C：feedback 表 | conversation-service DB | `V{N+1}__add_session_feedback.sql` | 新建表 |
| P0-D：Token 成本日志 | conversation-service | `LlmCostLogger`（新增）+ `ChatAppService`、`LangChain4jIntentService` | 新 Bean + 2 处调用 |
| P0-D：llm_cost_log 表 | conversation-service DB | `V{N+2}__add_llm_cost_log.sql` | 新建表 |
| P0-D：Admin 成本查询 API | conversation-service | `AdminStatsController`（复用） | 新接口 |

---

## Flyway 迁移文件执行顺序

### conversation-service

```
ai-conversation/conversation-service/src/main/resources/db/migration/
  V{N}__add_session_feedback.sql      ← P0-C 新增
  V{N+1}__add_llm_cost_log.sql        ← P0-D 新增
```

### knowledge-service

```
ai-knowledge/knowledge-service/src/main/resources/db/migration/
  V{N}__add_rag_miss_log.sql          ← P0-B 新增
```

**版本号 `{N}` 的确定步骤（切勿臆测，否则 Flyway 启动即报版本冲突）：**

1. 查当前最大版本号（两个服务的 schema 各查一次）：

```sql
-- conversation-service（schema cs_conversation）
SELECT version FROM cs_conversation.flyway_schema_history
ORDER BY installed_rank DESC LIMIT 1;

-- knowledge-service（schema public）
SELECT version FROM public.flyway_schema_history
ORDER BY installed_rank DESC LIMIT 1;
```

2. 新文件版本号 = 查到的最大版本号 + 1，按各自服务独立编号（conversation 与 knowledge 版本序列互不影响）。
3. 迁移文件一旦提交、被任何环境执行过，**禁止再修改内容**（Flyway checksum 会校验失败）。如需调整，追加新版本文件。
4. 若历史 checksum 因手工改动而失配，用 `flyway repair` 修复元数据表，不要删库重来。

---

## 编译与启动

P0 改造全部在已有模块内，无新模块依赖。编译命令从仓库根执行（`-am` 自动带上 common 模块）：

```bash
# conversation-service
mvn clean package -pl ai-conversation/conversation-service -am -DskipTests

# knowledge-service
mvn clean package -pl ai-knowledge/knowledge-service -am -DskipTests
```

---

## P0-A 验证：DIT 命中率指标

### 手动触发意图识别

```bash
# 发送一条能命中关键词规则的消息（应走 Tier1）
curl -s -X POST http://localhost:8082/api/v1/chat/message \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <visitor_token>" \
  -d '{"sessionId":"<sid>","content":"退款"}'
```

### 查询 Micrometer 指标

```bash
# Tier1 执行次数（含命中/未命中细分）
curl 'http://localhost:8082/actuator/metrics/intent.tier.hit.total?tag=tier:RULE&tag=hit:true'
curl 'http://localhost:8082/actuator/metrics/intent.tier.hit.total?tag=tier:RULE&tag=hit:false'

# Tier3 实际触发次数（应远小于 Tier1 执行次数）
curl 'http://localhost:8082/actuator/metrics/intent.tier.hit.total?tag=tier:LLM'

# 各层延迟分布（P50/P99 从 Timer histogram 中读取）
curl 'http://localhost:8082/actuator/metrics/intent.tier.latency?tag=tier:RULE'
curl 'http://localhost:8082/actuator/metrics/intent.tier.latency?tag=tier:LLM'
```

### Admin API 查询（落库统计，管理台可视化）

```bash
# 查询今日三层命中率汇总
curl 'http://localhost:8082/api/v1/admin/stats/intent-classification?period=today' \
  -H "Authorization: Bearer <admin_token>"

# 响应示例
# {
#   "tier1HitRate": 0.74,
#   "tier2HitRate": 0.61,
#   "tier3TriggerRate": 0.12,
#   "avgLatencyMs": { "RULE": 2, "EMBEDDING": 48, "LLM": 310 }
# }
```

### 预期结果

| 场景 | `tier` tag | 说明 |
|------|-----------|------|
| 关键词命中 | `RULE` | Tier1 拦截，不进 Tier2/Tier3 |
| 语义匹配 | `EMBEDDING` | Tier2 命中，`intent_count >= 1` |
| 长尾问题 | `LLM` | 三层均未高置信命中，兜底 Tier3 |

---

## P0-B 验证：RAG 检索质量记录

### 触发一次带 RAG 的会话

miss 日志写在 conversation-service（`ChatAppService` / `FaqChatAppService` 检索出口），因此通过正常会话触发，而非直接调 knowledge-service 内部接口：

```bash
# conversation-service（8082）：发送一条会走 RAG 的消息
curl -s -X POST http://localhost:8082/api/v1/chat/message \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <visitor_token>" \
  -d '{"sessionId":"<sid>","content":"如何申请退款"}'
```

### 查询 miss_log

```sql
-- 验证检索质量记录已写入（表在 cs_conversation schema，字段见 DDL）
SELECT query_text, top1_score, hit_count, is_miss, source, created_at
FROM cs_conversation.cs_rag_miss_log
ORDER BY created_at DESC
LIMIT 10;

-- 用一个 KB 没有覆盖的问题触发会话后，确认该行 is_miss = TRUE
SELECT query_text, top1_score, is_miss
FROM cs_conversation.cs_rag_miss_log
WHERE is_miss = TRUE
ORDER BY created_at DESC
LIMIT 5;
```

### KB 覆盖度分析（Admin API）

历史趋势与覆盖度报表通过 conversation-service 的 Admin API 聚合 `cs_rag_miss_log`（无独立聚合任务/视图）：

```bash
# 近 7 天 miss 率趋势 + miss 最多的意图
curl 'http://localhost:8082/api/v1/admin/stats/rag-quality?period=7d' \
  -H "Authorization: Bearer <admin_token>"
```

底层等价于「数据查询示例」节的 SQL（`GROUP BY DATE(created_at)` 求 miss 率、`UNNEST(intent_codes)` 求意图分布）。

### 指标端点

`rag.search.*` 系列 Micrometer 指标全部由 knowledge-service 的 `KnowledgeSearchAppService.recordSearchMetrics()` 埋点，因此统一在 8081 查询。conversation-service 侧的 `RagQualityRecorder` 只写 DB（miss_log），不打 Micrometer 指标。

```bash
# 每次检索命中 chunk 数分布（DistributionSummary）
curl 'http://localhost:8081/actuator/metrics/rag.search.hit_count'

# top1_score 分布（DistributionSummary）
curl 'http://localhost:8081/actuator/metrics/rag.search.top1_score'

# 完全未命中（0 结果）次数（Counter）
curl 'http://localhost:8081/actuator/metrics/rag.search.miss_total'

# 各来源命中占比 VECTOR / FULL_TEXT / RERANK（Counter）
curl 'http://localhost:8081/actuator/metrics/rag.search.source_total?tag=source:VECTOR'
```

---

## P0-C 验证：坐席纠错反馈

### 提交意图纠错反馈

```bash
curl -s -X POST http://localhost:8082/api/v1/sessions/feedback \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <agent_token>" \
  -d '{
    "sessionId": "<sid>",
    "feedbackType": "WRONG_INTENT",
    "originalQuery": "帮我查一下快递",
    "correctIntent": "LOGISTICS_QUERY"
  }'
```

### 验证写入结果

```sql
-- 确认反馈记录已写入
SELECT id, session_id, feedback_type, correct_intent, accumulated, created_at
FROM cs_conversation.cs_session_feedback
ORDER BY created_at DESC LIMIT 5;

-- 确认样本已写入 example_vectors
SELECT intent_code, message_text, auto_confirmed, created_at
FROM cs_conversation.intent_example_vectors
WHERE intent_code = 'LOGISTICS_QUERY'
ORDER BY created_at DESC LIMIT 5;

-- 确认 accumulated 已标记
SELECT accumulated FROM cs_conversation.cs_session_feedback
WHERE correct_intent = 'LOGISTICS_QUERY'
ORDER BY created_at DESC LIMIT 1;
```

### 指标端点

```bash
# 各类反馈提交量
curl 'http://localhost:8082/actuator/metrics/intent.feedback.total?tag=type:wrong_intent'
curl 'http://localhost:8082/actuator/metrics/intent.feedback.total?tag=type:good'

# 人工 vs 自动样本积累对比
curl 'http://localhost:8082/actuator/metrics/intent.example.accumulate.total?tag=source:manual'
curl 'http://localhost:8082/actuator/metrics/intent.example.accumulate.total?tag=source:auto'
```

---

## 回归测试清单

以下现有测试需在 P0 改造后确认不破坏：

| 测试类 | 所在模块 | 覆盖点 |
|--------|---------|--------|
| `MultiHybridIntentServiceTest` | conversation-service | Tier 级联逻辑 |
| `IntentAccumulationServiceTest` | conversation-service | 自动积累幂等性 |
| `KnowledgeSearchAppServiceTest` | knowledge-service | 混合检索返回格式 |
| `WebhookDispatcherTest` | conversation-service | 与 Feedback 接口无交叉，确认无编译冲突 |

新增测试（最小覆盖）：

```
SessionFeedbackAppServiceTest
  ├── submitFeedback_wrongIntent_shouldAccumulateSample()
  ├── submitFeedback_wrongIntent_missingCorrectIntent_shouldSkipAccumulate()
  ├── submitFeedback_good_shouldOnlyIncrementCounter()
  └── submitFeedback_wrongAnswer_shouldLogAndNotAccumulate()

RagQualityRecorderTest
  ├── logAsync_lowScore_shouldMarkIsMiss()
  ├── logAsync_emptyHits_shouldMarkIsMissWithNullTop1Score()
  └── logAsync_highScore_shouldNotMarkMiss()
```

---

## Actuator 指标与 Admin API 总览（P0 新增）

### conversation-service

| 指标 / API | tag / 参数 | 来源 | 用途 |
|-----------|-----------|------|------|
| `intent.tier.hit.total`（Counter） | `tier=RULE\|EMBEDDING\|LLM`, `hit=true\|false` | `IntentMetricsRecorder` | 各层执行次数 + 命中次数 |
| `intent.tier.latency`（Timer） | `tier` | `IntentMetricsRecorder` | 各层 P50/P99 延迟 |
| `intent.classification.total`（原有） | `tier, intent_count` | `MultiHybridIntentService` | 最终到达层（向后兼容） |
| `intent.classification.latency`（原有） | `tier` | `MultiHybridIntentService` | 全链路耗时 |
| `intent.feedback.total`（Counter） | `type=wrong_intent\|wrong_answer\|good` | `SessionFeedbackAppService` | 各类反馈提交量 |
| `intent.example.accumulate.total`（Counter） | `intent_code, source=manual\|auto` | `IntentAccumulationService` | 样本积累量 |
| `llm.token.input.total`（Counter） | `model` | `LlmCostLogger` | 输入 Token 累计 |
| `llm.token.output.total`（Counter） | `model` | `LlmCostLogger` | 输出 Token 累计 |
| `GET /api/v1/admin/stats/intent-classification` | `period` | `AdminStatsController` | 三层命中率 + 延迟报表 |
| `GET /api/v1/admin/stats/llm-cost` | `period` | `AdminStatsController` | Token 消耗 + 成本报表 |

### knowledge-service

| 指标 | tag | 来源 | 用途 |
|------|-----|------|------|
| `rag.search.hit_count`（DistributionSummary） | — | `KnowledgeSearchAppService` | 每次检索命中 chunk 数分布 |
| `rag.search.top1_score`（DistributionSummary） | — | `KnowledgeSearchAppService` | top-1 score 分布 |
| `rag.search.miss_total`（Counter） | — | `KnowledgeSearchAppService` | 完全未命中次数 |
| `rag.search.source_total`（Counter） | `source=VECTOR\|FULL_TEXT\|RERANK` | `KnowledgeSearchAppService` | 各来源命中占比 |

Micrometer 指标均可通过 `/actuator/metrics/{name}` 实时查询；Admin API 从落库数据聚合，支持历史趋势，面向管理台展示。
