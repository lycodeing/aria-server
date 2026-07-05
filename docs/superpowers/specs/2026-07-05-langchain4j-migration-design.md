# LangChain4j 全面迁移设计文档

**日期：** 2026-07-05  
**状态：** 评审完成，待实施（v2.0 — 新增域路由小模型 / 会话域切换 / ROUTER 模型类型）  
**涉及模块：** `ai-conversation/conversation-service`、`ai-knowledge/knowledge-service`、`ai-common/common-web`、`ai-auth/auth-service`

---

## 1. 背景

### 1.1 当前架构

项目目前的 AI 调用层完全为手工实现：

- **LLM 调用**：`DynamicAiClient` 通过 WebClient 手动发送 SSE 请求，`OpenAiCompatibleHandler` / `AnthropicHandler` 各自解析协议
- **意图分类**：`IntentClassifier` / `DomainIntentClassifier` 手写 JSON prompt，手动解析返回值
- **Slot 提取**：`SlotExtractService` 手写 prompt，手动解析结果
- **工具调用**：自定义 DIT（Domain-Intent-Tool）管道，LLM 识别意图 → 填槽 → `HttpToolRunner` 调用 HTTP 端点，不使用 LLM 原生 function calling
- **Embedding**：`OpenAiEmbeddingService` 手写 RestClient 调用
- **域路由**：`domainCode` 由前端入口传入，session 级固定，会话内切换域无处理
- **模型管理**：`ai_model_config` 仅支持 `CHAT` / `EMBEDDING` 两种类型

### 1.2 迁移目标

1. 将所有 AI 相关调用统一迁移到 **LangChain4j 1.1.0**
2. 使用 LangChain4j 原生 function calling 替换自定义 DIT 工具调用管道
3. **保留热切换能力**：运行时切换 LLM provider，无需重启
4. **保留 HttpToolRunner**：HTTP 工具执行逻辑复用，仅改变调用入口
5. **保留 DIT 数据库结构**：ToolConfig / IntentConfig / SlotConfig / DomainConfig 等实体不变
6. **新增域路由小模型**：引入轻量小模型（`ROUTER` 类型）专门处理会话内跨域切换，可在模型管理后台配置
7. **新增会话域切换历史**：持久化记录每次域切换，供后台查询分析

## 2. 迁移范围

### 2.1 删除（替换）

| 组件 | 替换为 |
|------|--------|
| `DynamicAiClient` | `DynamicModelFactory` |
| `AiProtocolHandler` 接口 | LangChain4j `ChatLanguageModel` / `StreamingChatLanguageModel` |
| `OpenAiCompatibleHandler` | `OpenAiChatModel` / `OpenAiStreamingChatModel` |
| `AnthropicHandler` | `AnthropicChatModel` / `AnthropicStreamingChatModel` |
| `IntentClassifier`（手写 JSON 解析，硬编码意图）| `LangChain4jIntentService`（从 `__system__` 域动态读取意图描述）|
| `DomainIntentClassifier`（手写 JSON 解析）| 融入 `DomainAgentService`（LLM 直接选工具）|
| `SlotExtractService`（手写 prompt）| `LangChain4jSlotService` |
| `DitPipeline`（手写 intent→slot→tool 循环）| `DomainAgentService`（AI Services + ToolProvider）|
| `ChatMessage`（自定义 record）| LangChain4j 原生 `ChatMessage` 体系 |
| `OpenAiEmbeddingService`（手写 RestClient）| `LangChain4jEmbeddingService` |

### 2.2 保留（不变）

| 组件 | 原因 |
|------|------|
| `AiModelConfigProvider` / `AiModelConfig` | 热切换配置源，扩展新增 `getActiveRouter()` |
| `HttpToolRunner` | HTTP 工具执行逻辑完整复用，仅作为 `ToolExecutor` 实现 |
| 所有 DIT DB 实体 / Mapper | `ToolConfig` 直接转 `ToolSpecification` |
| `DomainRepository` | 数据访问层不变，新增 `findAllEnabled()` 方法 |
| `KnowledgeClient` | RAG 调用与 AI 无关 |
| `ConversationHistoryRepository` | 实现 `ChatMemoryStore` 接口，新增 `saveAll()` |
| WebSocket / MQ / 会话管理 / 鉴权 | 与 AI 完全无关 |
| `DitManageAppService`（CRUD 部分）| 新增 `domainEmbeddingRouter.invalidate()` 调用点 |
| `RerankService`（knowledge-service）| BGE reranker 不经过 LangChain4j |

### 2.3 新增

| 组件 | 职责 |
|------|------|
| `DomainRouterService` | 用小模型（ROUTER 类型）判断当前消息是否需要切换域 |
| `SessionDomainRepository` | Redis，存储每个 session 当前激活的 domain code |
| `SessionDomainSwitchRepository` | DB，持久化会话域切换历史，供后台查询 |
| `SessionDomainSwitchDO` / `SessionDomainSwitchMapper` | 新增 DB 实体和 Mapper |
| `AiProtocol` 常量类 | 集中管理协议字符串常量 |
| `BuiltinToolNames` 常量类 | 集中管理内置工具名称常量 |
| `SwitchType` 常量类 | 集中管理域切换类型常量 |

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
  ├── FAQ 路径（无 domainCode）─────────────────────────────────┐
  │   KnowledgeClient(RAG) + LangChain4jIntentService           │
  │   → DynamicModelFactory.getStreamingChatModel()             │
  │   → Flux<ChatEvent> SSE                                     │
  │                                                             │
  └── Domain 路径（有 domainCode）──────────────────────────────┘
      │
      ▼
      SessionDomainRepository（Redis，读/写当前激活域）
      │
      ▼
      DomainRouterService（ROUTER 小模型，~50-200ms）
      ├── 当前域置信度高 → 保持当前域
      └── 检测到切换 → 写 SessionDomainRepository + 写 cs_session_domain_switch
      │
      ▼
      DomainAgentService（大模型 function calling）
        ├── DynamicModelFactory.getStreamingChatModel()
        ├── ToolProvider（实时从 DB 加载 ToolConfig → ToolSpecification）
        │     ├── 业务工具 → HttpToolRunner 执行
        │     └── 内置工具（switch_domain / transfer_to_agent）
        ├── SessionChatMemoryStore（会话历史，LangChain4j ACL 适配）
        └── per-request systemPrompt（domain 指令 + RAG 检索）
            → Flux<ChatEvent> SSE（data / tool_call / tool_done / transfer / domain_switch）
```

### 3.2 三层域路由架构

参考美团「领域识别」方案，本系统采用两层互补机制：

```
用户消息
  ↓
① DomainRouterService（ROUTER 小模型，专用轻量模型）
  优势：语义理解强，能结合上下文，无需调阈值
  延迟：~50-200ms（取决于小模型部署方式）
  处理：明确跨域（"我要查物流" 在订单域）

  ↓ 若小模型未检测到切换
② DomainAgentService 内置 switch_domain 工具（大模型兜底）
  优势：覆盖模糊跨域场景，LLM 理解语境
  延迟：含在正常对话调用中，无额外开销
  处理：话语暗示换域但不明确的场景

两者互补，小模型快速处理明确切换，大模型兜底处理细粒度场景。
```

### 3.3 会话域状态机

```
session 创建
  │ domainCode（前端传入，入口决定）
  ▼
INITIAL 记录写入 cs_session_domain_switch
  │
  ▼
DomainRouterService 每条消息判断
  ├── 保持当前域（无记录）
  └── 切换新域 → ROUTER_MODEL 记录写入 cs_session_domain_switch
                 SessionDomainRepository 更新 Redis
                 PendingSlotRepository.delete()（清除旧域残留 slot 状态）
  │
  ▼
DomainAgentService switch_domain tool 调用
  └── LLM_TOOL 记录写入 cs_session_domain_switch
```

### 3.4 模型类型体系（新）

`ai_model_config.model_type` 从两种扩展为三种，前端对应三个独立 Tab：

| 类型 | 用途 | 推荐模型 | 典型延迟 |
|------|------|---------|---------|
| `CHAT` | 对话生成（主模型）| GPT-4o / DeepSeek / Claude | 500-2000ms |
| `EMBEDDING` | 向量化（RAG）| BGE-M3 / text-embedding-3-small | 50-200ms |
| `ROUTER` | 域路由判断（轻量）| Qwen2.5-0.5B / phi3-mini | 50-200ms |

### 3.5 热切换机制

```
每次请求
  │
  ▼
DynamicModelFactory
  ├── configHash = SHA256(baseUrl + apiKey + modelName + protocol)
  ├── Caffeine 缓存命中 → 返回已有实例（maximumSize=10, expireAfterAccess=30min）
  └── Miss → 根据 protocol 构建新实例
            ├── "openai" / 兼容协议 → OpenAiChatModel / OpenAiStreamingChatModel
            └── "anthropic" → AnthropicChatModel / AnthropicStreamingChatModel

三套独立缓存：chatCache / streamingCache / routerCache
热切换触发：Redis Pub/Sub "aria:config:ai-changed" → 三套缓存同时失效
```

## 4. 核心组件设计

### 4.1 常量类

> **C1/C2 修复：** `IntentResult`、`IntentType` 移至 `domain/` 层；`DomainCodes` 常量类放在 `domain/model/`，不再放在基础设施实现类上。

```java
// ─── 领域层常量（domain/model/DomainCodes.java）──────────────────────────
package com.aria.conversation.domain.model;

/** 领域相关常量，禁止在代码中使用字符串字面量（阿里规范 §1.4）。 */
public final class DomainCodes {
    /** DIT 系统保留域 code，存储 FAQ 路径通用意图，由运营维护 */
    public static final String SYSTEM_DOMAIN = "__system__";
    private DomainCodes() {}
}

// ─── infrastructure/ai/AiProtocol.java ──────────────────────────────────
package com.aria.conversation.infrastructure.ai;

/** AI 协议标识常量。所有值统一小写，与 DB 数据保持一致（阿里规范 §1.4）。 */
public final class AiProtocol {
    public static final String OPENAI            = "openai";
    public static final String OPENAI_COMPATIBLE = "openai_compatible";  // 统一小写
    public static final String DEEPSEEK          = "deepseek";
    public static final String MOONSHOT          = "moonshot";
    public static final String QIANWEN           = "qianwen";
    public static final String ANTHROPIC         = "anthropic";
    private AiProtocol() {}
}

// infrastructure/ai/BuiltinToolNames.java
public final class BuiltinToolNames {
    public static final String TRANSFER_TO_AGENT = "transfer_to_agent";
    public static final String SWITCH_DOMAIN     = "switch_domain";
    private BuiltinToolNames() {}
}

// infrastructure/dit/domain/SwitchType.java
public final class SwitchType {
    public static final String INITIAL       = "INITIAL";       // 初始进入
    public static final String ROUTER_MODEL  = "ROUTER_MODEL";  // 小模型检测切换
    public static final String LLM_TOOL      = "LLM_TOOL";      // 大模型工具触发切换
    public static final String USER_SELECTED = "USER_SELECTED"; // 用户手动选择（预留）
    private SwitchType() {}
}
```

---

### 4.2 `DynamicModelFactory`

**位置：** `conversation-service/infrastructure/ai/`  
**职责：** 提供三类 LangChain4j model 实例（CHAT / STREAMING / ROUTER），Caffeine 有界缓存支持热切换

> **C3 修复：** `buildChatModel()` 内部不使用 `switch(apiProtocol)`，改为通过 `LlmModelBuilder` 策略列表 dispatch（详见 §11.1）。新增 LLM provider 只需新增 `@Component`，工厂完全不改。

```java
@Slf4j
@Component
public class DynamicModelFactory {

    private final AiModelConfigProvider configProvider;
    private final List<LlmModelBuilder> builders; // Spring 注入所有策略实现

    // 三套独立 Caffeine 缓存，各自按 config hash 管理
    private final Cache<String, ChatLanguageModel> chatCache = Caffeine.newBuilder()
            .maximumSize(10).expireAfterAccess(30, TimeUnit.MINUTES).build();
    private final Cache<String, StreamingChatLanguageModel> streamingCache = Caffeine.newBuilder()
            .maximumSize(10).expireAfterAccess(30, TimeUnit.MINUTES).build();
    private final Cache<String, ChatLanguageModel> routerCache = Caffeine.newBuilder()
            .maximumSize(5).expireAfterAccess(30, TimeUnit.MINUTES).build();

    public DynamicModelFactory(AiModelConfigProvider configProvider,
                                List<LlmModelBuilder> builders) {
        this.configProvider = configProvider;
        this.builders = builders;
    }

    /** CHAT 大模型（用于 FAQ、IntentService、SlotService） */
    public ChatLanguageModel getChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return chatCache.get(configHash(cfg), k -> {
            log.info("[AI] Building ChatModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    /** STREAMING 大模型（用于 DomainAgentService 流式输出） */
    public StreamingChatLanguageModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return streamingCache.get(configHash(cfg), k -> {
            log.info("[AI] Building StreamingChatModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildStreamingModel(cfg);
        });
    }

    /** ROUTER 小模型（用于 DomainRouterService 域路由判断） */
    public ChatLanguageModel getRouterModel() {
        AiModelConfig cfg = configProvider.getActiveRouter();
        return routerCache.get(configHash(cfg), k -> {
            log.info("[AI] Building RouterModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    /** 供外部日志/诊断查询当前 chat config hash */
    public String currentConfigHash() {
        return configHash(configProvider.getActive());
    }

    /** 通过 LlmModelBuilder 策略列表选择对应构建器，无 switch/if-else */
    private LlmModelBuilder resolveBuilder(AiModelConfig cfg) {
        return builders.stream()
                .filter(b -> b.supports(cfg.apiProtocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[AI] No LlmModelBuilder for protocol: " + cfg.apiProtocol()));
    }

    private String configHash(AiModelConfig cfg) {
        // SHA-256，非加密用途，仅做 cache key（阿里规范 §6.10 禁止 MD5）
        return DigestUtils.sha256Hex(cfg.baseUrl() + cfg.apiKey() + cfg.modelName() + cfg.apiProtocol());
    }
}
```

---

### 4.3 `DomainRouterService`（新）

**位置：** `conversation-service/infrastructure/dit/pipeline/`  
**职责：** 用 ROUTER 小模型判断当前消息是否需要切换域，结合最近对话历史理解上下文

**设计要点：**
- 只输出一个 domain code，`maxTokens=32`，token 消耗极少
- prompt 注入最近 2 轮历史，帮助理解"接着问"的场景
- 返回非法 domain code 时降级保持当前域，不影响主流程
- 异常全部 catch，路由失败不阻断对话

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainRouterService {

    private final DomainRepository domainRepo;
    private final DynamicModelFactory modelFactory;

    public record RouteResult(String suggestedDomain, boolean shouldSwitch) {}

    /**
     * 用小模型判断是否需要切换域。
     *
     * @param userMessage   当前用户消息
     * @param currentDomain 当前会话激活的 domain code
     * @param recentHistory 最近 4 条 LangChain4j ChatMessage（含上下文）
     */
    public RouteResult route(String userMessage, String currentDomain,
                             List<ChatMessage> recentHistory) {
        try {
            List<DomainConfig> domains = domainRepo.findAllEnabled();
            if (domains.size() <= 1) return new RouteResult(currentDomain, false);

            String prompt = buildRouterPrompt(domains, currentDomain, recentHistory, userMessage);
            String response = modelFactory.getRouterModel().chat(prompt).trim();

            boolean validCode = domains.stream().anyMatch(d -> d.code().equalsIgnoreCase(response));
            if (!validCode) {
                log.warn("[DomainRouter] 小模型返回非法域 code: {}, 降级保持 {}", response, currentDomain);
                return new RouteResult(currentDomain, false);
            }

            boolean shouldSwitch = !response.equalsIgnoreCase(currentDomain);
            if (shouldSwitch) {
                log.info("[DomainRouter] 检测到域切换: {} → {}", currentDomain, response);
            }
            return new RouteResult(response, shouldSwitch);

        } catch (Exception e) {
            log.warn("[DomainRouter] 小模型路由失败，保持当前域 domain={}", currentDomain, e);
            return new RouteResult(currentDomain, false);
        }
    }

    private String buildRouterPrompt(List<DomainConfig> domains, String currentDomain,
                                      List<ChatMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个领域路由器。根据用户最新消息，判断应该由哪个服务域处理。\n\n");
        sb.append("可用服务域：\n");
        domains.forEach(d -> sb.append("- ").append(d.code()).append("：").append(d.description()).append("\n"));

        // 注入最近 2 轮历史（4 条消息）提供上下文
        int start = Math.max(0, history.size() - 4);
        if (!history.isEmpty()) {
            sb.append("\n最近对话：\n");
            history.subList(start, history.size()).forEach(m -> {
                if (m instanceof AiMessage ai) sb.append("助手：").append(ai.text()).append("\n");
                else if (m instanceof UserMessage um) sb.append("用户：").append(um.singleText()).append("\n");
            });
        }

        sb.append("\n当前服务域：").append(currentDomain);
        sb.append("\n用户消息：").append(userMessage);
        sb.append("\n\n只输出最合适的服务域 code，不要任何解释：");
        return sb.toString();
    }
}
```

---

### 4.4 `SessionDomainRepository` + `SessionDomainSwitchRepository`（新）

**位置：** `conversation-service/infrastructure/dit/repository/`

```java
// Redis：存储每个 session 当前激活 domain（TTL 2 小时）
@Repository @RequiredArgsConstructor @Slf4j
public class SessionDomainRepository {
    private static final String KEY_PREFIX = "dit:session_domain:";
    private static final Duration TTL = Duration.ofHours(2);
    private final RedisCacheHelper cache;

    public void save(String sessionId, String domainCode) {
        cache.set(KEY_PREFIX + sessionId, domainCode, TTL);
    }
    public Optional<String> find(String sessionId) {
        return Optional.ofNullable(cache.get(KEY_PREFIX + sessionId));
    }
    public void delete(String sessionId) { cache.delete(KEY_PREFIX + sessionId); }
}

// DB：持久化切换历史，供后台查询
@Repository @RequiredArgsConstructor @Slf4j
public class SessionDomainSwitchRepository {
    private final SessionDomainSwitchMapper mapper;

    /** 记录一条域切换历史，写入失败不影响主流程 */
    public void record(String sessionId, String fromDomain, String toDomain,
                       String switchType, String triggerMessage, String reason, Long msgSeq) {
        try {
            SessionDomainSwitchDO r = new SessionDomainSwitchDO();
            r.setSessionId(sessionId); r.setFromDomain(fromDomain); r.setToDomain(toDomain);
            r.setSwitchType(switchType); r.setTriggerMessage(triggerMessage);
            r.setReason(reason); r.setMsgSeq(msgSeq);
            mapper.insert(r);
            log.info("[Domain] 记录域切换 sessionId={} {}→{} type={}", sessionId, fromDomain, toDomain, switchType);
        } catch (Exception e) {
            log.error("[Domain] 记录域切换失败 sessionId={}", sessionId, e);
        }
    }

    public List<SessionDomainSwitchDO> findHistory(String sessionId) {
        return mapper.findBySessionId(sessionId);
    }
}
```

---

### 4.5 `SessionChatMemoryStore`

**位置：** `conversation-service/infrastructure/ai/`  
**职责：** LangChain4j `ChatMemoryStore` ACL 适配器，LangChain4j 类型不跨越此边界

**关键约束：** `updateMessages()` 使用全量替换语义，`historyRepo.saveAll()` 必须实现 replace 而非 append。

```java
@Slf4j @Component @RequiredArgsConstructor
public class SessionChatMemoryStore implements ChatMemoryStore {
    private final ConversationHistoryRepository historyRepo;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return historyRepo.findAll(memoryId.toString()).stream()
                .map(this::toLangChain4jMessage).toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 全量替换，依赖 historyRepo.saveAll() 的 replace 语义
        try {
            historyRepo.saveAll(memoryId.toString(), messages.stream()
                    .map(this::toDomainMessage).toList());
        } catch (Exception e) {
            log.error("[Memory] 持久化会话历史失败 sessionId={}", memoryId, e);
            throw e;
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        historyRepo.deleteBySessionId(memoryId.toString());
    }

    // ACL 类型转换（LangChain4j 类型仅在此类内使用）
    private ChatMessage toLangChain4jMessage(ConversationMessage m) {
        return switch (m.role()) {
            case "assistant" -> AiMessage.from(m.content());
            case "tool"      -> ToolExecutionResultMessage.from(m.content(), m.content(), m.content());
            default          -> UserMessage.from(m.content());
        };
    }
    private ConversationMessage toDomainMessage(ChatMessage m) {
        if (m instanceof AiMessage ai) return new ConversationMessage("assistant", ai.text(), 0L);
        if (m instanceof ToolExecutionResultMessage tr) return new ConversationMessage("tool", tr.text(), 0L);
        if (m instanceof UserMessage um) return new ConversationMessage("user", um.singleText(), 0L);
        return new ConversationMessage("user", m.toString(), 0L);
    }
}
```

---

### 4.6 `LangChain4jIntentService` / `LangChain4jSlotService`

**位置：** 接口在 `domain/service/`，实现在 `infrastructure/ai/`（DDD 分层）

**`LangChain4jIntentService`：** 意图描述从 `__system__` 域动态读取（非硬编码），运营可在 DIT 后台修改：

```java
@Slf4j @Component @RequiredArgsConstructor
public class LangChain4jIntentService implements IntentService {

    // I8 修复：常量移至领域层 DomainCodes，不应定义在 infrastructure 实现类上
    // 使用 DomainCodes.SYSTEM_DOMAIN，保持领域概念集中管理

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepo;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            // 从 __system__ 域读取意图列表，动态构建 prompt
            List<IntentConfig> intents = domainRepo.loadIntents(DomainCodes.SYSTEM_DOMAIN);
            String systemPrompt = buildClassifyPrompt(intents);

            List<ChatMessage> messages = List.of(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text().strip();

            IntentType type = parseIntentType(response);
            return new IntentResult(type, 1.0);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }

    private String buildClassifyPrompt(List<IntentConfig> intents) {
        StringBuilder sb = new StringBuilder("你是一个用户意图分类器。分析用户输入，返回以下枚举之一，不要输出任何其他内容：\n");
        sb.append(intents.stream().map(IntentConfig::code).collect(Collectors.joining(", ")));
        sb.append("\n\n意图说明：\n");
        intents.forEach(i -> sb.append("- ").append(i.code()).append("：").append(i.description()).append("\n"));
        sb.append("\n只输出枚举值，不要解释。");
        return sb.toString();
    }

    private IntentType parseIntentType(String response) {
        try { return IntentType.valueOf(response.toUpperCase()); }
        catch (IllegalArgumentException e) {
            log.warn("[Intent] 未知意图值: {}, 降级为 UNKNOWN", response);
            return IntentType.UNKNOWN;
        }
    }
}
```

**`LangChain4jSlotService`：** slot 结构动态，沿用手写 prompt + JSON 解析，底层 LLM 调用切换到 `DynamicModelFactory`（实现略，与原 `SlotExtractService` 逻辑一致）。

---

### 4.7 `DomainAgentService`

**位置：** `conversation-service/application/service/`（应用层）  
**职责：** LangChain4j 原生 function calling 替代 DitPipeline

**设计要点：**
- `DomainAssistant` per-request 新建（避免 stale RAG / stale tools / 缓存失效问题）
- `ToolProvider` lambda 每次调用时实时从 DB 加载，感知工具变更
- `systemPrompt` 每次请求构建（含 per-request RAG 检索），通过 `@SystemMessage` 参数传入
- `ChatModelListener` per-request 构造注入 `Sinks.Many`（避免 ThreadLocal + Reactor 线程问题）
- 内置 `switch_domain` 工具触发域切换，`transfer_to_agent` 触发转人工

```java
interface DomainAssistant {
    Flux<String> chat(@MemoryId String sessionId,
                      @SystemMessage String systemPrompt,
                      @UserMessage String message);
}

// DomainAgentService.streamChat() 核心逻辑（伪代码）：
// 1. buildSystemPrompt(domain, userMessage) → per-request RAG 注入
// 2. Sinks.Many<ChatEvent> toolEventSink → per-request 构造
// 3. buildToolProvider(domainCode, toolEventSink, userMessage) → 业务工具 + switch_domain + transfer_to_agent
// 4. AiServices.builder(DomainAssistant.class)
//       .streamingChatModel(factory.getStreamingChatModel())
//       .chatMemoryProvider(...)
//       .toolProvider(toolProvider)
//       .listeners(List.of(new ToolEventListener(toolEventSink)))
//       .build()
// 5. Flux.merge(assistant.chat(...).map(ChatEvent::data), toolEventSink.asFlux())
```

**`buildProperties()` 说明：** `ToolConfig.paramSchema`（JSON 字符串）解析为 `JsonObjectSchema`，供 LangChain4j 生成工具描述给 LLM：

```java
private ToolSpecification buildToolSpec(ToolConfig tc) {
    Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
    // 解析 paramSchema JSON → properties map
    // 每个参数 → JsonStringSchema.builder().description(...).build()
    return ToolSpecification.builder()
            .name(tc.code())
            .description(tc.description())
            .parameters(JsonObjectSchema.builder().properties(props).build())
            .build();
}
```

`HttpToolRunner.execute(tc, args, sessionCtx)` 接收 `Map<String, Object>` 参数，`ToolExecutionRequest.arguments()` 返回 JSON 字符串需先反序列化。

---

### 4.8 `ChatAppService` 域路由集成

`stream()` 方法 domain 路径新增三段逻辑：

```java
if (StringUtils.isNotBlank(domainCode)) {
    // 1. 读/写 session 当前激活域（首次进入写 INITIAL 记录）
    String activeDomain = sessionDomainRepo.find(sessionId).orElseGet(() -> {
        sessionDomainRepo.save(sessionId, domainCode);
        domainSwitchRepo.record(sessionId, null, domainCode, SwitchType.INITIAL, message, null, null);
        return domainCode;
    });

    // 2. 小模型域路由判断（~50-200ms）
    List<ChatMessage> recentHistory = memoryStore.getMessages(sessionId);
    DomainRouterService.RouteResult routing = domainRouterService.route(message, activeDomain, recentHistory);
    if (routing.shouldSwitch()) {
        String newDomain = routing.suggestedDomain();
        pendingSlotRepo.delete(sessionId);   // 清除旧域残留 slot 状态
        sessionDomainRepo.save(sessionId, newDomain);
        domainSwitchRepo.record(sessionId, activeDomain, newDomain,
                SwitchType.ROUTER_MODEL, message, "小模型检测", null);
        activeDomain = newDomain;
    }

    // 3. 大模型 agent（含 switch_domain / transfer_to_agent 内置工具兜底）
    return domainAgentService.streamChat(sessionId, activeDomain, message);
}
```

---

### 4.9 `LangChain4jEmbeddingService`（knowledge-service）

**位置：** `knowledge-service/infrastructure/embedding/`  
**职责：** 替代手写 `OpenAiEmbeddingService`，支持任意 OpenAI-compat 端点

Caffeine 缓存按 config hash 管理，热切换与 CHAT model 机制相同。`embed()` 按 `EmbeddingProperties.batchSize` 分批，每批重新拉取配置，支持长任务中途热切换。

## 5. 数据库变更

### 5.1 `ai_model_config` 扩展 ROUTER 类型（auth-service）

```sql
-- docs/sql/migration-005-router-model.sql

-- 扩展 CHECK 约束，支持 ROUTER 类型
ALTER TABLE cs_auth.ai_model_config
    DROP CONSTRAINT IF EXISTS ai_model_config_model_type_check;
ALTER TABLE cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_model_type_check
    CHECK (model_type IN ('CHAT', 'EMBEDDING', 'ROUTER'));

-- 插入默认 ROUTER 模型（可在后台修改）
INSERT INTO cs_auth.ai_model_config
    (name, provider, api_protocol, model_type, base_url, api_key_enc,
     model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled)
VALUES ('Qwen2.5-0.5B (域路由)', 'Ollama', 'OPENAI_COMPATIBLE', 'ROUTER',
        'http://localhost:11434/v1', 'PLAINTEXT:none',
        'qwen2.5:0.5b', 0.0, 32, 5, true, true);
```

### 5.2 `cs_session_domain_switch` 新增表（conversation-service）

```sql
-- 会话领域切换历史
CREATE TABLE cs_conversation.cs_session_domain_switch (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(100) NOT NULL,
    from_domain     VARCHAR(64),                    -- 切换前的域，初始进入时为 NULL
    to_domain       VARCHAR(64)  NOT NULL,
    switch_type     VARCHAR(32)  NOT NULL,           -- INITIAL / ROUTER_MODEL / LLM_TOOL / USER_SELECTED
    trigger_message TEXT,                            -- 触发切换的用户消息原文
    reason          TEXT,                            -- 切换原因（小模型评分 / LLM 工具参数）
    msg_seq         BIGINT,                          -- 关联 cs_conversation_message.seq
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);

-- I3/I4 修复：索引命名规范 idx_表名_字段名；补充 COMMENT ON TABLE
CREATE INDEX idx_cs_session_domain_switch_session_id ON cs_conversation.cs_session_domain_switch(session_id);
CREATE INDEX idx_cs_session_domain_switch_created_at ON cs_conversation.cs_session_domain_switch(created_at);
COMMENT ON TABLE cs_conversation.cs_session_domain_switch
    IS '会话领域切换历史表，记录每次 session 跨域切换事件，供运营分析';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.switch_type
    IS 'INITIAL=初始进入, ROUTER_MODEL=小模型检测切换, LLM_TOOL=大模型工具触发, USER_SELECTED=用户手动选择';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.trigger_message
    IS '触发切换的用户消息，用于分析跨域切换原因';
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.msg_seq
    IS '关联 cs_conversation_message.seq，定位切换发生在哪条消息';
```

### 5.3 `__system__` 域意图初始化数据

FAQ 路径的通用意图描述存入 DIT，由运营维护，不再硬编码：

```sql
-- I7 修复：所有 INSERT 添加 ON CONFLICT DO NOTHING，保证 migration 脚本可重复执行
INSERT INTO cs_conversation.cs_domain (code, name, description, enabled)
VALUES ('__system__', '系统通用', 'FAQ 路径路由意图，系统保留域，勿删', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO cs_conversation.cs_intent (domain_id, code, name, description, enabled)
SELECT d.id, v.code, v.name, v.description, true
FROM cs_conversation.cs_domain d,
     (VALUES
        ('FAQ_QUERY',        'FAQ 问答',   '咨询产品、服务、政策等业务问题'),
        ('TRANSFER_REQUEST', '转人工',     '要求转人工客服（"我要真人"、"转客服"、"人工"）'),
        ('COMPLAINT',        '投诉',       '投诉、强烈不满（"投诉"、"要求赔偿"）'),
        ('CHITCHAT',         '闲聊',       '闲聊、问候，与业务无关'),
        ('OUT_OF_SCOPE',     '超出范围',   '与本业务完全无关的话题'),
        ('UNKNOWN',          '未知',       '无法判断意图')
     ) AS v(code, name, description)
WHERE d.code = '__system__'
ON CONFLICT (domain_id, code) DO NOTHING;
```

## 6. 依赖与接口变更

### 6.1 根 `pom.xml` — LangChain4j BOM 统一版本

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

### 6.2 `conversation-service/pom.xml` 新增

```xml
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j</artifactId></dependency>
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-open-ai</artifactId></dependency>
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-anthropic</artifactId></dependency>
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-reactor</artifactId></dependency>
<!-- Caffeine 有界缓存，Spring Boot BOM 已管理版本 -->
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
```

不使用 Spring Boot auto-config starter，`DynamicModelFactory` 手动管理 bean 生命周期。

### 6.3 `knowledge-service/pom.xml` 调整

```xml
<!-- 原 langchain4j-spring-boot-starter 替换为拆分依赖 -->
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j</artifactId></dependency>
<dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-open-ai</artifactId></dependency>
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
```

### 6.4 `AiModelConfigProvider` 接口扩展（common-web）

```java
public interface AiModelConfigProvider {
    AiModelConfig getActive();              // CHAT（已有）
    AiModelConfig getActiveEmbedding();     // EMBEDDING（已有）
    AiModelConfig getActiveRouter();        // ROUTER（新增）

    void invalidate();                      // 失效 CHAT（已有）
    void invalidateEmbedding();             // 失效 EMBEDDING（已有）
    void invalidateRouter();               // 失效 ROUTER（新增）
}
```

### 6.5 `RemoteAiModelConfigProvider` 实现扩展（common-web）

新增约 20 行，完全对称于现有 EMBEDDING 逻辑：

```java
private static final String ROUTER_CACHE_KEY = "aria:ai:model:router:active";

@Override
public AiModelConfig getActiveRouter() {
    String cached = redis.get(ROUTER_CACHE_KEY);
    if (cached != null) return parse(cached);
    AiModelConfig config = fetchFromAuthService("/internal/ai-models/active-router");
    redis.set(ROUTER_CACHE_KEY, serialize(config), Duration.ofMinutes(5));
    return config;
}

@Override
public void invalidateRouter() { redis.delete(ROUTER_CACHE_KEY); }

// 现有 aria:config:ai-changed 订阅回调中新增一行
private void onConfigChanged(String message) {
    invalidate(); invalidateEmbedding(); invalidateRouter(); // ← 新增
}
```

### 6.6 `InternalAiModelController` 新增一个端点（auth-service）

```java
// 新增，对称于现有 /active-embedding
@GetMapping("/active-router")
public AiModelConfigVO getActiveRouter() {
    return service.getActiveByType("ROUTER"); // 复用已有通用方法
}
```

`AdminAiModelController`（`GET /api/v1/admin/ai-models?modelType=`）**不需要修改**，前端新增 ROUTER Tab 直接复用。

## 7. SSE 事件协议

| 事件类型 | 来源 |
|---------|------|
| `data` | `Flux<String>` token 回调 |
| `sources` | RAG 检索结果，per-request systemPrompt 构建时注入 |
| `transfer` | LLM 调用 `transfer_to_agent` 内置工具时（`ChatModelListener` 拦截）|
| `tool_call` | LLM 发出工具调用请求时（`ChatModelListener.onResponse` 检测到 `ToolExecutionRequest`）|
| `tool_done` | 工具执行完毕后（`ToolExecutor` 执行完，结果注入对话）|
| `domain_switch` | LLM 调用 `switch_domain` 内置工具时，`ChatAppService` 捕获后切换域并重新处理 |
| `slot_ask` | 工具参数缺失时 LLM 自然语言追问（LangChain4j 自动处理，无需 `PendingSlotRepository`）|
| `error` | 异常统一兜底 |

**`tool_call` / `tool_done` 注入机制：**

```
DomainAgentService.streamChat()
  │ per-request 创建 Sinks.Many<ChatEvent> toolEventSink
  │ per-request 创建 ToolEventListener(toolEventSink)  ← 构造注入，避免 ThreadLocal
  │
  ▼
AiServices.builder().listeners(List.of(toolEventListener))
  │
  ├── LLM 返回 ToolExecutionRequest → onResponse() → sink.tryEmitNext(tool_call 事件)
  └── ToolExecutor 执行完毕 → sink.tryEmitNext(tool_done 事件)

Flux.merge(tokenFlux, toolEventSink.asFlux())  ← 合并输出到 SSE
```

## 8. 分阶段实施计划

### Phase 1：依赖升级 + `DynamicModelFactory`（低风险）
- 根 pom 引入 `langchain4j-bom:1.1.0`
- conversation-service 添加 `langchain4j`、`langchain4j-open-ai`、`langchain4j-anthropic`、`langchain4j-reactor`、`caffeine`
- 创建 `AiProtocol`、`BuiltinToolNames` 常量类
- 实现 `DynamicModelFactory`（含 `getRouterModel()`），替换 `DynamicAiClient`
- FAQ 路径切到 `DynamicModelFactory`，验证流式输出正常
- 保留 `DitPipeline` 路径暂不动

### Phase 2：ROUTER 模型类型 + `AiModelConfigProvider` 扩展（低风险）
- `ai_model_config` 扩展 CHECK 约束，插入默认 ROUTER 模型数据（migration-005）
- `AiModelConfigProvider` 接口新增 `getActiveRouter()` / `invalidateRouter()`
- `RemoteAiModelConfigProvider` 新增对称实现
- `InternalAiModelController` 新增 `/active-router` 端点
- `RemoteAiModelConfigProvider` 的 `onConfigChanged` 补充 `invalidateRouter()`
- 前端模型管理新增 ROUTER Tab

### Phase 3：结构化意图 + Spike 验证（低风险）
- 初始化 `__system__` 域意图数据（migration-005 一并执行）
- 接口定义：`IntentService`（domain 层）、`SlotService`（domain 层）
- 实现 `LangChain4jIntentService`（从 `__system__` 域动态读意图描述）
- 实现 `LangChain4jSlotService`
- **Spike：** 验证 `AiServices + Flux<String> + langchain4j-reactor` 在 Spring WebFlux 下可用
- `ChatAppService` 切换到新接口，对应测试迁移

### Phase 4：域路由 + 会话域切换历史（中风险）
- 创建 `cs_session_domain_switch` 表（migration-005 一并执行）
- 实现 `SessionDomainRepository`（Redis）
- 实现 `SessionDomainSwitchDO` / `SessionDomainSwitchMapper` / `SessionDomainSwitchRepository`
- 实现 `SwitchType` 常量类
- 实现 `DomainRouterService`（小模型路由）
- `ChatAppService.stream()` domain 路径集成三段逻辑（读域 → 小模型路由 → agent）
- 新增查询接口 `GET /api/v1/admin/sessions/{sessionId}/domain-history`
- `DitManageAppService` domain/intent CRUD 后调用 domain 相关缓存失效

### Phase 5：`DomainAgentService` + `ToolProvider`（核心，高风险）
- 实现 `SessionChatMemoryStore`，`ConversationHistoryRepository` 新增 `saveAll()` replace 语义
- 实现 `DomainAgentService`（含业务工具 + `switch_domain` + `transfer_to_agent` 内置工具）
- `ToolEventListener` 注入 `tool_call` / `tool_done` / `transfer` / `domain_switch` SSE 事件
- `ChatAppService` domain 路径切换至 `DomainAgentService`
- 删除 `DitPipeline`、`ToolExecutor`、`DomainIntentClassifier`
- `PendingSlotRepository` 废弃验证（断线重连场景），通过后删除

### Phase 6：knowledge-service Embedding（低风险，可与其他 Phase 并行）
- `knowledge-service/pom.xml` 调整依赖
- 实现 `LangChain4jEmbeddingService`，替换 `OpenAiEmbeddingService`
- 验证批量 embed 和单条 encode 功能

## 9. 风险分析

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LangChain4j 0.36.2 → 1.1.0 API 破坏性变更 | 编译失败 | Phase 6（knowledge-service）独立处理，其余模块全新引入无历史包袱 |
| ROUTER 小模型返回非法域 code | 路由错误 | `DomainRouterService` 校验返回值合法性，非法则降级保持当前域，不影响主流程 |
| 小模型延迟过高（网络/冷启动）| 整体延迟上升 | ROUTER 小模型设 5s 超时；异常 catch 后降级保持当前域；推荐本地 Ollama 部署（5-10ms）|
| LLM 原生 function calling 依赖模型支持 | 不支持 tool calling 的模型无法使用 DIT | `DomainAgentService` 降级检测：不支持时回退纯 LLM 对话 |
| 热切换时 in-flight 请求影响 | 切换中请求使用旧 model | Caffeine 旧实例 30 分钟 idle 后自动驱逐；请求完成后自然 GC |
| `Flux<String>` + langchain4j-reactor 兼容性 | Phase 5 流式输出不可用 | Phase 3 先做 Spike 验证，确认后再实施 Phase 5 |
| `SessionChatMemoryStore` 对 TOOL 角色支持 | 工具调用历史丢失 | Phase 5 前确认 `ConversationHistoryRepository` 支持 `ToolExecutionResultMessage` 序列化 |
| `PendingSlotRepository` 废弃安全性 | 断线重连后 slot 上下文丢失 | Phase 5 完成后专项验证断线重连场景，验证通过再删除 |
| 会话域切换历史写入失败 | 历史数据缺失 | `SessionDomainSwitchRepository.record()` 内部 catch 异常，写入失败不阻断主流程 |
| `__system__` 域意图数据缺失 | `LangChain4jIntentService` 无法加载意图列表 | 加载为空时降级返回 `IntentResult.UNKNOWN`，FAQ 走通用 LLM 流程 |

## 10. 测试策略

- **单元测试**：`DynamicModelFactory` 用 `ChatLanguageModel` mock 验证缓存命中/miss；`LangChain4jIntentService` 用 `ChatModelMock.thatAlwaysResponds()` 验证意图解析和降级逻辑；`DomainRouterService` mock `getRouterModel()` 验证非法 code 降级
- **集成测试**：`DomainAgentService` 用 `langchain4j-core tests` 的 `ChatModelMock` 验证工具调用循环；`SessionDomainSwitchRepository` 验证写入 + 查询
- **Spike 验证**（Phase 3 前必做）：`FluxStreamingSpike` — 验证 `AiServices + Flux<String> + langchain4j-reactor` 在真实 Spring WebFlux 环境可用
- **回归测试**：`ChatAppServiceIntentTest` 等现有测试适配新接口签名
- **端到端验证**：每个 Phase 完成后对接真实 LLM 验证流式输出、工具执行、域切换完整链路
- **域路由专项验证**（Phase 4 后）：构造跨域对话场景，验证 `DomainRouterService` 检测准确率；验证切换历史完整写入 `cs_session_domain_switch`
- **断线重连专项验证**（Phase 5 后，`PendingSlotRepository` 废弃前提）：模拟 slot 填写中途断连重连，验证 LLM 通过 `ChatMemory` 恢复追问上下文

---

## 11. 策略模式应用

参考项目已有的 `AiProtocolHandler`（`DynamicAiClient` 中的范本），对以下五处 if/switch 分支统一应用策略模式，消除开闭原则违反。

---

### 11.1 `LlmModelBuilder` — 替换 `DynamicModelFactory` 中的 protocol switch

**问题：** `buildChatModel()` / `buildStreamingModel()` 中 `switch(apiProtocol)`，新增 Gemini / Mistral 等 provider 需要改这个方法。

**策略接口：**

```java
// infrastructure/ai/LlmModelBuilder.java
public interface LlmModelBuilder {
    boolean supports(String apiProtocol);
    ChatLanguageModel buildChatModel(AiModelConfig cfg);
    StreamingChatLanguageModel buildStreamingModel(AiModelConfig cfg);
}
```

**实现：** `OpenAiModelBuilder`（兜底，处理所有 OpenAI-compat 协议）、`AnthropicModelBuilder`，均为 `@Component`，Spring 自动发现。

**`DynamicModelFactory` dispatch 变为：**

```java
private final List<LlmModelBuilder> builders; // Spring 注入全部实现

private LlmModelBuilder resolveBuilder(AiModelConfig cfg) {
    return builders.stream()
            .filter(b -> b.supports(cfg.apiProtocol()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No builder for: " + cfg.apiProtocol()));
}
```

新增 provider 只需加一个 `@Component`，`DynamicModelFactory` 完全不改。

---

### 11.2 `HttpAuthStrategy` — 替换 `HttpToolRunner.buildHeaders()` 中的 authType if/else

**问题：** `if("BEARER") / else if("API_KEY")` 链，新增 OAuth2 / HMAC 要修改 `HttpToolRunner`。

**策略接口：**

```java
// infrastructure/dit/pipeline/HttpAuthStrategy.java
public interface HttpAuthStrategy {
    String authType(); // 对应 ToolConfig.authType()
    void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper);
}
```

**实现：** `BearerAuthStrategy`、`ApiKeyAuthStrategy`、`NoAuthStrategy`，均为 `@Component`。

**`HttpToolRunner` buildHeaders() 简化：**

```java
// 构造时注入，key = authType()
private final Map<String, HttpAuthStrategy> authStrategyMap;

private HttpHeaders buildHeaders(ToolConfig tool, Map<String, Object> params) {
    HttpHeaders headers = new HttpHeaders();
    authStrategyMap.getOrDefault(tool.authType(), noAuthStrategy)
                   .apply(headers, tool.authConfig(), objectMapper);
    // 自定义 headersTemplate 逻辑不变...
    return headers;
}
```

---

### 11.3 `SlotResolutionStrategy` — 替换 `SlotResolver` 中的 strategy switch

**问题：** `switch("EXTRACT"/"SESSION"/"DISCOVER"/"ASK_USER")` 中 `DISCOVER` case 混合了 HTTP 调用、Redis 写入、多种返回路径，难以独立测试。

**策略接口与结果类型：**

```java
// infrastructure/dit/pipeline/SlotResolutionStrategy.java
public interface SlotResolutionStrategy {
    String strategyCode();
    SlotStrategyOutcome apply(SlotConfig slot, SlotResolutionContext ctx);
}

// 密封类型，三种结果
public sealed interface SlotStrategyOutcome
        permits SlotStrategyOutcome.Resolved,
                SlotStrategyOutcome.NotResolved,
                SlotStrategyOutcome.Pending {
    record Resolved(Object value)             implements SlotStrategyOutcome {}
    record NotResolved()                      implements SlotStrategyOutcome {}
    record Pending(SlotResolveResult result)  implements SlotStrategyOutcome {}
}

// 上下文对象，替代 SlotResolver.resolve() 的散参数
public record SlotResolutionContext(
    String sessionId, String domainCode, String intentCode,
    String userMessage, List<ChatMessage> recentHistory,
    Map<String, Object> sessionCtx, Map<String, Object> resolved,
    PendingSlotState existingPending
) {}
```

**四个实现：** `ExtractSlotStrategy`、`SessionSlotStrategy`、`DiscoverSlotStrategy`、`AskUserSlotStrategy`，均为 `@Component`。

**`SlotResolver` 核心循环变为：**

```java
// 无任何 switch/if-else 类型判断
for (SlotConfig slot : requiredSlots) {
    if (resolved.containsKey(slot.slotName())) continue;
    for (String code : getStrategies(slot)) {
        SlotResolutionStrategy strategy = strategyMap.get(code);
        if (strategy == null) { log.warn("未知策略: {}", code); continue; }

        switch (strategy.apply(slot, ctx)) {
            case Resolved r  -> { resolved.put(slot.slotName(), r.value()); }
            case Pending p   -> { return p.result(); }  // 立即挂起
            case NotResolved ignored -> {}              // 尝试下一个策略
        }
        if (resolved.containsKey(slot.slotName())) break;
    }
}
```

---

### 11.4 `RouteResultHandler` — 替换 `ChatAppService.buildDomainEventStream()` instanceof 链

**问题：** `buildDomainEventStream()` 约 80 行，混合路由判断、业务操作、事件构造、AI 流处理四种职责。每增加一种 `RouteResult` 子类型要修改这个方法。

**策略接口：**

```java
// application/service/route/RouteResultHandler.java
public interface RouteResultHandler {
    boolean supports(RouteResult route);
    Flux<ChatEvent> handle(String sessionId, String userMessage,
                           RouteResult route, Map<String, Object> sessionCtx);
}
```

**四个实现及各自职责：**

| 实现类 | 职责 | 处理的 RouteResult |
|--------|------|-------------------|
| `TransferRouteHandler` | 转人工入队 + 发 `transfer` 事件 | `TransferResult` |
| `PendingRouteHandler` | 追问 slot + 发 `slot_ask` / `candidates` 事件 | `PendingResult` |
| `ExecuteRouteHandler` | 执行工具 + RAG + 构建 AI 流 | `ExecuteResult` |
| `FallbackRouteHandler` | 降级通用 LLM 回答 | `FallbackResult` |

**包结构：**

```
application/service/
  ChatAppService.java          ← 薄调度，只负责路由 dispatch
  route/
    RouteResultHandler.java    ← 策略接口
    TransferRouteHandler.java
    PendingRouteHandler.java
    ExecuteRouteHandler.java   ← Phase 5 后随 DitPipeline 一起删除
    FallbackRouteHandler.java
```

**`ChatAppService.buildDomainEventStream()` 压缩为 8 行：**

```java
private final List<RouteResultHandler> routeHandlers; // Spring 注入

private Flux<ChatEvent> buildDomainEventStream(String sessionId, String userMessage,
                                                RouteResult route, Map<String, Object> sessionCtx) {
    return routeHandlers.stream()
            .filter(h -> h.supports(route))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No handler: " + route.getClass().getSimpleName()))
            .handle(sessionId, userMessage, route, sessionCtx);
}
```

> **注意：** `ExecuteRouteHandler` 在 Phase 5（`DomainAgentService` 替换 `DitPipeline`）后整体删除。其余三个 Handler 在新架构中继续保留。

---

### 11.5 `WebSocketParticipant` — 替换 `ChatWebSocketHandler` 中的 role if/else

**问题：** `if("chat") / else` 在连接建立、消息处理、断开连接、错误处理四处重复，新增 supervisor 角色需改四处。

**策略接口：**

```java
// infrastructure/websocket/WebSocketParticipant.java
public interface WebSocketParticipant {
    boolean supports(String role);
    void onConnected(WebSocketSession session, String sessionId);
    void onMessage(WebSocketSession session, String sessionId, String payload);
    void onDisconnected(WebSocketSession session, String sessionId);
    void onError(WebSocketSession session, String sessionId, Throwable error);
}
```

**实现：** `VisitorParticipant`、`AgentParticipant`，均为 `@Component`。

**`ChatWebSocketHandler` 在连接时解析一次，后续全部委托：**

```java
// afterConnectionEstablished 时解析并存入 session attributes
WebSocketParticipant participant = participants.stream()
        .filter(p -> p.supports(role)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + role));
session.getAttributes().put("participant", participant);

// 后续三个 lifecycle 方法各一行
participant.onMessage(session, sessionId, message.getPayload());
participant.onDisconnected(session, sessionId);
participant.onError(session, sessionId, exception);
```

---

### 11.6 各策略改造时机

| 策略 | 改造时机 |
|------|---------|
| `LlmModelBuilder` | Phase 1（`DynamicModelFactory` 实现时）|
| `HttpAuthStrategy` | Phase 5（`HttpToolRunner` 保留期间同步重构）|
| `SlotResolutionStrategy` | Phase 5（`DitPipeline` 重构时同步，但 Phase 5 后 SlotResolver 被 LangChain4j 取代，可提前在单独 PR 重构）|
| `RouteResultHandler` | Phase 5（`ChatAppService` 改造时，`ExecuteRouteHandler` 同步删除）|
| `WebSocketParticipant` | 独立 PR，与 LangChain4j 迁移无关 |  
*变更摘要：新增 ROUTER 模型类型 / DomainRouterService 会话域切换 / cs_session_domain_switch 历史表 / __system__ 域意图动态化 / AiModelConfigProvider 扩展 / Phase 重新分层为 6 个*
