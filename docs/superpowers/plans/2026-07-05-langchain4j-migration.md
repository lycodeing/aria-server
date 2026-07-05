# LangChain4j 全面迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 conversation-service 和 knowledge-service 中所有手写 AI 基础设施替换为 LangChain4j 1.1.0，使用原生 function calling 替代自定义 DIT pipeline。

**Architecture:** `DynamicModelFactory` 通过 Caffeine 缓存按 config hash 热切换 LangChain4j model 实例；`DomainAgentService`（应用层）使用 `ToolProvider` 将数据库中的 `ToolConfig` 动态转为 `ToolSpecification`，委托 `HttpToolRunner` 执行；`SessionChatMemoryStore` 适配现有 `ConversationHistoryRepository`。

**Tech Stack:** LangChain4j 1.1.0, langchain4j-reactor (Flux streaming), langchain4j-open-ai, langchain4j-anthropic, Caffeine cache, Spring Boot 3, Project Reactor

---

## Phase 1：依赖升级 + DynamicModelFactory

**目标：** 用 LangChain4j 1.1.0 替换 `DynamicAiClient` 底层，`ChatAppService` 的 FAQ 路径切换到新实现，DIT 路径暂不动。

**涉及文件：**
- Modify: `pom.xml`（根）
- Modify: `ai-conversation/conversation-service/pom.xml`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AiProtocol.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/DynamicModelFactoryTest.java`

---

### Task 1.1：创建 feature 分支

- [ ] **Step 1: 从 main 创建分支**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
git checkout -b feature/langchain4j-migration
```

Expected: Switched to a new branch 'feature/langchain4j-migration'

---

### Task 1.2：升级根 pom 依赖版本

- [ ] **Step 1: 修改 `pom.xml` 根文件的 `langchain4j.version` 属性**

在 `<properties>` 块中将：
```xml
<langchain4j.version>0.36.2</langchain4j.version>
```
改为：
```xml
<langchain4j.version>1.1.0</langchain4j.version>
```

- [ ] **Step 2: 在 `<dependencyManagement>` 中替换 langchain4j 单条依赖为 BOM**

将：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```
替换为：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-bom</artifactId>
    <version>${langchain4j.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: 验证根 pom 编译**

```bash
./mvnw validate -q
```
Expected: BUILD SUCCESS，无报错

---

### Task 1.3：conversation-service 添加 LangChain4j 依赖

- [ ] **Step 1: 在 `ai-conversation/conversation-service/pom.xml` 的 `<dependencies>` 中添加**

```xml
<!-- LangChain4j 核心 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<!-- OpenAI / OpenAI-compat 协议（支持 DeepSeek / Moonshot / Qianwen / 天翼云等） -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
<!-- Anthropic Claude -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
</dependency>
<!-- Flux<String> 流式输出，依赖 langchain4j-reactor -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-reactor</artifactId>
</dependency>
<!-- Caffeine 有界缓存，用于 DynamicModelFactory -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <!-- 版本由 Spring Boot BOM 管理，无需显式声明 -->
</dependency>
```

- [ ] **Step 2: 编译 conversation-service（不跑测试，只验证依赖解析）**

```bash
./mvnw compile -pl ai-conversation/conversation-service -am -q
```
Expected: BUILD SUCCESS

---

### Task 1.4：创建 `AiProtocol` 常量类

- [ ] **Step 1: 创建文件**

```java
// ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AiProtocol.java
package com.aria.conversation.infrastructure.ai;

/**
 * AI 协议标识常量。
 * 禁止在代码中使用字符串字面量标识协议（阿里规范 §1.4）。
 */
public final class AiProtocol {
    public static final String OPENAI    = "openai";
    public static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";
    public static final String DEEPSEEK  = "deepseek";
    public static final String MOONSHOT  = "moonshot";
    public static final String QIANWEN   = "qianwen";
    public static final String ANTHROPIC = "anthropic";

    private AiProtocol() {}
}
```

---

### Task 1.5：创建 `DynamicModelFactory`

`DynamicModelFactory` 暴露与 `DynamicAiClient` 相同签名的 `streamChat` / `chat` 方法，内部用 LangChain4j 实现。Phase 1 阶段 `ChatAppService` 只需改注入点，不改业务逻辑。

- [ ] **Step 1: 写失败测试**

```java
// ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/DynamicModelFactoryTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class DynamicModelFactoryTest {

    @Mock
    private AiModelConfigProvider configProvider;

    private DynamicModelFactory factory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factory = new DynamicModelFactory(configProvider);
    }

    @Test
    void getChatModel_sameConfig_returnsCachedInstance() {
        AiModelConfig cfg = new AiModelConfig(
            "https://api.openai.com/v1", "sk-test", "gpt-4o-mini",
            AiProtocol.OPENAI, 2048, 0.7, 30
        );
        when(configProvider.getActive()).thenReturn(cfg);

        ChatLanguageModel m1 = factory.getChatModel();
        ChatLanguageModel m2 = factory.getChatModel();

        assertThat(m1).isSameAs(m2);
    }

    @Test
    void getChatModel_configChanged_returnsNewInstance() {
        AiModelConfig cfg1 = new AiModelConfig(
            "https://api.openai.com/v1", "sk-test", "gpt-4o-mini",
            AiProtocol.OPENAI, 2048, 0.7, 30
        );
        AiModelConfig cfg2 = new AiModelConfig(
            "https://api.deepseek.com/v1", "sk-ds", "deepseek-chat",
            AiProtocol.DEEPSEEK, 2048, 0.7, 30
        );
        when(configProvider.getActive()).thenReturn(cfg1);
        ChatLanguageModel m1 = factory.getChatModel();

        when(configProvider.getActive()).thenReturn(cfg2);
        ChatLanguageModel m2 = factory.getChatModel();

        assertThat(m1).isNotSameAs(m2);
    }

    @Test
    void currentConfigHash_returnsNonEmpty() {
        AiModelConfig cfg = new AiModelConfig(
            "https://api.openai.com/v1", "sk-test", "gpt-4o-mini",
            AiProtocol.OPENAI, 2048, 0.7, 30
        );
        when(configProvider.getActive()).thenReturn(cfg);

        assertThat(factory.currentConfigHash()).isNotBlank();
    }
}
```

- [ ] **Step 2: 运行测试，确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DynamicModelFactoryTest -q 2>&1 | tail -5
```
Expected: FAIL — `DynamicModelFactory` not found

- [ ] **Step 3: 实现 `DynamicModelFactory`**

```java
// ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 动态 LangChain4j Model 工厂。
 *
 * <p>替代 {@link DynamicAiClient}，按 config hash 缓存 model 实例，
 * 支持运行时热切换（baseUrl / apiKey / modelName / protocol），无需重启。
 *
 * <p>缓存策略：Caffeine 有界缓存（最多 10 个实例），30 分钟未访问自动驱逐，
 * 防止 config 多次变更导致旧实例永不 GC 的内存泄漏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicModelFactory {

    private final AiModelConfigProvider configProvider;

    private final Cache<String, ChatLanguageModel> chatCache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    private final Cache<String, StreamingChatLanguageModel> streamingCache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    // ----------------------------------------------------------------
    // 公共 API —— 与 DynamicAiClient 签名兼容，直接替换注入点
    // ----------------------------------------------------------------

    /**
     * 流式对话，返回 token Flux。
     * 内部将项目自定义 {@link ChatMessage} 和 systemPrompt 转换为 LangChain4j 消息列表。
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        StreamingChatLanguageModel model = getStreamingChatModel();
        log.debug("[AI] streamChat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());

        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = toLangChain4jMessages(messages, systemPrompt);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        model.chat(lc4jMessages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                sink.tryEmitNext(token);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                log.warn("[AI] 流式对话错误 model={}", cfg.modelName(), error);
                sink.tryEmitError(error);
            }
        });
        return sink.asFlux().filter(s -> !s.isEmpty());
    }

    /**
     * 非流式对话，返回完整回复文本。
     * ⚠️ 内部阻塞，仅限在 boundedElastic 线程上调用（与原 DynamicAiClient 约定相同）。
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        log.debug("[AI] chat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());
        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = toLangChain4jMessages(messages, systemPrompt);
        return getChatModel().chat(lc4jMessages).aiMessage().text();
    }

    // ----------------------------------------------------------------
    // LangChain4j Model 实例（供 AI Services 使用）
    // ----------------------------------------------------------------

    public ChatLanguageModel getChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        String hash = configHash(cfg);
        return chatCache.get(hash, k -> {
            log.info("[AI] Building ChatLanguageModel protocol={} model={} hash={}",
                    cfg.apiProtocol(), cfg.modelName(), hash);
            return buildChatModel(cfg);
        });
    }

    public StreamingChatLanguageModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        String hash = configHash(cfg);
        return streamingCache.get(hash, k -> {
            log.info("[AI] Building StreamingChatLanguageModel protocol={} model={} hash={}",
                    cfg.apiProtocol(), cfg.modelName(), hash);
            return buildStreamingModel(cfg);
        });
    }

    /** 供外部日志 / 诊断查询当前 chat config hash */
    public String currentConfigHash() {
        return configHash(configProvider.getActive());
    }

    // ----------------------------------------------------------------
    // 内部构建
    // ----------------------------------------------------------------

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
                    .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                    .build();
        };
    }

    private StreamingChatLanguageModel buildStreamingModel(AiModelConfig cfg) {
        return switch (cfg.apiProtocol()) {
            case AiProtocol.ANTHROPIC -> AnthropicStreamingChatModel.builder()
                    .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                    .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                    .build();
            default -> OpenAiStreamingChatModel.builder()
                    .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                    .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                    .build();
        };
    }

    private String configHash(AiModelConfig cfg) {
        // SHA-256，非加密用途，仅做 cache key（阿里规范 §6.10 禁止 MD5）
        return DigestUtils.sha256Hex(
                cfg.baseUrl() + cfg.apiKey() + cfg.modelName() + cfg.apiProtocol());
    }

    /** 将项目 ChatMessage 列表 + systemPrompt 转换为 LangChain4j 消息列表 */
    private List<dev.langchain4j.data.message.ChatMessage> toLangChain4jMessages(
            List<ChatMessage> messages, String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> result = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        for (ChatMessage m : messages) {
            result.add("assistant".equals(m.role())
                    ? AiMessage.from(m.content())
                    : UserMessage.from(m.content()));
        }
        return result;
    }
}
```

- [ ] **Step 4: 运行单元测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DynamicModelFactoryTest -q 2>&1 | tail -10
```
Expected: Tests run: 3, Failures: 0, Errors: 0

---

### Task 1.6：ChatAppService 切换到 DynamicModelFactory

- [ ] **Step 1: 修改 `ChatAppService` 构造函数和字段**

将字段 `private final DynamicAiClient aiClient;` 改为：
```java
private final DynamicModelFactory aiClient;
```

将构造函数参数 `DynamicAiClient aiClient` 改为：
```java
DynamicModelFactory aiClient,
```

其余业务代码无需改动（`DynamicModelFactory` 暴露了与 `DynamicAiClient` 相同的 `streamChat` / `chat` 方法）。

- [ ] **Step 2: 修改 import**

删除：
```java
import com.aria.conversation.infrastructure.ai.DynamicAiClient;
```
添加：
```java
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
```

- [ ] **Step 3: 编译并跑现有测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS，所有已有测试通过

- [ ] **Step 4: 提交**

```bash
git add ai-conversation/conversation-service/pom.xml \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AiProtocol.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/DynamicModelFactoryTest.java \
        pom.xml
git commit -m "feat(ai): Phase1 - DynamicModelFactory 替换 DynamicAiClient，升级 LangChain4j 1.1.0"
```

## Phase 2：结构化输出 + Spike 验证

**目标：** 用 LangChain4j AI Services 替换 `IntentClassifier` 和 `SlotExtractService`。先做 Flux streaming Spike，再实施。

**涉及文件：**
- Create: `infrastructure/ai/IntentService.java`（domain 层接口）
- Create: `infrastructure/ai/SlotService.java`（domain 层接口）
- Create: `infrastructure/ai/LangChain4jIntentService.java`
- Create: `infrastructure/ai/LangChain4jSlotService.java`
- Modify: `application/service/ChatAppService.java`（替换注入）
- Create: 对应测试文件

> 包路径前缀统一省略为 `com/aria/conversation/`

---

### Task 2.1：Spike — 验证 Flux streaming + AI Services

在正式实施前，用一个独立测试验证 `AiServices + Flux<String>` 在 LangChain4j 1.1.0 + langchain4j-reactor 中可用。

- [ ] **Step 1: 写 Spike 测试（不对外 Spring，直接用 Mock 模型）**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/FluxStreamingSpike.java
package com.aria.conversation.infrastructure.ai;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Spike：验证 LangChain4j 1.1.0 AI Services + Flux<String> 流式输出可用。
 * 通过后 Phase 3 的 DomainAgentService 才可以安全依赖此机制。
 */
class FluxStreamingSpike {

    interface StreamAssistant {
        Flux<String> chat(@UserMessage String message);
    }

    @Test
    void aiServices_fluxStreaming_works() {
        // ChatModelMock 是 langchain4j-testing 提供的轻量 mock，不需要真实 LLM
        StreamingChatLanguageModel mockModel = ChatModelMock.thatAlwaysStreams("Hello World");

        StreamAssistant assistant = AiServices.builder(StreamAssistant.class)
                .streamingChatModel(mockModel)
                .build();

        StepVerifier.create(assistant.chat("test"))
                .expectNextMatches(token -> !token.isEmpty())
                .thenConsumeWhile(token -> true)
                .verifyComplete();
    }
}
```

- [ ] **Step 2: 在 `conversation-service/pom.xml` 添加 test scope 依赖**

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-core</artifactId>
    <classifier>tests</classifier>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 运行 Spike**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=FluxStreamingSpike -q 2>&1 | tail -10
```
Expected: Tests run: 1, Failures: 0, Errors: 0

> 若 Spike 失败（`Flux<String>` 返回类型不兼容），需在继续 Phase 3 前评估备选方案（`TokenStream` 或手动 `Sinks`）。

---

### Task 2.2：定义领域层接口 IntentService / SlotService

- [ ] **Step 1: 创建 `IntentService` 接口**

```java
// src/main/java/com/aria/conversation/domain/service/IntentService.java
package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.IntentResult;

/**
 * 意图识别领域服务接口。
 * 实现类在 infrastructure/ai/ 中，通过 Spring DI 注入。
 */
public interface IntentService {
    /**
     * 对用户消息进行意图分类。
     * 分类失败时返回 {@link IntentResult#UNKNOWN}，不抛出异常。
     */
    IntentResult classify(String userMessage);
}
```

- [ ] **Step 2: 创建 `SlotService` 接口**

```java
// src/main/java/com/aria/conversation/domain/service/SlotService.java
package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;

import java.util.List;
import java.util.Map;

/**
 * Slot 提取领域服务接口。
 */
public interface SlotService {
    /**
     * 从用户消息和对话历史中批量提取槽位值。
     * 无法提取的槽位不出现在返回 Map 中。
     */
    Map<String, Object> extract(String userMessage,
                                List<ChatMessage> recentHistory,
                                List<SlotConfig> slots);
}
```

---

### Task 2.3：实现 `LangChain4jIntentService`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentServiceTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class LangChain4jIntentServiceTest {

    @Mock private DynamicModelFactory modelFactory;

    private LangChain4jIntentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LangChain4jIntentService(modelFactory);
    }

    @Test
    void classify_validIntent_returnsParsed() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("FAQ_QUERY");
        when(modelFactory.getChatModel()).thenReturn(mock);

        IntentResult result = service.classify("我的订单在哪里");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    void classify_llmError_returnsUnknown() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("INVALID_GARBAGE");
        when(modelFactory.getChatModel()).thenReturn(mock);

        IntentResult result = service.classify("test");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jIntentServiceTest -q 2>&1 | tail -5
```
Expected: FAIL — `LangChain4jIntentService` not found

- [ ] **Step 3: 实现 `LangChain4jIntentService`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.service.IntentService;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 LangChain4j AI Services 的意图分类实现。
 * 替代手写 JSON prompt 解析的 {@link IntentClassifier}。
 *
 * <p>每次调用从 {@link DynamicModelFactory} 获取最新 model，保证热切换生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jIntentService implements IntentService {

    /** LangChain4j 内部 AI Service 代理接口，不对外暴露 */
    private interface IntentProxy {
        @SystemMessage("""
                你是一个用户意图分类器。分析用户的输入，返回以下枚举之一，不要输出任何其他内容：
                FAQ_QUERY, TRANSFER_REQUEST, COMPLAINT, CHITCHAT, OUT_OF_SCOPE, UNKNOWN

                意图说明：
                - FAQ_QUERY：咨询产品、服务、政策等业务问题
                - TRANSFER_REQUEST：要求转人工客服（"我要真人"、"转客服"、"人工"）
                - COMPLAINT：投诉、强烈不满（"投诉"、"要求赔偿"）
                - CHITCHAT：闲聊、问候，与业务无关
                - OUT_OF_SCOPE：与本业务完全无关的话题
                - UNKNOWN：无法判断

                只输出枚举值，不要解释。
                """)
        IntentType classify(@UserMessage String userMessage);
    }

    private final DynamicModelFactory modelFactory;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            IntentProxy proxy = AiServices.builder(IntentProxy.class)
                    .chatModel(modelFactory.getChatModel())
                    .build();
            IntentType type = proxy.classify(userMessage);
            return new IntentResult(type, 1.0);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jIntentServiceTest -q 2>&1 | tail -10
```
Expected: Tests run: 2, Failures: 0, Errors: 0

---

### Task 2.4：实现 `LangChain4jSlotService`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jSlotServiceTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class LangChain4jSlotServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    private LangChain4jSlotService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LangChain4jSlotService(modelFactory);
    }

    @Test
    void extract_validJson_returnsParsed() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("{\"order_id\": \"ORD001\"}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        SlotConfig slot = new SlotConfig("order_id", "订单号", "string", null, false, null);
        Map<String, Object> result = service.extract("查一下ORD001", List.of(), List.of(slot));

        assertThat(result).containsEntry("order_id", "ORD001");
    }

    @Test
    void extract_emptySlots_returnsEmpty() {
        Map<String, Object> result = service.extract("hello", List.of(), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extract_llmFails_returnsEmpty() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("not json at all");
        when(modelFactory.getChatModel()).thenReturn(mock);

        SlotConfig slot = new SlotConfig("order_id", "订单号", "string", null, false, null);
        Map<String, Object> result = service.extract("test", List.of(), List.of(slot));

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jSlotServiceTest -q 2>&1 | tail -5
```
Expected: FAIL

- [ ] **Step 3: 实现 `LangChain4jSlotService`**

`SlotService` 的 slot 结构是动态的（每个 domain 的 slot 名称不同），无法用固定 POJO 做结构化输出，沿用原有的手写 prompt + JSON 解析方式，仅将 LLM 调用切换到 `DynamicModelFactory`。

```java
// src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jSlotService.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.service.SlotService;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 LangChain4j 的 Slot 提取实现。
 * 替代 {@link SlotExtractService}。
 *
 * <p>由于 slot 结构动态（每 domain 不同），沿用手写 prompt + JSON 解析，
 * 仅将底层 LLM 调用切换到 {@link DynamicModelFactory}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jSlotService implements SlotService {

    private final DynamicModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> extract(String userMessage,
                                       List<ChatMessage> recentHistory,
                                       List<SlotConfig> slots) {
        if (slots.isEmpty()) return Map.of();
        try {
            String systemPrompt = buildExtractPrompt(slots);
            List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(userMessage, recentHistory, systemPrompt);
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
            return parseExtracted(response);
        } catch (Exception e) {
            log.warn("[DIT] 槽位提取失败 slots={}", slots.stream()
                    .map(SlotConfig::slotName).toList(), e);
            return Collections.emptyMap();
        }
    }

    private String buildExtractPrompt(List<SlotConfig> slots) {
        StringBuilder sb = new StringBuilder("""
                你是一个信息提取器。从用户的消息中提取以下参数，以 JSON 格式返回，
                无法提取的参数不要包含在 JSON 中，不要输出任何其他内容。

                需要提取的参数：
                """);
        for (SlotConfig slot : slots) {
            sb.append("- ").append(slot.slotName())
              .append("（").append(slot.slotType()).append("）")
              .append("：").append(slot.description());
            if (slot.enumValues() != null && !slot.enumValues().isEmpty()) {
                sb.append("，可选值：").append(slot.enumValues());
            }
            sb.append("\n");
        }
        sb.append("\n只输出 JSON，如：{\"order_id\": \"ORD001\"} 或 {}（无法提取时）");
        return sb.toString();
    }

    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            String userMessage, List<ChatMessage> history, String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from(systemPrompt));
        int start = Math.max(0, history.size() - 6);
        for (ChatMessage m : history.subList(start, history.size())) {
            result.add("assistant".equals(m.role())
                    ? AiMessage.from(m.content())
                    : UserMessage.from(m.content()));
        }
        result.add(UserMessage.from(userMessage));
        return result;
    }

    private Map<String, Object> parseExtracted(String response) {
        if (response == null || response.isBlank()) return Map.of();
        String json = response.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('{'), e = json.lastIndexOf('}');
            if (s >= 0 && e >= s) json = json.substring(s, e + 1);
        }
        if (!json.startsWith("{")) return Map.of();
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            result.entrySet().removeIf(entry ->
                    entry.getValue() == null
                    || entry.getValue().toString().isBlank()
                    || "null".equalsIgnoreCase(entry.getValue().toString()));
            return result;
        } catch (Exception e) {
            log.warn("[DIT] 槽位提取 JSON 解析失败: {}", json, e);
            return Map.of();
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jSlotServiceTest -q 2>&1 | tail -10
```
Expected: Tests run: 3, Failures: 0, Errors: 0

---

### Task 2.5：ChatAppService 切换到新接口，DitPipeline 中替换 SlotExtractService

- [ ] **Step 1: `ChatAppService` 将 `IntentClassifier` 改为 `IntentService`**

将字段和构造参数中的 `IntentClassifier intentClassifier` 改为：
```java
private final IntentService intentClassifier;
```
修改 import：
```java
// 删除
import com.aria.conversation.infrastructure.ai.IntentClassifier;
// 添加
import com.aria.conversation.domain.service.IntentService;
```
调用点 `intentClassifier.classify(message)` 不变（接口方法名相同）。

- [ ] **Step 2: `DitPipeline`（或 `SlotResolver`）将 `SlotExtractService` 改为 `SlotService`**

找到注入 `SlotExtractService` 的类（通常是 `SlotResolver`），将字段类型改为 `SlotService`，修改 import：
```java
// 删除
import com.aria.conversation.infrastructure.dit.pipeline.SlotExtractService;
// 添加
import com.aria.conversation.domain.service.SlotService;
```
调用点 `slotExtractService.extract(...)` 改为 `slotService.extract(...)`（同名方法）。

- [ ] **Step 3: 编译并运行所有测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 4: 提交**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(ai): Phase2 - LangChain4j AI Services 替换 IntentClassifier/SlotExtractService"
```

## Phase 3：DomainAgentService + ToolProvider（核心 DIT 替换）

**目标：** 用 LangChain4j 原生 function calling 替代整个 DIT pipeline（`DitPipeline` / `ToolExecutor` / `DomainIntentClassifier`）。`HttpToolRunner` 保留，作为 `ToolExecutor` 实现。

**涉及文件：**
- Create: `infrastructure/ai/BuiltinToolNames.java`
- Create: `infrastructure/ai/SessionChatMemoryStore.java`
- Create: `application/service/DomainAgentService.java`
- Modify: `application/service/ChatAppService.java`
- Delete（Phase 3 完成后）: `DitPipeline.java`, `ToolExecutor.java`, `DomainIntentClassifier.java`

---

### Task 3.1：创建 `BuiltinToolNames` 常量类

- [ ] **Step 1: 创建文件**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/BuiltinToolNames.java
package com.aria.conversation.infrastructure.ai;

/**
 * 内置工具名称常量。
 * 禁止在代码中使用字符串字面量标识工具（阿里规范 §1.4）。
 */
public final class BuiltinToolNames {
    /** LLM 触发转人工时调用的内置工具名称 */
    public static final String TRANSFER_TO_AGENT = "transfer_to_agent";

    private BuiltinToolNames() {}
}
```

---

### Task 3.2：实现 `SessionChatMemoryStore`

`SessionChatMemoryStore` 是 ACL 适配器：LangChain4j 的 `ChatMessage` 类型不跨越此类边界。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/SessionChatMemoryStoreTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionChatMemoryStoreTest {

    @Mock private ConversationHistoryRepository historyRepo;
    private SessionChatMemoryStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = new SessionChatMemoryStore(historyRepo);
    }

    @Test
    void getMessages_convertsToLangChain4jTypes() {
        when(historyRepo.findAll("s1")).thenReturn(List.of(
            new ConversationMessage("user", "hello", 1L),
            new ConversationMessage("assistant", "hi", 2L)
        ));

        var messages = store.getMessages("s1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void updateMessages_callsSaveAll() {
        store.updateMessages("s1", List.of(
            UserMessage.from("hello"),
            AiMessage.from("hi")
        ));

        verify(historyRepo).saveAll(eq("s1"), anyList());
    }

    @Test
    void deleteMessages_callsDelete() {
        store.deleteMessages("s1");
        verify(historyRepo).deleteBySessionId("s1");
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=SessionChatMemoryStoreTest -q 2>&1 | tail -5
```
Expected: FAIL

> 若 `ConversationHistoryRepository` 没有 `saveAll(String, List)` 方法，需先在接口中添加。

- [ ] **Step 3: 若 `ConversationHistoryRepository` 无 `saveAll`，添加方法**

打开 `ConversationHistoryRepository` 接口文件，添加：
```java
/** 全量替换会话历史（replace 语义，非 append） */
void saveAll(String sessionId, List<ConversationMessage> messages);
```
并在实现类中实现（先删除旧数据再批量插入）。

- [ ] **Step 4: 实现 `SessionChatMemoryStore`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/SessionChatMemoryStore.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j ChatMemoryStore 适配器。
 *
 * <p>LangChain4j {@link ChatMessage} 类型仅在此类内部使用，不向外暴露，
 * 保持 ACL 边界（对外仍使用项目领域类型 {@link ConversationMessage}）。
 *
 * <p>{@link #updateMessages} 使用全量替换语义（不是 append），
 * 依赖 {@link ConversationHistoryRepository#saveAll} 的 replace 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionChatMemoryStore implements ChatMemoryStore {

    private final ConversationHistoryRepository historyRepo;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return historyRepo.findAll(memoryId.toString()).stream()
                .map(this::toLangChain4jMessage)
                .toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            historyRepo.saveAll(memoryId.toString(), messages.stream()
                    .map(this::toDomainMessage)
                    .toList());
        } catch (Exception e) {
            log.error("[Memory] 持久化会话历史失败 sessionId={}", memoryId, e);
            throw e;
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        historyRepo.deleteBySessionId(memoryId.toString());
    }

    // ---- 类型转换（LangChain4j 类型不跨越此边界）----

    private ChatMessage toLangChain4jMessage(ConversationMessage m) {
        return switch (m.role()) {
            case "assistant" -> AiMessage.from(m.content());
            case "tool"      -> ToolExecutionResultMessage.from(m.content(), m.content(), m.content());
            default          -> UserMessage.from(m.content());
        };
    }

    private ConversationMessage toDomainMessage(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            return new ConversationMessage("assistant", ai.text(), 0L);
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return new ConversationMessage("tool", tr.text(), 0L);
        }
        if (m instanceof UserMessage um) {
            return new ConversationMessage("user", um.singleText(), 0L);
        }
        return new ConversationMessage("user", m.toString(), 0L);
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=SessionChatMemoryStoreTest -q 2>&1 | tail -10
```
Expected: Tests run: 3, Failures: 0, Errors: 0

---

### Task 3.3：实现 `DomainAgentService`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/application/service/DomainAgentServiceTest.java
package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.ai.*;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class DomainAgentServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    @Mock private DomainRepository domainRepo;
    @Mock private HttpToolRunner httpToolRunner;
    @Mock private SessionChatMemoryStore memoryStore;
    @Mock private KnowledgeClient knowledgeClient;

    private DomainAgentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DomainAgentService(modelFactory, domainRepo, httpToolRunner, memoryStore, knowledgeClient);
    }

    @Test
    void streamChat_noTools_emitsDataEvents() {
        StreamingChatLanguageModel mockModel = ChatModelMock.thatAlwaysStreams("你好");
        when(modelFactory.getStreamingChatModel()).thenReturn(mockModel);
        when(domainRepo.loadTools("shop")).thenReturn(List.of());
        when(knowledgeClient.search(any())).thenReturn(List.of());
        when(memoryStore.getMessages(any())).thenReturn(List.of());

        StepVerifier.create(service.streamChat("sess1", "shop", "你好"))
                .expectNextMatches(e -> e.eventType() == null) // data event
                .thenConsumeWhile(e -> true)
                .verifyComplete();
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DomainAgentServiceTest -q 2>&1 | tail -5
```
Expected: FAIL — `DomainAgentService` not found

- [ ] **Step 3: 实现 `DomainAgentService`**

```java
// src/main/java/com/aria/conversation/application/service/DomainAgentService.java
package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.infrastructure.ai.BuiltinToolNames;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域感知对话应用服务（基于 LangChain4j 原生 function calling）。
 *
 * <p>替代 {@code DitPipeline} + {@code ToolExecutor} + {@code DomainIntentClassifier}。
 * 从 {@link DomainRepository} 动态加载 {@link ToolConfig}，转为 {@link ToolSpecification}，
 * 委托 {@link HttpToolRunner} 执行，工具调用循环由 LangChain4j 自动处理。
 *
 * <p>每次请求新建 AI Service 代理（轻量 JDK Proxy），底层 model 由 {@link DynamicModelFactory} 缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainAgentService {

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepo;
    private final HttpToolRunner httpToolRunner;
    private final SessionChatMemoryStore memoryStore;
    private final KnowledgeClient knowledgeClient;
    private final ObjectMapper objectMapper;

    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId,
                          @SystemMessage String systemPrompt,
                          @UserMessage String message);
    }

    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.debug("[DomainAgent] streamChat start sessionId={} domainCode={}", sessionId, domainCode);

        // 1. RAG 检索（构建 system prompt，per-request）
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = buildSystemPrompt(hits, domainCode);

        // 2. per-request Sinks，通过构造注入 listener，避免 ThreadLocal + Reactor 线程问题
        Sinks.Many<ChatEvent> toolEventSink = Sinks.many().unicast().onBackpressureBuffer();
        ChatModelListener toolListener = buildToolEventListener(toolEventSink);

        // 3. 每次请求新建代理（代价仅为 JDK Proxy，model 已在 DynamicModelFactory 缓存）
        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(20).chatMemoryStore(memoryStore).build())
                .toolProvider(buildToolProvider(domainCode))
                .listeners(List.of(toolListener))
                .build();

        // 4. token Flux + tool 事件 Flux 合并输出
        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, systemPrompt, userMessage)
                .map(ChatEvent::data);

        return Flux.merge(tokenFlux, toolEventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] streamChat error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage())));
    }

    // ----------------------------------------------------------------

    private ToolProvider buildToolProvider(String domainCode) {
        return (req) -> {
            // ToolProvider lambda 在每次工具选择时执行，实时从 DB 加载，感知工具变更
            List<ToolConfig> tools = domainRepo.loadTools(domainCode);
            ToolProviderResult.Builder builder = ToolProviderResult.builder();

            for (ToolConfig tc : tools) {
                ToolSpecification spec = buildToolSpec(tc);
                ToolExecutor executor = (toolReq, memId) -> executeHttpTool(tc, toolReq);
                builder.add(spec, executor);
            }
            return builder.build();
        };
    }

    private ToolSpecification buildToolSpec(ToolConfig tc) {
        // paramSchema 为 JSON 字符串，解析为属性 Map
        Map<String, dev.langchain4j.model.output.structured.JsonSchemaElement> props = new LinkedHashMap<>();
        try {
            if (tc.paramSchema() != null && !tc.paramSchema().isBlank()) {
                Map<String, Object> schema = objectMapper.readValue(tc.paramSchema(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                properties.forEach((name, def) -> {
                    String desc = "";
                    if (def instanceof Map<?, ?> defMap) {
                        desc = String.valueOf(defMap.getOrDefault("description", ""));
                    }
                    props.put(name, JsonStringSchema.builder().description(desc).build());
                });
            }
        } catch (Exception e) {
            log.warn("[DomainAgent] paramSchema 解析失败 tool={}", tc.code(), e);
        }

        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(JsonObjectSchema.builder().properties(props).build())
                .build();
    }

    private String executeHttpTool(ToolConfig tc, ToolExecutionRequest toolReq) {
        try {
            // LangChain4j 的 arguments() 返回 JSON 字符串，需解析为 Map
            Map<String, Object> args = objectMapper.readValue(
                    toolReq.arguments(), new TypeReference<>() {});
            ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
            log.info("[DomainAgent] tool executed tool={} status={}", tc.code(), result.getStatus());
            return result.isSuccess() ? result.getResponse() : "工具执行失败: " + result.getResponse();
        } catch (Exception e) {
            log.error("[DomainAgent] tool execution error tool={}", tc.code(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    private ChatModelListener buildToolEventListener(Sinks.Many<ChatEvent> sink) {
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                // 检测本次请求中是否携带工具调用（LLM 决定调用工具时触发）
                if (ctx.chatRequest().toolSpecifications() != null
                        && !ctx.chatRequest().toolSpecifications().isEmpty()) {
                    // 此处仅记录工具准备阶段，实际 tool_call 事件在工具执行后再 emit
                    log.debug("[DomainAgent] LLM tool request detected");
                }
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                var aiMsg = ctx.chatResponse().aiMessage();
                if (aiMsg.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        // transfer_to_agent 内置工具触发 transfer 事件
                        if (BuiltinToolNames.TRANSFER_TO_AGENT.equals(req.name())) {
                            sink.tryEmitNext(ChatEvent.transfer("{\"intentCode\":\"agent_transfer\"}"));
                            return;
                        }
                        try {
                            String payload = objectMapper.writeValueAsString(
                                    ToolCallPayload.running(req.name()));
                            sink.tryEmitNext(ChatEvent.toolCall(payload));
                        } catch (Exception e) {
                            log.warn("[DomainAgent] tool_call 事件序列化失败 tool={}", req.name(), e);
                        }
                    }
                }
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {
                log.error("[DomainAgent] ChatModel error", ctx.error());
                sink.tryEmitError(ctx.error());
            }
        };
    }

    private String buildSystemPrompt(List<KnowledgeSearchResult.Hit> hits, String domainCode) {
        StringBuilder sb = new StringBuilder();
        if (hits != null && !hits.isEmpty()) {
            sb.append("【参考资料】（请优先依据以下内容回答）\n\n");
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeSearchResult.Hit h = hits.get(i);
                String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                        ? h.getBreadcrumb() : "文档片段";
                sb.append("[").append(i + 1).append("] ").append(label).append("\n")
                  .append(h.getContent() != null ? h.getContent() : "").append("\n\n");
            }
            sb.append("---\n");
        }
        sb.append("你是一名专业的智能客服助手。请用简洁、友好的语言回答用户问题。");
        return sb.toString();
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DomainAgentServiceTest -q 2>&1 | tail -10
```
Expected: Tests run: 1, Failures: 0, Errors: 0

---

### Task 3.4：ChatAppService 切换 Domain 路径到 DomainAgentService

- [ ] **Step 1: 在 `ChatAppService` 中注入 `DomainAgentService`，移除旧 DIT 字段，修改 `stream()` 方法**

删除以下字段和构造参数（旧 DIT pipeline）：
```java
// 删除这些字段
private final DitPipeline ditPipeline;
private final ToolExecutor toolExecutor;
```

添加新字段：
```java
private final DomainAgentService domainAgentService;
```

构造函数中删除 `DitPipeline ditPipeline`、`ToolExecutor toolExecutor` 参数，添加 `DomainAgentService domainAgentService`，赋值。

删除 import：
```java
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolExecutor;
```

添加 import：
```java
import com.aria.conversation.application.service.DomainAgentService;
```

将 `stream()` 方法中：
```java
if (StringUtils.isNotBlank(domainCode)) {
    return streamChatWithDomain(sessionId, message, domainCode, Map.of());
}
```
改为：
```java
if (StringUtils.isNotBlank(domainCode)) {
    return domainAgentService.streamChat(sessionId, domainCode, message);
}
```

删除方法 `streamChatWithDomain` 和 `buildDomainEventStream`（已被 `DomainAgentService` 取代）。

- [ ] **Step 2: 编译并运行所有测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 3: 删除旧 DIT Pipeline 类（已被替代）**

```bash
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/DitPipeline.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/ToolExecutor.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/DomainIntentClassifier.java
```

同步删除对应测试文件（如有），再次编译确认无报错：
```bash
./mvnw compile -pl ai-conversation/conversation-service -am -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add -A ai-conversation/conversation-service/src/
git commit -m "feat(dit): Phase3 - DomainAgentService + ToolProvider 替换 DitPipeline，LangChain4j 原生 function calling"
```

## Phase 4：knowledge-service EmbeddingModel 替换

**目标：** 用 LangChain4j `OpenAiEmbeddingModel` 替换手写 `OpenAiEmbeddingService`，保留热切换和批量向量化能力。

**涉及文件：**
- Modify: `ai-knowledge/knowledge-service/pom.xml`
- Create: `knowledge-service/src/main/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java`
- Modify: `knowledge-service/src/test/java/...LangChain4jEmbeddingServiceTest.java`

---

### Task 4.1：knowledge-service 调整依赖

- [ ] **Step 1: 修改 `ai-knowledge/knowledge-service/pom.xml`**

将原有的：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
</dependency>
```
替换为（不用 auto-config starter，原因同 conversation-service：需要手动控制 model 生命周期）：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw compile -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS

---

### Task 4.2：实现 `LangChain4jEmbeddingService`

- [ ] **Step 1: 写失败测试**

```java
// ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingServiceTest.java
package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class LangChain4jEmbeddingServiceTest {

    @Mock private AiModelConfigProvider configProvider;
    @Mock private EmbeddingProperties props;

    private LangChain4jEmbeddingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(props.batchSize()).thenReturn(2);
        when(props.timeoutSeconds()).thenReturn(30);
        AiModelConfig cfg = new AiModelConfig(
            "https://api.openai.com/v1", "sk-test", "text-embedding-3-small",
            "openai", 0, 0.0, 30
        );
        when(configProvider.getActiveEmbedding()).thenReturn(cfg);
        service = new LangChain4jEmbeddingService(configProvider, props);
    }

    @Test
    void embed_setsVectorsOnChunks() {
        // 注入 mock EmbeddingModel，替换内部 Caffeine 缓存中的实例
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(mockModel.embedAll(anyList()))
            .thenReturn(Response.from(List.of(Embedding.from(vec))));

        service.overrideModelForTest(mockModel); // 测试专用注入口

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("test text");
        service.embed(List.of(chunk));

        assertThat(chunk.getVector()).isEqualTo(vec);
    }

    @Test
    void encode_returnsVector() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.4f, 0.5f};
        when(mockModel.embed(any())).thenReturn(Response.from(Embedding.from(vec)));
        service.overrideModelForTest(mockModel);

        float[] result = service.encode("hello");

        assertThat(result).isEqualTo(vec);
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-knowledge/knowledge-service -Dtest=LangChain4jEmbeddingServiceTest -q 2>&1 | tail -5
```
Expected: FAIL

- [ ] **Step 3: 实现 `LangChain4jEmbeddingService`**

```java
// ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java
package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 LangChain4j 的 Embedding 服务实现（替代手写 {@code OpenAiEmbeddingService}）。
 *
 * <p>支持任意 OpenAI 兼容端点（infinity / Ollama / ZhipuAI / OpenAI），
 * 通过 Caffeine 缓存按 config hash 热切换，无需重启。
 */
@Slf4j
@Service("realEmbeddingService")
@Profile("!test")
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider configProvider;
    private final EmbeddingProperties props;

    private final Cache<String, EmbeddingModel> modelCache = Caffeine.newBuilder()
            .maximumSize(5)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public LangChain4jEmbeddingService(AiModelConfigProvider configProvider,
                                       EmbeddingProperties props) {
        this.configProvider = configProvider;
        this.props = props;
    }

    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) return;
        List<List<KnowledgeChunk>> batches = partition(chunks, props.batchSize());
        log.debug("[Embedding] 开始批量向量化 total={} batches={}", chunks.size(), batches.size());

        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgeChunk> batch = batches.get(i);
            EmbeddingModel model = getModel(); // 每批重拉 config，支持长任务中热切换

            List<TextSegment> segments = batch.stream()
                    .map(c -> TextSegment.from(c.getContent()))
                    .toList();
            List<Embedding> embeddings = model.embedAll(segments).content();

            for (int j = 0; j < batch.size() && j < embeddings.size(); j++) {
                batch.get(j).setVector(embeddings.get(j).vector());
            }
            log.debug("[Embedding] 第 {}/{} 批完成 batchSize={}", i + 1, batches.size(), batch.size());
        }
    }

    @Override
    public float[] encode(String text) {
        return getModel().embed(TextSegment.from(text)).content().vector();
    }

    /** 测试专用：注入 mock 模型，绕过真实 HTTP 调用 */
    void overrideModelForTest(EmbeddingModel model) {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        modelCache.put(configHash(cfg), model);
    }

    private EmbeddingModel getModel() {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        String hash = configHash(cfg);
        return modelCache.get(hash, k -> {
            log.info("[Embedding] 初始化 EmbeddingModel baseUrl={} model={}", cfg.baseUrl(), cfg.modelName());
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(cfg.baseUrl())
                    .apiKey(cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : "none")
                    .modelName(cfg.modelName())
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .build();
        });
    }

    private String configHash(AiModelConfig cfg) {
        return DigestUtils.sha256Hex(cfg.baseUrl() + cfg.apiKey() + cfg.modelName());
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-knowledge/knowledge-service -Dtest=LangChain4jEmbeddingServiceTest -q 2>&1 | tail -10
```
Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 5: 运行 knowledge-service 全量测试**

```bash
./mvnw test -pl ai-knowledge/knowledge-service -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 6: 提交**

```bash
git add ai-knowledge/knowledge-service/pom.xml \
        ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java \
        ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingServiceTest.java
git commit -m "feat(knowledge): Phase4 - LangChain4j EmbeddingModel 替换 OpenAiEmbeddingService"
```

---

## 收尾：全量验证 + PR

### Task 5.1：全模块编译 + 测试

- [ ] **Step 1: 全量构建**

```bash
./mvnw clean test -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS，所有模块测试通过

- [ ] **Step 2: 检查旧类是否还有引用**

```bash
grep -r "DynamicAiClient\|IntentClassifier\|SlotExtractService\|DitPipeline\|ToolExecutor\|DomainIntentClassifier\|OpenAiEmbeddingService" \
  --include="*.java" \
  ai-conversation/conversation-service/src/main \
  ai-knowledge/knowledge-service/src/main
```
Expected: 无输出（旧类全部删除或无引用）

### Task 5.2：提 PR

- [ ] **Step 1: 推分支**

```bash
git push -u origin feature/langchain4j-migration
```

- [ ] **Step 2: 创建 PR**

```bash
gh pr create \
  --title "feat: LangChain4j 1.1.0 全面迁移（4 phases）" \
  --body "## 变更摘要
- Phase1: DynamicModelFactory 替换 DynamicAiClient，升级 LangChain4j 1.1.0
- Phase2: LangChain4j AI Services 替换 IntentClassifier/SlotExtractService
- Phase3: DomainAgentService + ToolProvider 替换 DitPipeline，使用原生 function calling
- Phase4: LangChain4jEmbeddingService 替换 OpenAiEmbeddingService

## 已测试
- 所有新组件有单元测试
- 全量 ./mvnw clean test 通过

## 注意事项
- PendingSlotRepository 暂保留，待断线重连场景验证后再删除
- 热切换能力保留（Caffeine hash-based 缓存）" \
  --base main
```
