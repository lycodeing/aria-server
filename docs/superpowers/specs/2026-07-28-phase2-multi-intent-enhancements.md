# Phase 2 — intentCodes 注入 Agent System Prompt 技术方案

## 1. 背景与问题

### 1.1 现状

`DomainAgentService.streamChat(sessionId, domainCode, userMessage, intentCodes)` 
是 Phase 1 新增的 4 参数重载，`intentCodes` 目前只打了日志，**被直接丢弃**：

```java
public Flux<ChatEvent> streamChat(String sessionId, String domainCode,
                                  String userMessage, List<String> intentCodes) {
    log.debug("[DomainAgent] multi-intent codes={} sessionId={}", intentCodes, sessionId);
    return streamChat(sessionId, domainCode, userMessage);  // intentCodes 完全忽略
}
```

### 1.2 问题影响

用户发送"帮我查物流，顺便取消这个订单"时：
- 多意图识别层已正确识别出 `["query_logistics", "cancel_order"]`
- 这两个 intentCode 被传入 DomainAgent，但 LLM 的 System Prompt 里没有任何提示
- LLM 只能靠"猜"来决定回答几个问题，极可能只回答了第一个意图
- **根本原因**：System Prompt 不感知多意图语境，LLM 视角下这和单意图消息没有区别

---

## 2. 优点分析

✅ **提升多意图回复完整性**：LLM 明确知道"这条消息包含 N 个意图，请逐一回答"，不会遗漏

✅ **零新基础设施**：只改 `DomainAgentService` 内部逻辑，不新增任何依赖或数据库表

✅ **System Prompt 已有 addon 扩展点**：`SystemPromptBuilder.build(hits, addon, base)` 的 `addon` 参数天然支持追加意图提示，修改集中在一处

✅ **意图粒度可控**：仅注入 intentCode 列表和简短指令，不引入复杂逻辑，Token 增量极小（通常 < 50 tokens）

✅ **向后兼容**：无意图版本 `streamChat(sessionId, domainCode, userMessage)` 完全不变

---

## 3. 方案设计

### 3.1 整体流程

```
ChatAppService
    │
    │ streamChat(s, domain, msg, ["query_logistics", "cancel_order"])
    ▼
DomainAgentService (4参数重载)
    │
    ├── buildIntentAddon(intentCodes)
    │       "当前用户消息包含以下意图：\n- query_logistics（查询物流）\n- cancel_order（取消订单）\n请逐一完整回答，不要遗漏任何一个。"
    │
    ├── knowledgeServiceClient.search(userMessage)  → hits
    │
    ├── buildDomainAddon(allDomains)  → domainAddon
    │
    ├── combined addon = domainAddon + "\n\n" + intentAddon
    │
    └── SystemPromptBuilder.build(hits, combinedAddon, null)
            │
            ▼
        LLM SystemPrompt（含意图感知指令）
```

### 3.2 核心代码变更

**`DomainAgentService.java` — 4 参数重载改造：**

```java
/**
 * 多意图重载：携带 intentCodes，注入 System Prompt 指引 LLM 逐一回答所有意图。
 *
 * <p><b>优点：</b>LLM 明确感知多意图语境，不依赖 LLM 自行"猜测"消息含义。
 *
 * @param intentCodes 当前消息识别到的所有意图 code 列表（如 ["query_logistics", "cancel_order"]）
 */
public Flux<ChatEvent> streamChat(String sessionId, String domainCode,
                                  String userMessage, List<String> intentCodes) {
    log.debug("[DomainAgent] multi-intent codes={} sessionId={}", intentCodes, sessionId);

    // intentCodes 非空时注入意图感知 System Prompt
    if (intentCodes == null || intentCodes.isEmpty()) {
        return streamChat(sessionId, domainCode, userMessage);
    }
    return streamChatWithIntentAware(sessionId, domainCode, userMessage, intentCodes);
}

/**
 * 携带意图感知 System Prompt 的内部实现。
 * 与 streamChat(3参数) 的唯一区别：System Prompt 追加了意图提示块。
 */
private Flux<ChatEvent> streamChatWithIntentAware(String sessionId, String domainCode,
                                                    String userMessage,
                                                    List<String> intentCodes) {
    log.info("[DomainAgent] start (multi-intent) sessionId={} domain={} intentCount={}",
            sessionId, domainCode, intentCodes.size());

    List<DomainSummary> allDomains = domainRepo.findAllEnabledSummary().stream()
            .map(d -> new DomainSummary(d.getCode(), d.getDescription()))
            .toList();

    List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);

    // 关键变更：combined addon = 域列表 + 意图提示
    String domainAddon = buildDomainAddon(allDomains);
    String intentAddon = buildIntentAddon(intentCodes, domainCode);
    String combinedAddon = (domainAddon != null ? domainAddon + "\n\n" : "") + intentAddon;

    String systemPrompt = SystemPromptBuilder.build(hits, combinedAddon, null);

    // 以下与 streamChat(3参数) 完全相同
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
                log.info("[DomainAgent] done (multi-intent) sessionId={} signal={}", sessionId, signal);
                eventSink.tryEmitComplete();
            });

    return Flux.merge(tokenFlux, eventSink.asFlux())
            .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
            .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)));
}
```

**`buildIntentAddon()` 新增私有方法：**

```java
/**
 * 根据意图 code 列表构建意图感知提示块。
 *
 * <p>尝试从域配置中加载意图名称（用于更友好的提示），
 * 无法加载时降级为直接使用 intentCode 字符串。
 *
 * @param intentCodes 当前消息的所有意图 code 列表
 * @param domainCode  当前域，用于查找意图名称描述
 * @return 意图感知提示块文本
 */
private String buildIntentAddon(List<String> intentCodes, String domainCode) {
    if (intentCodes == null || intentCodes.isEmpty()) {
        return "";
    }

    // 尝试加载域配置获取意图名称（优化 LLM 理解，非强依赖）
    Map<String, String> intentNameMap = domainRepo.findByCode(domainCode)
            .map(dc -> dc.intents().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ic -> ic.code().toLowerCase(),
                            ic -> ic.name() != null ? ic.name() : ic.code(),
                            (a, b) -> a)))
            .orElse(Map.of());

    StringBuilder sb = new StringBuilder("当前用户消息包含以下意图，请逐一完整回答，不要遗漏任何一个：\n");
    for (String code : intentCodes) {
        String name = intentNameMap.getOrDefault(code.toLowerCase(), code);
        sb.append("- ").append(name).append("\n");
    }
    sb.append("请确保每个意图都有对应的回答。");
    return sb.toString();
}
```

### 3.3 System Prompt 注入效果示例

**改造前（单意图语境）：**
```
[参考资料]
- 物流单号查询方法...

---
你是一个智能客服助手...（DEFAULT_BASE_PROMPT）
```

**改造后（多意图语境，intentCodes=["query_logistics","cancel_order"]）：**
```
[参考资料]
- 物流单号查询方法...
- 订单取消流程...

---
[已切换域列表]
...

当前用户消息包含以下意图，请逐一完整回答，不要遗漏任何一个：
- 查询物流
- 取消订单
请确保每个意图都有对应的回答。

你是一个智能客服助手...（DEFAULT_BASE_PROMPT）
```

---

## 4. 数据流时序图

```
ChatAppService               DomainAgentService
     │                              │
     │ streamChat(s,domain,msg,     │
     │   ["query_logistics",        │
     │    "cancel_order"])          │
     │─────────────────────────────►│
     │                              │ buildIntentAddon(codes, domain)
     │                              │─────────────────────────────►
     │                              │◄─────────────────────────────
     │                              │ "当前消息含以下意图：\n- 查询物流\n..."
     │                              │
     │                              │ knowledgeServiceClient.search(msg)
     │                              │─────────────────────────────►
     │                              │◄───────────── hits
     │                              │
     │                              │ SystemPromptBuilder.build(
     │                              │   hits,
     │                              │   domainAddon + intentAddon,
     │                              │   null
     │                              │ )
     │                              │
     │                              │ LLM.streamChat(prompt, msg)
     │                              │──────────────────────────────►
     │◄──────────────────────────── │  token stream（含两个意图的完整回答）
```

---

## 5. 配置与灰度

无新增配置项。`intentCodes` 为空列表时自动退化为原有 3 参数行为，完全向后兼容。

---

## 6. 测试要点

| 测试场景 | 验证点 |
|---------|-------|
| intentCodes 非空 | System Prompt 中包含意图提示文字 |
| intentCodes 为空列表 | 降级为 3 参数行为，System Prompt 无意图提示 |
| intentCodes 包含未在域配置中定义的 code | 降级为显示原始 intentCode 字符串，不抛异常 |
| 单意图 intentCodes（只有 1 个）| System Prompt 包含提示但只有一行 |
| LLM 实际输出 | 验证包含两个意图的完整回答（集成测试） |

# Phase 2 — 高置信度自动积累技术方案

## 1. 背景与问题

### 1.1 现状

Tier3 LLM 返回高置信度结果后，这些宝贵的"真实用户意图样本"直接被丢弃：

```java
// MultiHybridIntentService.doClassify() Tier3 段
if (shouldFallbackToLlm(merged, cfg)) {
    try {
        List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage);
        llmResults.forEach(r -> merged.merge(r.intentCode(), r, ...));
        // ↑ 高置信度结果在这里丢失，没有任何持久化
    } catch (Exception e) { ... }
}
```

### 1.2 问题影响

这直接导致"数据飞轮"无法运转：
- 长尾意图积累不了历史案例 → 动态 RAG 注入无材料可用
- 系统运行时间越长，长尾意图的 LLM 识别质量本应越来越好，但实际上始终停留在初始状态

```
理论上（飞轮转起来后）：
  用户触发意图 → Tier3 识别 → 高置信度案例入库 
  → 动态 RAG 注入质量提升 → Tier3 识别更准 → 更多案例积累 → 循环

实际（未实现时）：
  用户触发意图 → Tier3 识别 → 结果直接丢弃 → 下次仍靠相同的静态示例
```

---

## 2. 优点分析

✅ **数据飞轮效应**：随系统运行时间自动积累长尾意图的真实样本，无需人工标注

✅ **自动降低长尾漏召回**：历史案例增多后动态 RAG 注入质量提升，Tier3 LLM 识别准确率提升

✅ **原子幂等**：`saveIfAbsent()` 底层用 `ON CONFLICT DO NOTHING`，并发安全，不会重复积累

✅ **完全异步不阻塞主路径**：积累操作通过 `@Async` 在独立线程执行，不影响对话延迟 P99

✅ **阈值可配置**：`autoAccumulateMinConfidence` 通过 `system_config` 动态调整，无需重启

✅ **可随时关闭**：`autoAccumulateEnabled=false` 立即停止积累，5 分钟内生效，无副作用

✅ **为 Phase 2 动态 RAG 打基础**：数据先积累起来，动态 RAG 注入才有材料

---

## 3. 方案设计

### 3.1 积累条件与流程

```
Tier3 LLM 返回 llmResults
         │
    for each IntentResult r in llmResults:
         │
    ┌────▼────────────────────────────────┐
    │  autoAccumulateEnabled = true?       │
    │  r.confidence() >= threshold?        │
    │  r.intent() != UNKNOWN?              │
    └────┬────────────────────────────────┘
         │ 全部满足
         ▼
    @Async 线程池异步执行：
         │
    embed(userMessage) → float[] queryVec
         │
    exampleVectorRepo.saveIfAbsent(
        r.intentCode(),
        userMessage,
        queryVec,
        autoConfirmed = true
    )
    // ON CONFLICT DO NOTHING，幂等
         │
    log.debug("[AutoAccumulate] 积累 intent={} confidence={}", ...)
```

### 3.2 核心代码变更

**`MultiHybridIntentService.java` — 新增字段和积累逻辑：**

```java
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
    // 新增两个字段
    private final IntentExampleVectorRepository exampleVectorRepo;  // 用于积累
    private final EmbeddingService embeddingService;                 // 用于生成积累向量

    // ... doClassify() Tier3 段改造 ...

    // Tier3 结果合并后，异步触发积累
    if (shouldFallbackToLlm(merged, cfg)) {
        try {
            reachedTier = ClassificationTierConstants.LLM;
            List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage);
            llmResults.forEach(r -> merged.merge(r.intentCode(), r,
                    (ex, nr) -> ex.confidence() >= nr.confidence() ? ex : nr));
            log.debug("[MultiHybrid] Tier3 LLM 补充后共 {} 个意图", merged.size());

            // 新增：高置信度结果异步积累（不阻塞主路径）
            autoAccumulate(llmResults, userMessage, cfg);
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
        }
    }
```

**新增 `autoAccumulate()` 方法：**

```java
/**
 * 将 Tier3 LLM 高置信度结果异步积累到历史案例库。
 *
 * <p><b>优点：</b>
 * <ul>
 *   <li>@Async 不阻塞对话主路径，不影响 P99 延迟</li>
 *   <li>saveIfAbsent() 底层 ON CONFLICT DO NOTHING，幂等，并发安全</li>
 *   <li>threshold 可通过 system_config 动态调整</li>
 * </ul>
 *
 * @param llmResults  Tier3 LLM 返回的意图列表
 * @param userMessage 原始用户消息（作为案例文本入库）
 * @param cfg         路由配置（含积累开关和阈值）
 */
@Async("prototypeRebuildExecutor")
protected void autoAccumulate(List<IntentResult> llmResults,
                               String userMessage,
                               RoutingConfig.Intent cfg) {
    if (!cfg.isAutoAccumulateEnabled()) {
        return;
    }
    double threshold = cfg.getAutoAccumulateMinConfidence();

    for (IntentResult r : llmResults) {
        // 跳过 UNKNOWN（LLM 幻觉或无法识别，不值得积累）
        if (r.intent() == IntentType.UNKNOWN || r.confidence() < threshold) {
            continue;
        }
        try {
            float[] embedding = embeddingService.encode(userMessage);
            exampleVectorRepo.saveIfAbsent(
                    r.intentCode(), userMessage, embedding, true);
            log.debug("[AutoAccumulate] 积累案例 intentCode={} confidence={}",
                    r.intentCode(), String.format("%.3f", r.confidence()));
            // Micrometer 指标
            meterRegistry.counter("intent.example.accumulate.total",
                    "intent_code", r.intentCode(),
                    "auto_confirmed", "true").increment();
        } catch (Exception e) {
            // 积累失败不影响主流程，仅记录 warn
            log.warn("[AutoAccumulate] 积累失败 intentCode={}", r.intentCode(), e);
        }
    }
}
```

### 3.3 积累去重策略

`saveIfAbsent()` 目前用 `ON CONFLICT DO NOTHING`，但未定义唯一约束键。需要在数据库层补充：

```sql
-- 推荐：基于 (intent_code, message_text) 的哈希唯一约束，防止相同文本重复入库
ALTER TABLE cs_conversation.intent_example_vectors
    ADD CONSTRAINT uq_intent_example_text
    UNIQUE (intent_code, message_text);
```

**为什么不用向量相似度去重？**
- 向量相似度去重需要实时检索，是一次额外的 pgvector ANN 查询，增加延迟
- 文本去重更简单、更确定性，相同的 `message_text` 必然对应相同的向量
- 语义相近但文本不同的两条案例不构成"重复"，增加样本多样性反而更好

---

## 4. 积累效果与数据飞轮

```
第 1 周（初期）：
  intent_example_vectors 为空 → findSimilarByIntent() 返回空 Map
  → 动态 RAG 无材料 → Tier3 只靠静态 exampleQueries
  → 长尾意图识别率：基线水平

第 2-4 周（积累期）：
  每天通过 Tier3 高置信度案例自动积累 50-200 条
  → findSimilarByIntent() 开始返回语义相近的历史案例
  → 动态 RAG 注入质量提升 → LLM 识别准确率提升

第 2 个月+（飞轮期）：
  历史案例库足够丰富 → 越来越多案例触发 Tier2 而非 Tier3
  → Tier3 触发比率从 30%+ 下降到 10% 以内
  → 平均分类延迟大幅降低
```

---

## 5. 配置参数

| 配置项 | 默认值 | 说明 |
|--------|-------|------|
| `autoAccumulateEnabled` | `true` | 总开关，false 时立即停止积累 |
| `autoAccumulateMinConfidence` | `0.95` | 积累门槛，低于此值的结果不积累 |

通过 `system_config.routing.config` 动态下发，5 分钟内生效。

---

## 6. 数据监控

**Micrometer 指标：**

| 指标名 | 类型 | 含义 |
|-------|------|------|
| `intent.example.accumulate.total{intent_code}` | Counter | 各意图积累次数 |
| `intent.example.accumulate.total{auto_confirmed=true}` | Counter | 自动积累总次数 |

**运营 Admin API（后续可加）：**
```
GET  /admin/intent/examples?intentCode=xxx  → 查看某意图积累案例数
DELETE /admin/intent/examples/{id}          → 删除误积累案例
```

---

## 7. 测试要点

| 测试场景 | 验证点 |
|---------|-------|
| 置信度 >= 0.95 且非 UNKNOWN | `saveIfAbsent()` 被调用 |
| 置信度 < 0.95 | `saveIfAbsent()` 不被调用 |
| intent = UNKNOWN | `saveIfAbsent()` 不被调用 |
| `autoAccumulateEnabled=false` | 整个积累流程跳过 |
| `saveIfAbsent()` 抛异常 | 不传播到主路径，仅打 warn 日志 |
| 重复消息 | 幂等，数据库不重复插入（ON CONFLICT DO NOTHING） |
| 并发两个相同消息 | 原子操作，只插入一条 |

# Phase 2 — Tier3 动态 RAG 注入技术方案

## 1. 背景与问题

### 1.1 现状

`LangChain4jIntentService.buildMultiPrompt()` 目前只使用静态示例：

```java
String buildMultiPrompt(List<IntentConfig> intents) {
    // 只注入 IntentConfig.exampleQueries()（DB/YAML 静态配置）
    for (IntentConfig intent : intents) {
        List<String> examples = intent.exampleQueries();
        if (examples != null && !examples.isEmpty()) {
            sb.append("（示例：").append(String.join("、", sample)).append("）");
        }
    }
    // ↑ 没有任何向量检索，历史积累的案例完全没有被使用
}
```

类字段中没有 `EmbeddingService` 和 `IntentExampleVectorRepository`，无法生成 query 向量、无法检索历史案例。

### 1.2 问题影响

**核心矛盾：** 高置信度自动积累（Phase 2 Feature 2）辛苦积累了历史案例，动态 RAG 不接入的话这些案例永远不会被消费。

**长尾意图为什么需要动态 RAG：**
```
静态 Few-Shot：
  "理赔申请"（来自 IntentConfig.exampleQueries）
  → 对"我要申请一下理赔"语义覆盖不足
  → LLM 置信度低 → 识别不稳定

动态 Few-Shot（RAG 注入历史案例）：
  从 intent_example_vectors 检索到：
  "我要申请理赔"（0.94 置信度积累案例）
  "帮我申请一下理赔单"（0.96 置信度积累案例）
  → 语义高度相关，LLM 理解更准 → 识别稳定性大幅提升
```

---

## 2. 优点分析

✅ **长尾识别率持续提升**：历史案例越多，动态 Few-Shot 质量越高，形成正向数据飞轮

✅ **零样本成本**：不需要人工标注，高置信度自动积累的案例直接复用

✅ **语义最近邻**：每次检索都是针对当前 query 语义最相关的历史案例，比静态示例更贴近用户实际表达

✅ **热更新**：历史案例库随时更新，无需重启，下一次 LLM 调用即可使用最新案例

✅ **开关可控**：`llmRagEnabled=false` 立即退化为纯静态示例，5 分钟内生效

✅ **空库兼容**：历史案例为空时（系统初期）静默跳过，等同于当前行为，零副作用

✅ **Token 开销可控**：`llmRagTopK=2` 每个意图最多注入 2 条历史案例，通常增量 < 100 tokens

---

## 3. 方案设计

### 3.1 整体流程

```
LangChain4jIntentService.classifyMulti(userMessage)
         │
    loadSystemDomain()  → intents
         │
    buildMultiPrompt(intents, userMessage)  ← 新增 userMessage 参数
         │
    ┌────▼─────────────────────────────────────┐
    │  静态意图定义（原有逻辑）                  │
    │  "- FAQ_QUERY：知识问答（示例：查订单）"   │
    │  "- COMPLAINT：投诉（示例：...）"          │
    └────┬─────────────────────────────────────┘
         │
    llmRagEnabled = true?
    ├── NO → 直接进入 JSON 格式指令
    └── YES
         │
    queryVec = embeddingService.encode(userMessage)
         │
    historicalExamples = exampleVectorRepo
        .findSimilarByIntent(queryVec, topK=2, limit=20)
    // 返回：{"claim_apply": ["我要申请理赔", "理赔怎么办理"]}
         │
    historicalExamples.isEmpty?
    ├── YES → 直接进入 JSON 格式指令
    └── NO
         │
    注入历史案例块：
    "历史真实触发案例（供参考）：
    - claim_apply：我要申请理赔、理赔怎么办理"
         │
    JSON 格式指令
```

### 3.2 核心代码变更

**`LangChain4jIntentService.java` — 新增字段：**

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jIntentService implements IntentService, MultiIntentClassifier {

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private final RoutingConfigProvider routingConfigProvider;
    // 新增两个字段（@RequiredArgsConstructor 自动注入）
    private final EmbeddingService embeddingService;                   // 生成 query 向量
    private final IntentExampleVectorRepository exampleVectorRepo;     // 检索历史案例
```

**`classifyMulti()` 传递 userMessage 给 buildMultiPrompt：**

```java
@Override
public List<IntentResult> classifyMulti(String userMessage) {
    try {
        DomainConfig domain = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
        if (domain == null || domain.intents().isEmpty()) {
            return List.of(IntentResult.UNKNOWN);
        }
        // 改造：传入 userMessage 供动态 RAG 检索
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
```

**`buildMultiPrompt()` 改造（新增 userMessage 参数）：**

```java
/**
 * 构建多意图分类 Prompt，含动态 RAG 历史案例注入。
 *
 * <p><b>优点：</b>
 * <ul>
 *   <li>历史案例与当前 query 语义最相关，比静态示例更精准</li>
 *   <li>历史案例为空时零副作用，等同于当前行为</li>
 *   <li>llmRagEnabled=false 可随时关闭，5 分钟内生效</li>
 * </ul>
 *
 * @param intents     意图配置列表（含静态 exampleQueries）
 * @param userMessage 用户消息（用于向量检索历史案例）
 */
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

    // 1. 静态意图定义（原有逻辑）
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
    sb.append("- UNKNOWN：无法判断\n");

    // 2. 动态 RAG：检索历史案例注入（新增）
    RoutingConfig.Intent cfg = routingConfigProvider.getConfig().getIntent();
    if (cfg.isLlmRagEnabled()) {
        injectHistoricalExamples(sb, userMessage, cfg.getLlmRagTopK());
    }

    sb.append("\n只输出 JSON，不要解释。");
    return sb.toString();
}

/**
 * 将历史案例通过向量检索注入到 Prompt，供 LLM 参考。
 *
 * <p>历史案例为空时（系统初期）静默返回，不修改 sb，等同于纯静态示例行为。
 *
 * @param sb          Prompt 构建器（in-place 追加）
 * @param userMessage 当前用户消息，生成 query 向量
 * @param topK        每个意图返回的历史案例数上限
 */
private void injectHistoricalExamples(StringBuilder sb,
                                       String userMessage, int topK) {
    try {
        float[] queryVec = embeddingService.encode(userMessage);
        int limit = topK * 20;  // 候选集 = topK * 20，保证每个意图有足够候选
        Map<String, List<String>> historicalExamples =
                exampleVectorRepo.findSimilarByIntent(queryVec, topK, limit);

        if (historicalExamples.isEmpty()) {
            return;  // 历史案例为空，静默跳过，零副作用
        }

        sb.append("\n历史真实触发案例（仅供参考，以上面的意图定义为准）：\n");
        historicalExamples.forEach((code, examples) -> {
            sb.append("- ").append(code).append("：");
            sb.append(String.join("、", examples)).append("\n");
        });
    } catch (Exception e) {
        // 向量检索失败不影响主流程，降级为纯静态示例
        log.warn("[Intent] 动态 RAG 检索失败，使用静态示例. message={}", userMessage, e);
    }
}
```

---

## 4. Prompt 注入效果对比

**改造前（纯静态示例）：**
```
意图取值说明：
- FAQ_QUERY：知识问答（示例：查订单、看物流）
- COMPLAINT：投诉（示例：我要投诉）
- claim_apply：理赔申请（示例：理赔申请）  ← 长尾意图只有 1 个示例
- UNKNOWN：无法判断

只输出 JSON，不要解释。
```

**改造后（静态 + 动态 RAG）：**
```
意图取值说明：
- FAQ_QUERY：知识问答（示例：查订单、看物流）
- COMPLAINT：投诉（示例：我要投诉）
- claim_apply：理赔申请（示例：理赔申请）
- UNKNOWN：无法判断

历史真实触发案例（仅供参考，以上面的意图定义为准）：
- claim_apply：我要申请理赔、帮我提交一个理赔单  ← 动态注入，语义更贴近

只输出 JSON，不要解释。
```

---

## 5. 时序图

```
LangChain4jIntentService    EmbeddingService    IntentExampleVectorRepository    LLM
        │                       │                          │                       │
        │ classifyMulti(msg)    │                          │                       │
        │ buildMultiPrompt(     │                          │                       │
        │   intents, msg)       │                          │                       │
        │                       │                          │                       │
        │ llmRagEnabled?=true   │                          │                       │
        │ encode(msg)           │                          │                       │
        │──────────────────────►│                          │                       │
        │◄──────────────────────│                          │                       │
        │ queryVec              │                          │                       │
        │                       │                          │                       │
        │ findSimilarByIntent(  │                          │                       │
        │   queryVec, 2, 40)    │                          │                       │
        │──────────────────────────────────────────────────►                       │
        │◄─────────────────────────────────────────────────                        │
        │ {"claim_apply":["..",".."], ...}                                         │
        │                       │                          │                       │
        │ 注入历史案例到 Prompt  │                          │                       │
        │                       │                          │                       │
        │ getChatModel().chat(prompt + msg)                                        │
        │────────────────────────────────────────────────────────────────────────►│
        │◄────────────────────────────────────────────────────────────────────────│
        │ {"intents":[{"intent":"claim_apply","confidence":0.94}]}                │
```

---

## 6. 与自动积累的协同效应（完整数据飞轮）

```
第 1 天：
  历史案例库为空 → RAG 无材料 → 纯静态示例
  Tier3 LLM 识别 claim_apply confidence=0.87
  → 自动积累：入库 "我要申请理赔" (conf=0.87 < 0.95, 不积累)

第 1 周：
  运营补充几条示例 + 少量人工标注案例入库
  RAG 检索到 3 条历史案例 → Prompt 质量提升
  Tier3 LLM 识别 claim_apply confidence=0.96
  → 自动积累：入库 "帮我申请下理赔"

第 1 月：
  历史案例库 claim_apply 已有 50+ 条
  RAG 检索到高度相关案例 → LLM 识别稳定在 0.95+
  → 越来越多 claim_apply 触发 Tier2 而非 Tier3（向量相似度已超阈值）
  → 系统整体延迟下降
```

---

## 7. 配置参数

| 配置项 | 默认值 | 说明 |
|--------|-------|------|
| `llmRagEnabled` | `true` | 动态 RAG 总开关 |
| `llmRagTopK` | `2` | 每个意图注入的历史案例数 |

动态下发，5 分钟内生效。

---

## 8. 测试要点

| 测试场景 | 验证点 |
|---------|-------|
| 历史案例非空 + llmRagEnabled=true | Prompt 包含历史案例块 |
| 历史案例为空 | Prompt 不包含历史案例块，无异常 |
| llmRagEnabled=false | 跳过向量检索，Prompt 与改造前完全一致 |
| 向量检索抛异常 | 降级为静态示例，不传播异常 |
| buildMultiPrompt 向后兼容 | 无 userMessage 参数的旧签名调用方（如测试）不破坏 |

---

## 9. 改造注意事项

### 9.1 签名变更影响

`buildMultiPrompt(List<IntentConfig>)` → `buildMultiPrompt(List<IntentConfig>, String)` 是包内方法（package-private），影响范围：
- `classifyMulti()` 内部调用：更新传参
- 现有单元测试 `LangChain4jIntentServiceTest.buildPrompt_xxx`：更新调用，增加 userMessage 参数

### 9.2 循环依赖风险

`LangChain4jIntentService` 注入 `EmbeddingService`，而 `EmbeddingService` 的实现（`LangChain4jEmbeddingService`）注入 `AiModelConfigProvider`。三者均为 Spring `@Component`，无循环依赖。

### 9.3 性能影响

动态 RAG 增加了一次 Embedding + 一次 pgvector 查询。但这两步均在 Tier3 路径（已有 200-800ms LLM 调用）之前，整体延迟增量约 30-50ms，在 LLM 延迟面前完全可接受。
