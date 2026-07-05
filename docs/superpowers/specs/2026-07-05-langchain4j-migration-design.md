# LangChain4j 全面迁移设计文档

**日期：** 2026-07-05  
**状态：** 评审完成，待实施（v1.1 — 已应用代码评审修复）  
**涉及模块：** `ai-conversation/conversation-service`、`ai-knowledge/knowledge-service`、`ai-common/common-web`

---

## 1. 背景

### 1.1 当前架构

项目目前的 AI 调用层完全为手工实现：

- **LLM 调用**：`DynamicAiClient` 通过 WebClient 手动发送 SSE 请求，`OpenAiCompatibleHandler` / `AnthropicHandler` 各自解析不同协议的流式响应帧
- **意图分类**：`IntentClassifier` / `DomainIntentClassifier` 手写 JSON prompt，手动解析 LLM 返回的 JSON 字符串
- **Slot 提取**：`SlotExtractService` 手写 prompt，手动解析结果
- **工具调用**：自定义 DIT（Domain-Intent-Tool）管道，LLM 识别意图 → `SlotResolver` 填槽 → `HttpToolRunner` 调用 HTTP 端点，不使用 LLM 原生 function calling
- **Embedding**：`OpenAiEmbeddingService` 手写 RestClient 调用，仅 knowledge-service 使用

### 1.2 迁移目标

1. 将所有 AI 相关调用统一迁移到 **LangChain4j 1.1.0**
2. 使用 LangChain4j 原生 function calling 替换自定义 DIT 工具调用管道
3. **保留热切换能力**：运行时切换 LLM provider（baseUrl / apiKey / modelName），无需重启
4. **保留 HttpToolRunner**：HTTP 工具执行逻辑（slot 占位符替换、JSONPath 提取、鉴权）复用，仅改变调用入口
5. **保留 DIT 数据库结构**：ToolConfig / IntentConfig / SlotConfig / DomainConfig 等实体不变

---

## 2. 迁移范围

### 2.1 删除（替换）

| 组件 | 替换为 |
|------|--------|
| `DynamicAiClient` | `DynamicModelFactory` |
| `AiProtocolHandler` 接口 | LangChain4j `ChatLanguageModel` / `StreamingChatLanguageModel` |
| `OpenAiCompatibleHandler` | `OpenAiChatModel` / `OpenAiStreamingChatModel` |
| `AnthropicHandler` | `AnthropicChatModel` / `AnthropicStreamingChatModel` |
| `IntentClassifier`（手写 JSON 解析）| `IntentAiService`（AI Services 结构化输出）|
| `DomainIntentClassifier`（手写 JSON 解析）| 融入 `DomainAgentService` |
| `SlotExtractService`（手写 prompt）| `SlotAiService`（AI Services 结构化输出）|
| `DitPipeline`（手写 intent→slot→tool 循环）| `DomainAgentService`（AI Services + ToolProvider）|
| `ChatMessage`（自定义 record）| LangChain4j 原生 `ChatMessage` 体系 |
| `OpenAiEmbeddingService`（手写 RestClient）| `OpenAiEmbeddingModel`（LangChain4j）|

### 2.2 保留（不变）

| 组件 | 原因 |
|------|------|
| `AiModelConfigProvider` / `AiModelConfig` | 热切换配置源，`DynamicModelFactory` 依赖它 |
| `HttpToolRunner` | HTTP 工具执行逻辑完整复用，仅作为 `ToolExecutor` 实现 |
| 所有 DIT DB 实体 / Mapper | `ToolConfig` 直接转 `ToolSpecification` |
| `DomainRepository` / `PendingSlotRepository` | 数据访问层不变 |
| `KnowledgeClient` | RAG 调用与 AI 无关 |
| `ConversationHistoryRepository` | 实现 `ChatMemoryStore` 接口 |
| WebSocket / MQ / 会话管理 / 鉴权 | 与 AI 完全无关 |
| `DitManageAppService`（CRUD 部分）| 管理接口不变，仅调整缓存失效策略 |
| `RerankService`（knowledge-service）| BGE reranker 调用不经过 LangChain4j |

---

## 3. 架构设计

### 3.1 整体数据流（新）

```
用户消息
  │
  ▼
ChatController (SSE)
  │
  ▼
ChatAppService
  ├── FAQ 路径 ──────────────────────────────────────┐
  │   KnowledgeClient(RAG) + IntentAiService         │
  │   → DynamicModelFactory.getStreamingChatModel()  │
  │   → Flux<String> SSE                             │
  │                                                  │
  └── Domain 路径 ───────────────────────────────────┘
      DomainAgentService
        ├── DynamicModelFactory.getStreamingChatModel()
        ├── ToolProvider ← DomainRepository(ToolConfig)
        │     └── HttpToolRunner(执行)
        ├── SessionChatMemoryStore(会话历史)
        └── systemMessageProvider(RAG 注入)
            → Flux<String> SSE + tool_call/tool_done 事件
```

### 3.2 热切换机制

```
每次请求
  │
  ▼
DynamicModelFactory
  ├── AiModelConfigProvider.getActive() → AiModelConfig
  ├── configHash = SHA256(baseUrl + apiKey + modelName + protocol)
  ├── 命中缓存 → 返回已有 model 实例
  └── Miss → 根据 protocol 创建新实例
            ├── "openai" / "deepseek" / "moonshot" / "qianwen"
            │   → OpenAiChatModel.builder().baseUrl(...).apiKey(...)
            └── "anthropic"
                → AnthropicChatModel.builder().apiKey(...)
```

config 变化 → hash 变化 → 新建实例，旧实例自然 GC，无需重启。

---

## 4. 新增核心组件

### 4.1 `DynamicModelFactory`

**位置：** `conversation-service/infrastructure/ai/`  
**职责：** 替代 `DynamicAiClient` + `AiProtocolHandler` 体系，提供热切换的 LangChain4j model 实例

**评审修复：**
- `ConcurrentHashMap` 改为 **Caffeine** 有界缓存，防止 config 多次变更导致旧实例永不 GC 的内存泄漏
- `configHash` 使用 **SHA-256**（原文描述与实现均统一），避免阿里规范 §6.10 禁止 MD5 的问题
- `apiProtocol` 字符串字面量提取为 `AiProtocol` 常量类
- 暴露 `currentConfigHash()` 公共方法，供调用方（如 `DomainAgentService` 日志）查询当前 hash

```java
/** protocol 常量，禁止在代码中直接使用字符串字面量（阿里规范 §1.4） */
public final class AiProtocol {
    public static final String OPENAI    = "openai";
    public static final String DEEPSEEK  = "deepseek";
    public static final String MOONSHOT  = "moonshot";
    public static final String QIANWEN   = "qianwen";
    public static final String ANTHROPIC = "anthropic";
    private AiProtocol() {}
}

/** tool 名称常量 */
public final class BuiltinToolNames {
    public static final String TRANSFER_TO_AGENT = "transfer_to_agent";
    private BuiltinToolNames() {}
}

@Component
public class DynamicModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelFactory.class);

    private final AiModelConfigProvider configProvider;

    // Caffeine 有界缓存：最多 10 个模型实例（对应 10 种不同 config 组合），30 分钟未访问自动驱逐
    private final Cache<String, ChatLanguageModel> chatCache = Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();
    private final Cache<String, StreamingChatLanguageModel> streamingCache = Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();

    public ChatLanguageModel getChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        String hash = currentConfigHash(cfg);
        return chatCache.get(hash, k -> {
            log.info("Building ChatLanguageModel: protocol={}, model={}, hash={}",
                cfg.apiProtocol(), cfg.modelName(), hash);
            return buildChatModel(cfg);
        });
    }

    public StreamingChatLanguageModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        String hash = currentConfigHash(cfg);
        return streamingCache.get(hash, k -> {
            log.info("Building StreamingChatLanguageModel: protocol={}, model={}, hash={}",
                cfg.apiProtocol(), cfg.modelName(), hash);
            return buildStreamingModel(cfg);
        });
    }

    /** 当前 chat config 的 hash，供外部日志 / 诊断使用 */
    public String currentConfigHash() {
        return currentConfigHash(configProvider.getActive());
    }

    private ChatLanguageModel buildChatModel(AiModelConfig cfg) {
        return switch (cfg.apiProtocol()) {
            case AiProtocol.ANTHROPIC -> AnthropicChatModel.builder()
                    .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                    .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                    .build();
            default -> OpenAiChatModel.builder()
                    .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                    .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                    .build();
        };
    }

    // buildStreamingModel 结构同 buildChatModel，使用对应 Streaming 实现类

    private String currentConfigHash(AiModelConfig cfg) {
        // SHA-256，非加密用途，仅做 cache key（阿里规范 §6.10 禁止 MD5）
        return DigestUtils.sha256Hex(
            cfg.baseUrl() + cfg.apiKey() + cfg.modelName() + cfg.apiProtocol()
        );
    }
}
```

### 4.2 `SessionChatMemoryStore`

**位置：** `conversation-service/infrastructure/ai/`  
**职责：** 实现 LangChain4j `ChatMemoryStore`，后端为现有 `ConversationHistoryRepository`

**ACL 边界：** LangChain4j 的 `ChatMessage` 类型（`UserMessage`、`AiMessage`、`ToolExecutionResultMessage` 等）仅存在于本类内部，不向外暴露。`ConversationHistoryRepository` 继续使用项目自有的 `ConversationMessage` 领域类型，类型转换在本适配器中完成。

**`updateMessages()` 契约：** LangChain4j 每轮对话后以完整消息列表调用此方法。`historyRepo.save()` 需实现 **全量替换（upsert/replace）** 语义，而非追加，否则消息会重复。实现时需明确此约定。

```java
@Component
public class SessionChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionChatMemoryStore.class);
    private final ConversationHistoryRepository historyRepo;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 将 ConversationMessage（领域类型）转换为 LangChain4j ChatMessage（框架类型）
        // LangChain4j 类型不跨越此适配器边界
        return historyRepo.findBySessionId(memoryId.toString())
            .stream()
            .map(this::toLangChain4jMessage)
            .toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 全量替换，historyRepo.save() 必须实现 replace 而非 append 语义
        try {
            historyRepo.saveAll(memoryId.toString(), messages.stream()
                .map(this::toDomainMessage).toList());
        } catch (Exception e) {
            log.error("Failed to persist chat memory: sessionId={}", memoryId, e);
            throw e;
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        historyRepo.deleteBySessionId(memoryId.toString());
    }

    // toLangChain4jMessage / toDomainMessage：
    // UserMessage ↔ ConversationMessage(role=USER)
    // AiMessage ↔ ConversationMessage(role=ASSISTANT)
    // ToolExecutionResultMessage ↔ ConversationMessage(role=TOOL，需确认 historyRepo 支持此角色)
}
```

### 4.3 `IntentAiService` / `SlotAiService`

**位置：** `conversation-service/domain/service/`（领域服务层）  
**接口定义在领域层，LangChain4j 实现在 infrastructure 层**

> **DDD 说明：** 意图分类和 slot 提取是核心业务能力（理解用户意图），属于领域服务，不属于技术基础设施。接口定义在 domain 层，LangChain4j 实现类（`LangChain4jIntentService`、`LangChain4jSlotService`）放在 `infrastructure/ai/`，通过 Spring 依赖注入实现接口。这样 LangChain4j 依赖不污染领域层。

```java
// ─── 领域层接口（domain/service/） ───────────────────────────────────────
public interface IntentService {
    IntentType classify(String userMessage);
}

public interface SlotService {
    SlotExtractionResult extract(String context);
}

// ─── infrastructure 层 LangChain4j 实现（infrastructure/ai/） ──────────────
@Component
class LangChain4jIntentService implements IntentService {

    // AI Service 接口（LangChain4j 内部用，不对外暴露）
    private interface IntentAiProxy {
        @SystemMessage("""
            你是客服意图分类器。从以下意图中选择一个最匹配的：
            FAQ_QUERY, TRANSFER_REQUEST, COMPLAINT, CHITCHAT, OUT_OF_SCOPE, UNKNOWN
            只返回意图枚举值，不要额外解释。
            """)
        IntentType classify(@UserMessage String userMessage);
    }

    private final DynamicModelFactory modelFactory;

    @Override
    public IntentType classify(String userMessage) {
        // 每次从 DynamicModelFactory 取最新 model，保证热切换生效
        IntentAiProxy proxy = AiServices.builder(IntentAiProxy.class)
            .chatModel(modelFactory.getChatModel())
            .build();
        return proxy.classify(userMessage);
    }
}

// SlotExtractionResult record（领域层，供两层共用）
public record SlotExtractionResult(Map<String, String> slots) {}
```

### `buildProperties()` 说明

`DomainAgentService.buildProperties()` 将 `ToolConfig` 中的 `parameters`（`List<SlotConfig>`）转换为 LangChain4j `JsonObjectSchema` 的属性 Map，是 DIT→LangChain4j 的核心适配逻辑：

```java
/**
 * 将 SlotConfig 列表转为 LangChain4j JsonObjectSchema 所需的 properties Map。
 * key = slot name，value = 对应 JsonSchemaElement（通常为 STRING 类型 + description）。
 */
private Map<String, JsonSchemaElement> buildProperties(List<SlotConfig> params) {
    Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
    for (SlotConfig slot : params) {
        props.put(slot.slotName(), JsonStringSchema.builder()
            .description(slot.description())
            .build());
    }
    return props;
}
```

`SlotConfig.slotName()` 对应工具调用时 LLM 填写的参数名，`HttpToolRunner` 中的 `{slot_name}` 占位符替换逻辑依赖此名称保持一致。

### 4.4 `DomainAgentService`

**位置：** `conversation-service/application/service/`（应用层，不在 infrastructure）
**职责：** 替代 `DitPipeline` + `ToolExecutor` + `DomainIntentClassifier`，使用 LangChain4j 原生 function calling

> **设计决策（评审修复）：** `DomainAssistant` 不做缓存，每次请求新建代理对象。代理对象是轻量 JDK 动态代理，底层 `StreamingChatLanguageModel` 已在 `DynamicModelFactory` 中缓存，重建代理无连接开销。缓存代理会导致：① RAG 上下文固化为第一次请求内容；② ToolProvider 无法感知 DB 工具变更；③ 需要手动管理缓存失效。三个问题一次规避。

```java
@Component
public class DomainAgentService {

    private static final Logger log = LoggerFactory.getLogger(DomainAgentService.class);

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepo;
    private final HttpToolRunner httpToolRunner;
    private final SessionChatMemoryStore memoryStore;
    private final KnowledgeClient knowledgeClient;

    /**
     * 每次请求构建轻量代理，ToolProvider 每次调用时实时从 DB 加载，RAG 上下文按请求注入。
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.debug("streamChat start: sessionId={}, domainCode={}", sessionId, domainCode);
        DomainConfig domain = domainRepo.loadDomainConfig(domainCode);

        // 1. System prompt：per-request 构建，包含 domain 指令 + 本次 RAG 检索结果
        String systemPrompt = buildSystemPrompt(domain, userMessage);

        // 2. per-request ChatModelListener，通过构造函数传入 Sinks（避免 ThreadLocal + Reactor 线程问题）
        Sinks.Many<ChatEvent> toolEventSink = Sinks.many().unicast().onBackpressureBuffer();
        ChatModelListener toolEventListener = new ToolEventChatModelListener(toolEventSink);

        // 3. 每次请求新建代理（代价仅为 JDK Proxy 反射，model 已在 DynamicModelFactory 缓存）
        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
            .streamingChatModel(modelFactory.getStreamingChatModel())
            .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                .id(id).maxMessages(20).chatMemoryStore(memoryStore).build())
            .toolProvider(buildToolProvider(domainCode))
            .listeners(List.of(toolEventListener))
            .build();

        // 4. token 流 + tool 事件流合并后输出
        Flux<ChatEvent> tokenFlux = assistant
            .chat(sessionId, systemPrompt, userMessage)
            .map(token -> ChatEvent.data(token));

        return Flux.merge(tokenFlux, toolEventSink.asFlux())
            .doOnError(e -> log.error("streamChat error: sessionId={}", sessionId, e))
            .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage())));
    }

    /**
     * ToolProvider 每次调用时实时读取 DB，感知工具变更。
     * buildProperties() 将 SlotConfig 列表转为 LangChain4j JsonObjectSchema 属性。
     */
    private ToolProvider buildToolProvider(String domainCode) {
        return (req) -> {
            List<ToolConfig> tools = domainRepo.loadTools(domainCode);
            ToolProviderResult.Builder builder = ToolProviderResult.builder();

            for (ToolConfig tc : tools) {
                ToolSpecification spec = ToolSpecification.builder()
                    .name(tc.toolCode())
                    .description(tc.description())
                    .parameters(JsonObjectSchema.builder()
                        .properties(buildProperties(tc.parameters()))
                        .required(tc.requiredParams())
                        .build())
                    .build();

                // ToolExecutor 委托 HttpToolRunner；异常捕获后以错误文本返回，LLM 据此回复用户
                ToolExecutor executor = (toolReq, memId) -> {
                    try {
                        ToolCallResult result = httpToolRunner.run(tc, toolReq.arguments());
                        log.info("Tool executed: tool={}, sessionId/memId={}", tc.toolCode(), memId);
                        return result.output();
                    } catch (Exception e) {
                        log.error("Tool execution failed: tool={}, error={}", tc.toolCode(), e.getMessage(), e);
                        return "工具执行失败: " + e.getMessage();
                    }
                };

                builder.add(spec, executor);
            }
            return builder.build();
        };
    }
}

/**
 * AI Service 接口（流式）。
 * systemPrompt 通过 @SystemMessage 参数每次传入，避免闭包捕获导致 RAG 上下文固化。
 */
interface DomainAssistant {
    Flux<String> chat(@MemoryId String sessionId,
                      @SystemMessage String systemPrompt,
                      @UserMessage String message);
}
```

### 4.5 `LangChain4jEmbeddingService`（knowledge-service）

**位置：** `knowledge-service/infrastructure/embedding/`  
**职责：** 替代手写 `OpenAiEmbeddingService`，支持任意 OpenAI-compat 端点（infinity / Ollama / ZhipuAI）

```java
@Component
@Profile("!test")
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider configProvider;
    // configHash → EmbeddingModel 缓存，支持热切换
    private final Map<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        EmbeddingModel model = getModel();
        // 按 batchSize 分批，与现有逻辑保持一致
        Lists.partition(chunks, batchSize).forEach(batch -> {
            List<TextSegment> segments = batch.stream()
                .map(c -> TextSegment.from(c.content())).toList();
            List<Embedding> embeddings = model.embedAll(segments).content();
            // 回写 vector 到 chunk
        });
    }

    @Override
    public float[] encode(String text) {
        return getModel().embed(TextSegment.from(text)).content().vector();
    }

    private EmbeddingModel getModel() {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        return modelCache.computeIfAbsent(configHash(cfg), k ->
            OpenAiEmbeddingModel.builder()
                .baseUrl(cfg.baseUrl())
                .apiKey(cfg.apiKey())
                .modelName(cfg.modelName())
                .build());
    }
}
```

---

## 5. 依赖变更

### 5.1 根 `pom.xml` `<dependencyManagement>` 统一版本

```xml
<!-- 升级：0.36.2 → 1.1.0 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-bom</artifactId>
    <version>1.1.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

### 5.2 `conversation-service/pom.xml` 新增

```xml
<!-- LangChain4j 核心 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<!-- OpenAI / OpenAI-compat 协议 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
<!-- Anthropic Claude -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
</dependency>
<!-- Flux<String> 流式输出 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-reactor</artifactId>
</dependency>
<!-- DynamicModelFactory model 缓存，防内存泄漏 -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <!-- Spring Boot BOM 已管理版本，无需显式声明 version -->
</dependency>
```

不使用 Spring Boot auto-config starter，因为需要 `DynamicModelFactory` 手动管理 bean 生命周期（热切换需要）。

### 5.3 `knowledge-service/pom.xml` 调整

```xml
<!-- 原有 langchain4j-spring-boot-starter 替换为拆分依赖 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
```

---

## 6. SSE 事件协议兼容性

现有前端依赖以下 SSE 事件类型，迁移后保持不变：

| 事件类型 | 来源（新）|
|---------|----------|
| `data` | `Flux<String>` token 回调 |
| `sources` | RAG 检索结果，`systemMessageProvider` 调用时注入 |
| `transfer` | LLM 调用内置 `transfer_to_agent` tool 时（由 `ChatModelListener` 拦截触发）|
| `tool_call` | `ChatModelListener.onRequest` 检测到工具调用时 |
| `tool_done` | `ChatModelListener.onResponse` 工具执行完毕时 |
| `slot_ask` | 工具参数缺失时，LLM 自然语言追问（不再需要 `PendingSlotRepository`）|
| `error` | 异常统一处理 |

`tool_call` / `tool_done` 事件通过以下机制注入 SSE 流：

1. `DomainAgentService.streamChat()` 调用前创建 `Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer()`
2. 将 `sink` 绑定到 `ChatModelListener`（通过 ThreadLocal 或显式传参），监听 `onRequest` / `onResponse` 回调
3. `ChatModelListener` 检测到 `ToolExecutionRequest` 时向 `sink` emit `tool_call` 事件；工具执行完毕后 emit `tool_done` 事件
4. `DomainAssistant.chat()` 返回的 `Flux<String>` token 流与 `eventSink.asFlux()` 通过 `Flux.merge()` 合并后输出到 SSE

> 注：LangChain4j 1.1.0 的 `ChatModelListener` 在工具调用阶段会触发 `onRequest`（携带 `ToolExecutionRequest` 列表），工具全部执行后触发第二次 `onRequest`（携带工具结果的 `ToolExecutionResultMessage`）。两次回调对应 `tool_call` → `tool_done` 事件对。

---

## 7. 数据迁移

无数据库结构变更。`DIT` 相关表（`dit_domain`、`dit_intent`、`dit_slot`、`dit_tool`、`dit_intent_tool`）保持原样，`ToolConfig` 对象在运行时转换为 `ToolSpecification`。

### `PendingSlotRepository` 废弃前提条件

**不能假设可以直接废弃。** `PendingSlotRepository`（Redis）当前处理多轮 slot 收集过程中用户断线重连的场景：系统将半填充的 slot 状态持久化到 Redis，用户重连后可继续上次进度。

LangChain4j 原生工具调用依赖 LLM 在同一次会话的 `ChatMemory` 中追问缺失参数，但如果用户断线，`ChatMemory` 中的追问记录依然存在（通过 `SessionChatMemoryStore` 持久化），LLM 重连后会继续追问——这与 `PendingSlotRepository` 的作用是等价的。

**废弃决策需在 Phase 3 完成后验证：**
1. 验证用户断线后重连，LLM 能否通过 `ChatMemory` 恢复追问上下文
2. 验证 `SessionChatMemoryStore` 能否正确持久化 `ToolExecutionResultMessage` 消息（需确认 `ConversationHistoryRepository` 支持 `TOOL` 角色）
3. 验证通过后再删除 `PendingSlotRepository` 相关代码，Phase 3 期间双路径兼容运行

---

## 8. 分阶段实施计划

### Phase 1：依赖升级 + `DynamicModelFactory`（低风险）
- 根 pom 引入 `langchain4j-bom:1.1.0`
- conversation-service 添加 `langchain4j`、`langchain4j-open-ai`、`langchain4j-anthropic`、`langchain4j-reactor`
- 实现 `DynamicModelFactory`，替换 `DynamicAiClient` 的底层 LLM 调用
- FAQ 路径 (`streamFaq`) 切到 `DynamicModelFactory`，验证流式输出正常
- 保留 `DitPipeline` 路径暂不动

### Phase 2：结构化输出 + Spike 验证（低风险）
- **Spike（先做）：** 写独立测试验证 `AiServices.builder(...).streamingChatModel(...).build()` + `Flux<String>` 返回类型在 LangChain4j 1.1.0 + Spring WebFlux 下可正常工作，再设计 Phase 3 生产代码
- 实现 `LangChain4jIntentService`（`IntentService` 接口 infrastructure 实现），替换 `IntentClassifier`
- 实现 `LangChain4jSlotService`（`SlotService` 接口 infrastructure 实现），替换 `SlotExtractService`
- 对应单元测试迁移

### Phase 3：`DomainAgentService` + `ToolProvider`（核心）
- 实现 `SessionChatMemoryStore`
- 实现 `DomainAgentService`，`ToolProvider` 桥接 `HttpToolRunner`
- `ChatModelListener` 注入 `tool_call` / `tool_done` SSE 事件
- `streamChatWithDomain` 切换至新路径
- 废弃 `DitPipeline`、`ToolExecutor`、`DomainIntentClassifier`

### Phase 4：knowledge-service Embedding（低风险）
- 实现 `LangChain4jEmbeddingService`，替换 `OpenAiEmbeddingService`
- 验证批量 embed 和单条 encode 功能

---

## 9. 风险分析

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LangChain4j 0.36.2 → 1.1.0 API 破坏性变更 | 编译失败 | knowledge-service 现有代码量少，Phase 4 独立处理 |
| LLM 原生 function calling 依赖模型支持 | 不支持 tool calling 的模型无法使用 DIT | `DomainAgentService` 降级检测：模型不支持时回退到纯 LLM 对话 |
| 热切换时 in-flight 请求影响 | 切换中的请求使用旧 model 实例 | Caffeine 缓存旧实例在 30 分钟 idle 后自动驱逐；旧实例完成请求后 GC，无需强制中断 |
| `Flux<String>` + LangChain4j-reactor 集成兼容性 | Phase 3 流式输出不可用 | Phase 2 先做 Spike 验证，确认后再实施 Phase 3 |
| `SessionChatMemoryStore` 对 `TOOL` 角色支持 | 工具调用历史丢失，多轮对话断链 | Phase 3 前确认 `ConversationHistoryRepository` 支持 `ToolExecutionResultMessage` 序列化 |
| `PendingSlotRepository` 废弃安全性 | 断线重连后 slot 上下文丢失 | Phase 3 完成后专项验证断线重连场景，验证通过再删除；未验证前双路径兼容 |
| `ChatModelListener` 与 Sinks 线程安全 | tool 事件乱序或丢失 | 使用 per-request 构造注入，`Sinks.Many.tryEmitNext()` 内部线程安全；`Flux.merge()` 保序 |

---

## 10. 测试策略

- **单元测试**：`DynamicModelFactory` 用 `ChatLanguageModel` mock；`IntentAiService` / `SlotAiService` 用 `OpenAiChatModel` 的 mock 模式
- **集成测试**：`DomainAgentService` 使用 `langchain4j-open-ai` 的内置 mock server 或 WireMock，验证工具调用循环
- **回归测试**：`ChatAppServiceIntentTest` 等现有测试用例迁移，适配新接口签名
- **端到端验证**：每个 Phase 完成后，在开发环境对接真实 LLM 验证流式输出和工具执行

---

*文档版本 1.0 — 待评审后进入 writing-plans 阶段*
