# LangChain4j 全面迁移实施计划 v2.0

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 conversation-service 和 knowledge-service 中所有手写 AI 基础设施替换为 LangChain4j 1.1.0，引入域路由小模型、会话域切换历史、策略模式重构，消除 if/switch 分支膨胀。

**Architecture:** `DynamicModelFactory`（含 `LlmModelBuilder` 策略）按 config hash Caffeine 缓存三类 model 实例（CHAT/STREAMING/ROUTER）；`DomainRouterService` 用 ROUTER 小模型判断会话内域切换；`DomainAgentService` 用 LangChain4j 原生 function calling 替代 DIT pipeline；`RouteResultHandler` / `HttpAuthStrategy` / `SlotResolutionStrategy` 消除 ChatAppService / HttpToolRunner / SlotResolver 中的分支膨胀。

**Tech Stack:** LangChain4j 1.1.0, langchain4j-reactor, langchain4j-open-ai, langchain4j-anthropic, Caffeine, Spring Boot 3, Project Reactor, MyBatis-Plus

**设计文档:** `docs/superpowers/specs/2026-07-05-langchain4j-migration-design.md`

---

## Phase 1：依赖升级 + DynamicModelFactory + LlmModelBuilder 策略

**目标：** 升级 LangChain4j 至 1.1.0，用 `LlmModelBuilder` 策略模式替代 switch(apiProtocol)，实现 `DynamicModelFactory`，FAQ 路径切换到新实现。DIT 路径暂不动。

**涉及文件：**
- Modify: `pom.xml`（根）
- Modify: `ai-conversation/conversation-service/pom.xml`
- Create: `…/infrastructure/ai/AiProtocol.java`
- Create: `…/infrastructure/ai/BuiltinToolNames.java`
- Create: `…/infrastructure/ai/LlmModelBuilder.java`
- Create: `…/infrastructure/ai/OpenAiModelBuilder.java`
- Create: `…/infrastructure/ai/AnthropicModelBuilder.java`
- Create: `…/infrastructure/ai/DynamicModelFactory.java`
- Modify: `…/application/service/ChatAppService.java`
- Create: `…/test/…/DynamicModelFactoryTest.java`

> 包路径前缀统一为 `com/aria/conversation/`

---

### Task 1.1：创建 feature 分支

- [ ] **Step 1: 从 main 创建分支**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
git checkout -b feature/langchain4j-migration
```

Expected: `Switched to a new branch 'feature/langchain4j-migration'`

---

### Task 1.2：升级根 pom 依赖

- [ ] **Step 1: 修改 `pom.xml` `<properties>` 中的版本号**

将 `<langchain4j.version>0.36.2</langchain4j.version>` 改为：
```xml
<langchain4j.version>1.1.0</langchain4j.version>
```

- [ ] **Step 2: 在 `<dependencyManagement>` 中将单条依赖替换为 BOM**

删除：
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

- [ ] **Step 3: 验证根 pom**

```bash
./mvnw validate -q
```
Expected: `BUILD SUCCESS`

---

### Task 1.3：conversation-service 添加依赖

- [ ] **Step 1: 在 `ai-conversation/conversation-service/pom.xml` 的 `<dependencies>` 中添加**

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
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-reactor</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

- [ ] **Step 2: 编译验证（不跑测试）**

```bash
./mvnw compile -pl ai-conversation/conversation-service -am -q
```
Expected: `BUILD SUCCESS`

---

### Task 1.4：常量类

- [ ] **Step 1: 创建 `AiProtocol.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/AiProtocol.java
package com.aria.conversation.infrastructure.ai;

/** AI 协议标识常量。禁止在代码中使用字符串字面量标识协议（阿里规范 §1.4）。 */
public final class AiProtocol {
    public static final String OPENAI             = "openai";
    public static final String OPENAI_COMPATIBLE  = "OPENAI_COMPATIBLE";
    public static final String DEEPSEEK           = "deepseek";
    public static final String MOONSHOT           = "moonshot";
    public static final String QIANWEN            = "qianwen";
    public static final String ANTHROPIC          = "anthropic";
    private AiProtocol() {}
}
```

- [ ] **Step 2: 创建 `BuiltinToolNames.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/BuiltinToolNames.java
package com.aria.conversation.infrastructure.ai;

/** 内置工具名称常量。禁止在代码中使用字符串字面量标识工具（阿里规范 §1.4）。 */
public final class BuiltinToolNames {
    public static final String TRANSFER_TO_AGENT = "transfer_to_agent";
    public static final String SWITCH_DOMAIN     = "switch_domain";
    private BuiltinToolNames() {}
}
```

---

### Task 1.5：LlmModelBuilder 策略（替代 switch(apiProtocol)）

- [ ] **Step 1: 创建策略接口 `LlmModelBuilder.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/LlmModelBuilder.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

/**
 * LLM model 构建策略。
 * 新增 LLM provider 只需新增一个 @Component 实现，无需修改 DynamicModelFactory。
 */
public interface LlmModelBuilder {
    /** 是否支持指定协议 */
    boolean supports(String apiProtocol);

    ChatLanguageModel buildChatModel(AiModelConfig cfg);
    StreamingChatLanguageModel buildStreamingModel(AiModelConfig cfg);
}
```

- [ ] **Step 2: 创建 `OpenAiModelBuilder.java`（兜底，处理所有 OpenAI-compat 协议）**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/OpenAiModelBuilder.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 处理 OpenAI 及所有兼容协议（DeepSeek / Moonshot / Qianwen / 天翼云等）。 */
@Component
public class OpenAiModelBuilder implements LlmModelBuilder {

    @Override
    public boolean supports(String apiProtocol) {
        // 兜底实现：只要不是明确的其他协议，均走 OpenAI-compat
        return !AiProtocol.ANTHROPIC.equals(apiProtocol);
    }

    @Override
    public ChatLanguageModel buildChatModel(AiModelConfig cfg) {
        return OpenAiChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }

    @Override
    public StreamingChatLanguageModel buildStreamingModel(AiModelConfig cfg) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }
}
```

- [ ] **Step 3: 创建 `AnthropicModelBuilder.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/AnthropicModelBuilder.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 处理 Anthropic Claude 协议。 */
@Component
public class AnthropicModelBuilder implements LlmModelBuilder {

    @Override
    public boolean supports(String apiProtocol) {
        return AiProtocol.ANTHROPIC.equals(apiProtocol);
    }

    @Override
    public ChatLanguageModel buildChatModel(AiModelConfig cfg) {
        return AnthropicChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }

    @Override
    public StreamingChatLanguageModel buildStreamingModel(AiModelConfig cfg) {
        return AnthropicStreamingChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }
}
```

---

### Task 1.6：DynamicModelFactory

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/DynamicModelFactoryTest.java
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

    @Mock private AiModelConfigProvider configProvider;
    @Mock private LlmModelBuilder openAiBuilder;
    @Mock private LlmModelBuilder anthropicBuilder;
    @Mock private ChatLanguageModel mockChatModel;
    @Mock private ChatLanguageModel mockAnthropicModel;
    @Mock private StreamingChatLanguageModel mockStreamingModel;

    private DynamicModelFactory factory;

    private AiModelConfig openAiCfg() {
        return new AiModelConfig(1L, "test", "OpenAI", AiProtocol.OPENAI,
                "https://api.openai.com/v1", "sk-test", "gpt-4o-mini",
                0.7, 2048, 30);
    }

    private AiModelConfig anthropicCfg() {
        return new AiModelConfig(2L, "claude", "Anthropic", AiProtocol.ANTHROPIC,
                "https://api.anthropic.com", "sk-ant", "claude-3-5-sonnet-20241022",
                0.7, 2048, 30);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(openAiBuilder.supports(AiProtocol.OPENAI)).thenReturn(true);
        when(openAiBuilder.supports(AiProtocol.ANTHROPIC)).thenReturn(false);
        when(anthropicBuilder.supports(AiProtocol.ANTHROPIC)).thenReturn(true);
        when(anthropicBuilder.supports(AiProtocol.OPENAI)).thenReturn(false);
        when(openAiBuilder.buildChatModel(openAiCfg())).thenReturn(mockChatModel);
        when(openAiBuilder.buildStreamingModel(openAiCfg())).thenReturn(mockStreamingModel);
        when(anthropicBuilder.buildChatModel(anthropicCfg())).thenReturn(mockAnthropicModel);
        factory = new DynamicModelFactory(configProvider, List.of(openAiBuilder, anthropicBuilder));
    }

    @Test
    void getChatModel_sameConfig_returnsCachedInstance() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        ChatLanguageModel m1 = factory.getChatModel();
        ChatLanguageModel m2 = factory.getChatModel();
        assertThat(m1).isSameAs(m2);
    }

    @Test
    void getChatModel_configChanged_returnsNewInstance() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        ChatLanguageModel m1 = factory.getChatModel();

        when(configProvider.getActive()).thenReturn(anthropicCfg());
        ChatLanguageModel m2 = factory.getChatModel();

        assertThat(m1).isNotSameAs(m2);
    }

    @Test
    void getChatModel_routesAnthropicToAnthropicBuilder() {
        when(configProvider.getActive()).thenReturn(anthropicCfg());
        ChatLanguageModel model = factory.getChatModel();
        assertThat(model).isSameAs(mockAnthropicModel);
    }

    @Test
    void currentConfigHash_returnsNonEmpty() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        assertThat(factory.currentConfigHash()).isNotBlank();
    }
}
```

- [ ] **Step 2: 运行测试确认 FAIL**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DynamicModelFactoryTest -q 2>&1 | tail -5
```
Expected: FAIL — `DynamicModelFactory` not found

- [ ] **Step 3: 实现 `DynamicModelFactory.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java
package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 动态 LangChain4j Model 工厂。
 *
 * <p>替代 {@link DynamicAiClient}，按 config hash 缓存三类 model 实例：
 * CHAT / STREAMING / ROUTER。
 * 支持热切换（Redis Pub/Sub 触发 invalidate → 下次请求重建）。
 *
 * <p>使用 {@link LlmModelBuilder} 策略消除 switch(apiProtocol)，
 * 新增 LLM provider 只需新增 @Component 实现。
 */
@Slf4j
@Component
public class DynamicModelFactory {

    private final AiModelConfigProvider configProvider;
    private final List<LlmModelBuilder> builders;

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

    // ----------------------------------------------------------------
    // 公共 API — 与 DynamicAiClient 签名兼容，供 ChatAppService 直接替换
    // ----------------------------------------------------------------

    /**
     * 流式对话。将项目 {@link ChatMessage} 列表 + systemPrompt 转为 LangChain4j 消息列表后调用。
     * 与原 DynamicAiClient.streamChat() 签名完全一致，仅替换注入点即可。
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        StreamingChatLanguageModel model = getStreamingChatModel();
        log.debug("[AI] streamChat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());

        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages =
                toLangChain4jMessages(messages, systemPrompt);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        model.chat(lc4jMessages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) { sink.tryEmitNext(token); }
            @Override
            public void onCompleteResponse(ChatResponse response) { sink.tryEmitComplete(); }
            @Override
            public void onError(Throwable error) {
                log.warn("[AI] 流式对话错误 model={}", cfg.modelName(), error);
                sink.tryEmitError(error);
            }
        });
        return sink.asFlux().filter(s -> !s.isEmpty());
    }

    /**
     * 非流式对话。⚠️ 内部阻塞，仅限在 boundedElastic 线程上调用。
     * 与原 DynamicAiClient.chat() 签名完全一致。
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        log.debug("[AI] chat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());
        return getChatModel().chat(toLangChain4jMessages(messages, systemPrompt))
                .aiMessage().text();
    }

    // ----------------------------------------------------------------
    // LangChain4j Model 实例（供 AI Services / DomainAgentService 使用）
    // ----------------------------------------------------------------

    public ChatLanguageModel getChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return chatCache.get(configHash(cfg), k -> {
            log.info("[AI] Building ChatModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    public StreamingChatLanguageModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return streamingCache.get(configHash(cfg), k -> {
            log.info("[AI] Building StreamingModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildStreamingModel(cfg);
        });
    }

    /** ROUTER 小模型，用于 DomainRouterService 域路由判断（Phase 4 使用）。 */
    public ChatLanguageModel getRouterModel() {
        AiModelConfig cfg = configProvider.getActiveRouter();
        return routerCache.get(configHash(cfg), k -> {
            log.info("[AI] Building RouterModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    /** 供外部日志/诊断查询当前 chat config hash。 */
    public String currentConfigHash() {
        return configHash(configProvider.getActive());
    }

    // ----------------------------------------------------------------

    private LlmModelBuilder resolveBuilder(AiModelConfig cfg) {
        return builders.stream()
                .filter(b -> b.supports(cfg.apiProtocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[AI] No LlmModelBuilder for protocol: " + cfg.apiProtocol()));
    }

    private String configHash(AiModelConfig cfg) {
        return DigestUtils.sha256Hex(
                cfg.baseUrl() + cfg.apiKey() + cfg.modelName() + cfg.apiProtocol());
    }

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

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DynamicModelFactoryTest -q 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

---

### Task 1.7：ChatAppService 切换注入点

- [ ] **Step 1: 将 `ChatAppService` 中 `DynamicAiClient` 字段和构造参数改为 `DynamicModelFactory`**

```java
// 修改前
private final DynamicAiClient aiClient;
// 修改后
private final DynamicModelFactory aiClient;
```

构造函数参数同步修改。删除 import `DynamicAiClient`，添加 import `DynamicModelFactory`。

其余所有调用点（`aiClient.streamChat(...)` / `aiClient.chat(...)`）**不需要改动**，因为 `DynamicModelFactory` 暴露了相同签名。

- [ ] **Step 2: 编译 + 运行所有测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`，所有已有测试通过

- [ ] **Step 3: 提交**

```bash
git add pom.xml \
        ai-conversation/conversation-service/pom.xml \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AiProtocol.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/BuiltinToolNames.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/LlmModelBuilder.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/OpenAiModelBuilder.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AnthropicModelBuilder.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicModelFactory.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/DynamicModelFactoryTest.java
git commit -m "feat(ai): Phase1 - DynamicModelFactory + LlmModelBuilder 策略替换 DynamicAiClient，升级 LangChain4j 1.1.0"
```

## Phase 2：ROUTER 模型类型 + AiModelConfigProvider 扩展

**目标：** 在 `ai_model_config` 表新增 `ROUTER` 类型，扩展 `AiModelConfigProvider` 接口和 `RemoteAiModelConfigProvider` 实现，`auth-service` 新增内部端点，前端模型管理新增 ROUTER Tab。

**涉及文件：**
- Create: `docs/sql/migration-005-router-and-domain-switch.sql`
- Modify: `ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelConfigProvider.java`
- Modify: `ai-common/common-web/src/main/java/com/aria/common/web/ai/RemoteAiModelConfigProvider.java`
- Modify: `ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/InternalAiModelController.java`

---

### Task 2.1：数据库 migration 脚本

- [ ] **Step 1: 创建 `docs/sql/migration-005-router-and-domain-switch.sql`**

```sql
-- migration-005: 新增 ROUTER 模型类型 + 会话域切换历史表 + __system__ 域意图数据

-- ① 扩展 ai_model_config CHECK 约束，支持 ROUTER 类型
ALTER TABLE cs_auth.ai_model_config
    DROP CONSTRAINT IF EXISTS ai_model_config_model_type_check;
ALTER TABLE cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_model_type_check
    CHECK (model_type IN ('CHAT', 'EMBEDDING', 'ROUTER'));

-- ② 插入默认 ROUTER 模型（本地 Ollama，可在后台修改）
INSERT INTO cs_auth.ai_model_config
    (name, provider, api_protocol, model_type, base_url, api_key_enc,
     model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled)
VALUES ('Qwen2.5-0.5B (域路由)', 'Ollama', 'OPENAI_COMPATIBLE', 'ROUTER',
        'http://localhost:11434/v1', 'PLAINTEXT:none',
        'qwen2.5:0.5b', 0.0, 32, 5, true, true);

-- ③ 会话域切换历史表
CREATE TABLE cs_conversation.cs_session_domain_switch (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      VARCHAR(100) NOT NULL,
    from_domain     VARCHAR(64),
    to_domain       VARCHAR(64)  NOT NULL,
    switch_type     VARCHAR(32)  NOT NULL,
    trigger_message TEXT,
    reason          TEXT,
    msg_seq         BIGINT,
    created_at      TIMESTAMPTZ  DEFAULT NOW()
);
CREATE INDEX idx_sds_session ON cs_conversation.cs_session_domain_switch(session_id);
CREATE INDEX idx_sds_created ON cs_conversation.cs_session_domain_switch(created_at);
COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.switch_type
    IS 'INITIAL=初始进入, ROUTER_MODEL=小模型检测, LLM_TOOL=大模型工具触发, USER_SELECTED=用户手动选择';

-- ④ __system__ 域：FAQ 路径通用意图（由运营维护，不再硬编码）
INSERT INTO cs_conversation.cs_domain (code, name, description, enabled)
VALUES ('__system__', '系统通用', 'FAQ 路径路由意图，系统保留域，勿删', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO cs_conversation.cs_intent (domain_id, code, name, description, enabled)
SELECT d.id, v.code, v.name, v.description, true
FROM cs_conversation.cs_domain d,
     (VALUES
        ('FAQ_QUERY',        'FAQ 问答',   '咨询产品、服务、政策等业务问题'),
        ('TRANSFER_REQUEST', '转人工',     '要求转人工客服，如"我要真人"、"转客服"'),
        ('COMPLAINT',        '投诉',       '投诉、强烈不满，如"投诉"、"要求赔偿"'),
        ('CHITCHAT',         '闲聊',       '闲聊、问候，与业务无关'),
        ('OUT_OF_SCOPE',     '超出范围',   '与本业务完全无关的话题'),
        ('UNKNOWN',          '未知',       '无法判断意图')
     ) AS v(code, name, description)
WHERE d.code = '__system__'
ON CONFLICT (domain_id, code) DO NOTHING;
```

- [ ] **Step 2: 执行脚本（开发数据库）**

```bash
psql -U postgres -d aria_dev -f docs/sql/migration-005-router-and-domain-switch.sql
```
Expected: 无报错，`cs_session_domain_switch` 表已创建

---

### Task 2.2：AiModelConfigProvider 接口扩展

- [ ] **Step 1: 修改 `AiModelConfigProvider.java`，新增 ROUTER 相关方法**

```java
// ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelConfigProvider.java
public interface AiModelConfigProvider {
    AiModelConfig getActive();              // CHAT（已有）
    AiModelConfig getActiveEmbedding();     // EMBEDDING（已有）
    AiModelConfig getActiveRouter();        // ROUTER（新增）

    void invalidate();                      // 失效 CHAT（已有）
    void invalidateEmbedding();             // 失效 EMBEDDING（已有）
    void invalidateRouter();               // 失效 ROUTER（新增）
}
```

---

### Task 2.3：RemoteAiModelConfigProvider 实现扩展

- [ ] **Step 1: 在 `RemoteAiModelConfigProvider.java` 新增约 25 行（对称于现有 EMBEDDING 逻辑）**

在常量区新增：
```java
private static final String ROUTER_CACHE_KEY = "aria:ai:model:router:active";
```

新增两个方法：
```java
@Override
public AiModelConfig getActiveRouter() {
    String cached = redis.get(ROUTER_CACHE_KEY);
    if (cached != null) return parse(cached);
    AiModelConfig config = fetchFromAuthService("/internal/ai-models/active-router");
    redis.set(ROUTER_CACHE_KEY, serialize(config), Duration.ofMinutes(5));
    return config;
}

@Override
public void invalidateRouter() {
    redis.delete(ROUTER_CACHE_KEY);
    log.debug("[AiModel] Router config cache invalidated");
}
```

在现有 `onConfigChanged()` 方法中补充一行：
```java
private void onConfigChanged(String message) {
    invalidate();            // CHAT（已有）
    invalidateEmbedding();   // EMBEDDING（已有）
    invalidateRouter();      // ROUTER（新增）
    log.info("[AiModel] All model config caches invalidated");
}
```

- [ ] **Step 2: 编译 common-web**

```bash
./mvnw compile -pl ai-common/common-web -am -q
```
Expected: `BUILD SUCCESS`

---

### Task 2.4：auth-service 内部端点

- [ ] **Step 1: 在 `InternalAiModelController.java` 新增一个端点**

找到现有 `@GetMapping("/active-embedding")` 方法，在其下方添加：
```java
/**
 * 获取激活的 ROUTER 类型模型配置（供 conversation-service 等内部调用）。
 * 通过 X-Internal-Secret 头验证，Nginx 应屏蔽外部访问此路径。
 */
@GetMapping("/active-router")
public AiModelConfigVO getActiveRouter() {
    return service.getActiveByType("ROUTER");
}
```

`service.getActiveByType(String type)` 是已有的通用方法，无需修改 `AiModelConfigService`。

- [ ] **Step 2: 编译 auth-service**

```bash
./mvnw compile -pl ai-auth/auth-service -am -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 运行 auth-service 测试**

```bash
./mvnw test -pl ai-auth/auth-service -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add docs/sql/migration-005-router-and-domain-switch.sql \
        ai-common/common-web/src/main/java/com/aria/common/web/ai/AiModelConfigProvider.java \
        ai-common/common-web/src/main/java/com/aria/common/web/ai/RemoteAiModelConfigProvider.java \
        ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/InternalAiModelController.java
git commit -m "feat(model): Phase2 - ROUTER 模型类型 + AiModelConfigProvider 扩展 + /active-router 端点"
```

## Phase 3：IntentService + SlotService（结构化输出 + Spike 验证）

**目标：** 用 LangChain4j AI Services 替换 `IntentClassifier` 和 `SlotExtractService`；意图描述从 `__system__` 域动态读取；先做 Flux streaming Spike 验证。

**涉及文件：**
- Create: `…/domain/service/IntentService.java`
- Create: `…/domain/service/SlotService.java`
- Create: `…/infrastructure/ai/LangChain4jIntentService.java`
- Create: `…/infrastructure/ai/LangChain4jSlotService.java`
- Modify: `…/application/service/ChatAppService.java`（替换注入）
- Create: 对应测试文件

---

### Task 3.1：Spike — 验证 Flux streaming + AI Services

- [ ] **Step 1: 在 `conversation-service/pom.xml` 添加 test scope 依赖**

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

- [ ] **Step 2: 写 Spike 测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/FluxStreamingSpike.java
package com.aria.conversation.infrastructure.ai;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FluxStreamingSpike {

    interface StreamAssistant {
        Flux<String> chat(@UserMessage String message);
    }

    @Test
    void aiServices_fluxStreaming_works() {
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

- [ ] **Step 3: 运行 Spike，确认可用**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=FluxStreamingSpike -q 2>&1 | tail -5
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`

> 若 FAIL，停止 Phase 3，评估备选方案（`TokenStream` 或手动 `Sinks`），再继续。

---

### Task 3.2：领域层接口

- [ ] **Step 1: 创建 `IntentService.java`**

```java
// src/main/java/com/aria/conversation/domain/service/IntentService.java
package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.IntentResult;

/**
 * 意图识别领域服务接口。
 * 接口定义在领域层，LangChain4j 实现在 infrastructure 层，保持 DDD 分层。
 */
public interface IntentService {
    /**
     * 对用户消息进行意图分类。
     * 分类失败时返回 {@link IntentResult#UNKNOWN}，不抛出异常。
     */
    IntentResult classify(String userMessage);
}
```

- [ ] **Step 2: 创建 `SlotService.java`**

```java
// src/main/java/com/aria/conversation/domain/service/SlotService.java
package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;

import java.util.List;
import java.util.Map;

/** Slot 提取领域服务接口。 */
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

### Task 3.3：LangChain4jIntentService

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentServiceTest.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class LangChain4jIntentServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    @Mock private DomainRepository domainRepo;
    @Mock private IntentConfig faqIntent;
    @Mock private IntentConfig transferIntent;

    private LangChain4jIntentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(faqIntent.code()).thenReturn("FAQ_QUERY");
        when(faqIntent.description()).thenReturn("咨询产品、服务、政策等业务问题");
        when(transferIntent.code()).thenReturn("TRANSFER_REQUEST");
        when(transferIntent.description()).thenReturn("要求转人工客服");
        when(domainRepo.loadIntents(LangChain4jIntentService.SYSTEM_DOMAIN_CODE))
                .thenReturn(List.of(faqIntent, transferIntent));
        service = new LangChain4jIntentService(modelFactory, domainRepo);
    }

    @Test
    void classify_validIntent_returnsParsed() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("FAQ_QUERY");
        when(modelFactory.getChatModel()).thenReturn(mock);

        IntentResult result = service.classify("我的订单在哪里");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    void classify_unknownResponse_returnsUnknown() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("GARBAGE");
        when(modelFactory.getChatModel()).thenReturn(mock);

        IntentResult result = service.classify("test");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    void classify_emptyIntentList_returnsUnknown() {
        when(domainRepo.loadIntents(anyString())).thenReturn(List.of());
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("FAQ_QUERY");
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
Expected: FAIL

- [ ] **Step 3: 实现 `LangChain4jIntentService.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/LangChain4jIntentService.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LangChain4j 的意图分类实现。
 *
 * <p>替代 {@link IntentClassifier}，意图描述从 {@code __system__} 域动态读取，
 * 运营可在 DIT 后台修改意图说明，无需改代码重部署。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jIntentService implements IntentService {

    /** DIT 系统保留域 code，存储 FAQ 路径通用意图 */
    public static final String SYSTEM_DOMAIN_CODE = "__system__";

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepo;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            List<IntentConfig> intents = domainRepo.loadIntents(SYSTEM_DOMAIN_CODE);
            if (intents.isEmpty()) {
                log.warn("[Intent] __system__ 域意图列表为空，降级为 UNKNOWN");
                return IntentResult.UNKNOWN;
            }
            String systemPrompt = buildClassifyPrompt(intents);
            List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userMessage)
            );
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text().strip();
            return new IntentResult(parseIntentType(response), 1.0);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }

    private String buildClassifyPrompt(List<IntentConfig> intents) {
        String codes = intents.stream().map(IntentConfig::code).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("你是一个用户意图分类器。分析用户输入，返回以下枚举之一，不要输出任何其他内容：\n");
        sb.append(codes).append("\n\n意图说明：\n");
        intents.forEach(i -> sb.append("- ").append(i.code()).append("：").append(i.description()).append("\n"));
        sb.append("\n只输出枚举值，不要解释。");
        return sb.toString();
    }

    private IntentType parseIntentType(String response) {
        try {
            return IntentType.valueOf(response.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[Intent] 未知意图值: {}, 降级为 UNKNOWN", response);
            return IntentType.UNKNOWN;
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jIntentServiceTest -q 2>&1 | tail -5
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

---

### Task 3.4：LangChain4jSlotService

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
        service = new LangChain4jSlotService(modelFactory, new com.fasterxml.jackson.databind.ObjectMapper());
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
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("not json");
        when(modelFactory.getChatModel()).thenReturn(mock);
        SlotConfig slot = new SlotConfig("order_id", "订单号", "string", null, false, null);

        Map<String, Object> result = service.extract("test", List.of(), List.of(slot));

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 实现 `LangChain4jSlotService.java`**

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
 * 基于 LangChain4j 的 Slot 提取实现，替代 {@link SlotExtractService}。
 *
 * <p>slot 结构动态（每个 domain 不同），沿用手写 prompt + JSON 解析，
 * 底层 LLM 调用切换到 {@link DynamicModelFactory}。
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
            List<dev.langchain4j.data.message.ChatMessage> messages =
                    buildMessages(userMessage, recentHistory, systemPrompt);
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
            return parseExtracted(response);
        } catch (Exception e) {
            log.warn("[DIT] 槽位提取失败 slots={}",
                    slots.stream().map(SlotConfig::slotName).toList(), e);
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

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=LangChain4jSlotServiceTest -q 2>&1 | tail -5
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

---

### Task 3.5：ChatAppService 切换接口

- [ ] **Step 1: 将 `IntentClassifier` 替换为 `IntentService`**

```java
// 删除
private final IntentClassifier intentClassifier;
// 添加
private final IntentService intentClassifier;
```
删除 import `IntentClassifier`，添加 import `IntentService`。调用点 `intentClassifier.classify(message)` 不变。

- [ ] **Step 2: 将 `SlotExtractService` 替换为 `SlotService`（在 SlotResolver 中）**

找到注入 `SlotExtractService` 的 `SlotResolver`，将字段类型改为 `SlotService`，删除 import，添加 import `SlotService`。调用点不变。

- [ ] **Step 3: 编译 + 全量测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(ai): Phase3 - LangChain4j IntentService/SlotService 替换手写 JSON 解析，意图描述从 __system__ 域读取"
```

## Phase 4：域路由 + 会话域切换历史

**目标：** 实现 `SessionDomainRepository`（Redis）、`SessionDomainSwitchRepository`（DB）、`DomainRouterService`（ROUTER 小模型），在 `ChatAppService.stream()` domain 路径集成三段路由逻辑。

**涉及文件：**
- Create: `…/infrastructure/dit/domain/SessionDomainSwitchDO.java`
- Create: `…/infrastructure/dit/domain/SwitchType.java`
- Create: `…/infrastructure/dit/mapper/SessionDomainSwitchMapper.java`
- Create: `…/infrastructure/dit/repository/SessionDomainRepository.java`
- Create: `…/infrastructure/dit/repository/SessionDomainSwitchRepository.java`
- Create: `…/infrastructure/dit/pipeline/DomainRouterService.java`
- Modify: `…/application/service/ChatAppService.java`
- Create: 对应测试文件

---

### Task 4.1：SwitchType 常量类

- [ ] **Step 1: 创建 `SwitchType.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/domain/SwitchType.java
package com.aria.conversation.infrastructure.dit.domain;

/** 会话域切换类型常量（阿里规范 §1.4 禁止魔法字符串）。 */
public final class SwitchType {
    public static final String INITIAL       = "INITIAL";       // 初始进入
    public static final String ROUTER_MODEL  = "ROUTER_MODEL";  // 小模型检测切换
    public static final String LLM_TOOL      = "LLM_TOOL";      // 大模型工具触发切换
    public static final String USER_SELECTED = "USER_SELECTED"; // 用户手动选择（预留）
    private SwitchType() {}
}
```

---

### Task 4.2：SessionDomainSwitchDO + Mapper

- [ ] **Step 1: 创建 `SessionDomainSwitchDO.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/domain/SessionDomainSwitchDO.java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 会话域切换历史（映射 cs_conversation.cs_session_domain_switch 表）。 */
@Data
@TableName("cs_session_domain_switch")
public class SessionDomainSwitchDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    /** 切换前的域 code，初始进入时为 null */
    private String fromDomain;

    /** 切换后的域 code */
    private String toDomain;

    /** 切换类型，见 {@link SwitchType} */
    private String switchType;

    /** 触发切换的用户消息原文，用于分析跨域切换原因 */
    private String triggerMessage;

    /** 切换原因（小模型评分 / LLM 工具参数） */
    private String reason;

    /** 关联 cs_conversation_message.seq，定位切换发生在哪条消息 */
    private Long msgSeq;

    private OffsetDateTime createdAt;
}
```

- [ ] **Step 2: 创建 `SessionDomainSwitchMapper.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/mapper/SessionDomainSwitchMapper.java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SessionDomainSwitchMapper extends BaseMapper<SessionDomainSwitchDO> {

    @Select("SELECT * FROM cs_session_domain_switch WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<SessionDomainSwitchDO> findBySessionId(@Param("sessionId") String sessionId);
}
```

---

### Task 4.3：SessionDomainRepository（Redis）

- [ ] **Step 1: 创建 `SessionDomainRepository.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/repository/SessionDomainRepository.java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话当前激活域缓存（Redis）。
 *
 * <p>key: {@code dit:session_domain:{sessionId}}，TTL 2 小时。
 * 记录每个 session 当前正在处理的业务域，支持会话内跨域切换。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionDomainRepository {

    private static final String KEY_PREFIX = "dit:session_domain:";
    private static final Duration TTL = Duration.ofHours(2);

    private final RedisCacheHelper cache;

    public void save(String sessionId, String domainCode) {
        cache.set(KEY_PREFIX + sessionId, domainCode, TTL);
        log.debug("[Domain] 更新 session 激活域 sessionId={} domain={}", sessionId, domainCode);
    }

    public Optional<String> find(String sessionId) {
        return Optional.ofNullable(cache.get(KEY_PREFIX + sessionId));
    }

    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
    }
}
```

---

### Task 4.4：SessionDomainSwitchRepository（DB）

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/dit/repository/SessionDomainSwitchRepositoryTest.java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.mapper.SessionDomainSwitchMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class SessionDomainSwitchRepositoryTest {

    @Mock private SessionDomainSwitchMapper mapper;
    @InjectMocks private SessionDomainSwitchRepository repository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() { MockitoAnnotations.openMocks(this); }

    @Test
    void record_insertsCorrectFields() {
        repository.record("sess1", "order", "logistics",
                SwitchType.ROUTER_MODEL, "我的快递在哪", "小模型检测", 42L);

        ArgumentCaptor<SessionDomainSwitchDO> captor =
                ArgumentCaptor.forClass(SessionDomainSwitchDO.class);
        verify(mapper).insert(captor.capture());

        SessionDomainSwitchDO saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo("sess1");
        assertThat(saved.getFromDomain()).isEqualTo("order");
        assertThat(saved.getToDomain()).isEqualTo("logistics");
        assertThat(saved.getSwitchType()).isEqualTo(SwitchType.ROUTER_MODEL);
        assertThat(saved.getMsgSeq()).isEqualTo(42L);
    }

    @Test
    void record_mapperFails_doesNotThrow() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB error")).when(mapper).insert(org.mockito.ArgumentMatchers.any());

        // 写入失败不应影响主流程
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            repository.record("sess1", null, "shop", SwitchType.INITIAL, null, null, null));
    }
}
```

- [ ] **Step 2: 实现 `SessionDomainSwitchRepository.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/repository/SessionDomainSwitchRepository.java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.aria.conversation.infrastructure.dit.mapper.SessionDomainSwitchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话域切换历史 DB 仓储。
 *
 * <p>写入失败不影响主流程（catch 后仅打 ERROR 日志）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionDomainSwitchRepository {

    private final SessionDomainSwitchMapper mapper;

    /**
     * 记录一条域切换历史。
     *
     * @param sessionId      会话 ID
     * @param fromDomain     切换前的域（初始进入时为 null）
     * @param toDomain       切换后的域
     * @param switchType     切换类型，见 {@link com.aria.conversation.infrastructure.dit.domain.SwitchType}
     * @param triggerMessage 触发切换的用户消息原文
     * @param reason         切换原因描述
     * @param msgSeq         关联 cs_conversation_message.seq（可为 null）
     */
    public void record(String sessionId, String fromDomain, String toDomain,
                       String switchType, String triggerMessage, String reason, Long msgSeq) {
        try {
            SessionDomainSwitchDO record = new SessionDomainSwitchDO();
            record.setSessionId(sessionId);
            record.setFromDomain(fromDomain);
            record.setToDomain(toDomain);
            record.setSwitchType(switchType);
            record.setTriggerMessage(triggerMessage);
            record.setReason(reason);
            record.setMsgSeq(msgSeq);
            mapper.insert(record);
            log.info("[Domain] 记录域切换 sessionId={} {}→{} type={}",
                    sessionId, fromDomain, toDomain, switchType);
        } catch (Exception e) {
            log.error("[Domain] 记录域切换失败 sessionId={}", sessionId, e);
        }
    }

    /** 查询会话完整域切换历史（按时间升序）。 */
    public List<SessionDomainSwitchDO> findHistory(String sessionId) {
        return mapper.findBySessionId(sessionId);
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=SessionDomainSwitchRepositoryTest -q 2>&1 | tail -5
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`

---

### Task 4.5：DomainRouterService

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/conversation/infrastructure/dit/pipeline/DomainRouterServiceTest.java
package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainRouterServiceTest {

    @Mock private DomainRepository domainRepo;
    @Mock private DynamicModelFactory modelFactory;

    private DomainRouterService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        DomainConfig orderDomain = mock(DomainConfig.class);
        when(orderDomain.code()).thenReturn("order");
        when(orderDomain.description()).thenReturn("订单查询、取消、退款");
        DomainConfig logisticsDomain = mock(DomainConfig.class);
        when(logisticsDomain.code()).thenReturn("logistics");
        when(logisticsDomain.description()).thenReturn("物流查询、快递追踪");
        when(domainRepo.findAllEnabled()).thenReturn(List.of(orderDomain, logisticsDomain));
        service = new DomainRouterService(domainRepo, modelFactory);
    }

    @Test
    void route_sameAsCurrentDomain_noSwitch() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("order");
        when(modelFactory.getRouterModel()).thenReturn(mock);

        DomainRouterService.RouteResult result = service.route("我的订单在哪", "order", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        assertThat(result.suggestedDomain()).isEqualTo("order");
    }

    @Test
    void route_differentDomain_switches() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("logistics");
        when(modelFactory.getRouterModel()).thenReturn(mock);

        DomainRouterService.RouteResult result = service.route("我的快递到哪了", "order", List.of());

        assertThat(result.shouldSwitch()).isTrue();
        assertThat(result.suggestedDomain()).isEqualTo("logistics");
    }

    @Test
    void route_illegalCode_staysOnCurrentDomain() {
        ChatLanguageModel mock = ChatModelMock.thatAlwaysResponds("INVALID_GARBAGE");
        when(modelFactory.getRouterModel()).thenReturn(mock);

        DomainRouterService.RouteResult result = service.route("test", "order", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        assertThat(result.suggestedDomain()).isEqualTo("order");
    }

    @Test
    void route_singleDomain_noSwitch() {
        DomainConfig singleDomain = mock(DomainConfig.class);
        when(singleDomain.code()).thenReturn("order");
        when(domainRepo.findAllEnabled()).thenReturn(List.of(singleDomain));

        DomainRouterService.RouteResult result = service.route("test", "order", List.of());

        assertThat(result.shouldSwitch()).isFalse();
    }
}
```

- [ ] **Step 2: 实现 `DomainRouterService.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/dit/pipeline/DomainRouterService.java
package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 域路由服务。
 *
 * <p>用 ROUTER 小模型（约 50-200ms）判断当前消息是否需要切换到不同业务域，
 * 是两层域路由机制的第一层（第二层为 DomainAgentService 的 switch_domain 内置工具）。
 *
 * <p>路由失败或返回非法域 code 时，降级保持当前域，不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainRouterService {

    private final DomainRepository domainRepo;
    private final DynamicModelFactory modelFactory;

    public record RouteResult(String suggestedDomain, boolean shouldSwitch) {}

    /**
     * 判断当前消息是否需要切换域。
     *
     * @param userMessage   当前用户消息
     * @param currentDomain 当前会话激活域 code
     * @param recentHistory 最近 LangChain4j ChatMessage 列表（提供上下文）
     */
    public RouteResult route(String userMessage, String currentDomain,
                             List<dev.langchain4j.data.message.ChatMessage> recentHistory) {
        try {
            List<DomainConfig> domains = domainRepo.findAllEnabled();
            if (domains.size() <= 1) {
                return new RouteResult(currentDomain, false);
            }

            String prompt = buildRouterPrompt(domains, currentDomain, recentHistory, userMessage);
            String response = modelFactory.getRouterModel().chat(prompt).trim();

            boolean validCode = domains.stream()
                    .anyMatch(d -> d.code().equalsIgnoreCase(response));
            if (!validCode) {
                log.warn("[DomainRouter] 小模型返回非法域 code: {}, 保持当前域 {}", response, currentDomain);
                return new RouteResult(currentDomain, false);
            }

            boolean shouldSwitch = !response.equalsIgnoreCase(currentDomain);
            if (shouldSwitch) {
                log.info("[DomainRouter] 检测到域切换: {} → {}", currentDomain, response);
            }
            return new RouteResult(response, shouldSwitch);

        } catch (Exception e) {
            log.warn("[DomainRouter] 路由失败，保持当前域 domain={}", currentDomain, e);
            return new RouteResult(currentDomain, false);
        }
    }

    private String buildRouterPrompt(List<DomainConfig> domains, String currentDomain,
                                      List<dev.langchain4j.data.message.ChatMessage> history,
                                      String userMessage) {
        StringBuilder sb = new StringBuilder("你是一个领域路由器。根据用户最新消息，判断应该由哪个服务域处理。\n\n");
        sb.append("可用服务域：\n");
        domains.forEach(d -> sb.append("- ").append(d.code()).append("：").append(d.description()).append("\n"));

        // 注入最近 2 轮对话（4 条消息）提供上下文
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

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=DomainRouterServiceTest -q 2>&1 | tail -5
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

---

### Task 4.6：ChatAppService 集成域路由三段逻辑

- [ ] **Step 1: 在 `ChatAppService` 中注入新依赖**

添加三个字段：
```java
private final SessionDomainRepository sessionDomainRepo;
private final SessionDomainSwitchRepository domainSwitchRepo;
private final DomainRouterService domainRouterService;
```
构造函数同步添加参数并赋值。添加对应 import。

- [ ] **Step 2: 修改 `stream()` 方法 domain 路径**

将：
```java
if (StringUtils.isNotBlank(domainCode)) {
    return streamChatWithDomain(sessionId, message, domainCode, Map.of());
}
```
替换为：
```java
if (StringUtils.isNotBlank(domainCode)) {
    // 1. 读/写 session 当前激活域（首次进入写 INITIAL 记录）
    String activeDomain = sessionDomainRepo.find(sessionId).orElseGet(() -> {
        sessionDomainRepo.save(sessionId, domainCode);
        domainSwitchRepo.record(sessionId, null, domainCode,
                SwitchType.INITIAL, message, "用户进入服务入口", null);
        return domainCode;
    });

    // 2. ROUTER 小模型域路由判断（~50-200ms，失败时降级保持当前域）
    List<dev.langchain4j.data.message.ChatMessage> recentHistory =
            sessionChatMemoryStore.getMessages(sessionId); // Phase 5 后可用；此阶段传 List.of()
    DomainRouterService.RouteResult routing =
            domainRouterService.route(message, activeDomain, List.of());
    if (routing.shouldSwitch()) {
        String newDomain = routing.suggestedDomain();
        pendingSlotRepo.delete(sessionId);
        sessionDomainRepo.save(sessionId, newDomain);
        domainSwitchRepo.record(sessionId, activeDomain, newDomain,
                SwitchType.ROUTER_MODEL, message,
                "小模型检测切换", null);
        activeDomain = newDomain;
    }

    // 3. 进入 DIT pipeline（Phase 5 替换为 DomainAgentService）
    return streamChatWithDomain(sessionId, message, activeDomain, Map.of());
}
```

注意：`pendingSlotRepo` 已在 `ChatAppService` 中，直接使用。`sessionChatMemoryStore` 在 Phase 5 前用 `List.of()` 占位。

- [ ] **Step 3: 新增域切换历史查询接口**

在 `DitManageAppService` 中新增：
```java
public List<SessionDomainSwitchDO> getSessionDomainHistory(String sessionId) {
    return domainSwitchRepo.findHistory(sessionId);
}
```

在 `DitDomainController`（或新建 `SessionDomainController`）中新增：
```java
@GetMapping("/api/v1/admin/sessions/{sessionId}/domain-history")
@SaCheckLogin
public R<List<SessionDomainSwitchDO>> getDomainHistory(@PathVariable String sessionId) {
    return R.ok(manageService.getSessionDomainHistory(sessionId));
}
```

- [ ] **Step 4: 编译 + 全量测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(domain): Phase4 - DomainRouterService 小模型域路由 + 会话域切换历史持久化"
```

## Phase 5：DomainAgentService + 策略模式重构（核心）

**目标：** 用 LangChain4j 原生 function calling 替代 `DitPipeline`；实现 `RouteResultHandler` / `HttpAuthStrategy` / `SlotResolutionStrategy` 策略模式；实现 `SessionChatMemoryStore`。

**涉及文件（新增）：**
- `…/infrastructure/ai/SessionChatMemoryStore.java`
- `…/application/service/route/RouteResultHandler.java`（接口）
- `…/application/service/route/TransferRouteHandler.java`
- `…/application/service/route/PendingRouteHandler.java`
- `…/application/service/route/ExecuteRouteHandler.java`
- `…/application/service/route/FallbackRouteHandler.java`
- `…/application/service/DomainAgentService.java`
- `…/infrastructure/dit/pipeline/HttpAuthStrategy.java`（接口）
- `…/infrastructure/dit/pipeline/BearerAuthStrategy.java`
- `…/infrastructure/dit/pipeline/ApiKeyAuthStrategy.java`
- `…/infrastructure/dit/pipeline/NoAuthStrategy.java`
- `…/infrastructure/dit/pipeline/SlotResolutionStrategy.java`（接口）
- `…/infrastructure/dit/pipeline/ExtractSlotStrategy.java`
- `…/infrastructure/dit/pipeline/SessionSlotStrategy.java`
- `…/infrastructure/dit/pipeline/DiscoverSlotStrategy.java`
- `…/infrastructure/dit/pipeline/AskUserSlotStrategy.java`
- `…/infrastructure/dit/pipeline/SlotResolutionContext.java`
- `…/infrastructure/dit/pipeline/SlotStrategyOutcome.java`

**修改：**
- `HttpToolRunner.java`（移除 authType if/else，改用策略）
- `SlotResolver.java`（移除 strategy switch，改用策略）
- `ChatAppService.java`（domain 路径切到 DomainAgentService，buildDomainEventStream → RouteResultHandler）
- `ConversationHistoryRepository.java`（新增 `saveAll()` replace 语义）

**删除（Phase 5 完成后）：**
- `DitPipeline.java`
- `ToolExecutor.java`
- `DomainIntentClassifier.java`
- `IntentClassifier.java`（已被 LangChain4jIntentService 替代）
- `SlotExtractService.java`（已被 LangChain4jSlotService 替代）
- `DynamicAiClient.java`（已被 DynamicModelFactory 替代）

---

### Task 5.1：ConversationHistoryRepository 新增 saveAll()

- [ ] **Step 1: 在 `ConversationHistoryRepository` 接口新增方法**

```java
/**
 * 全量替换会话历史（replace 语义，非 append）。
 * LangChain4j ChatMemoryStore 的 updateMessages() 契约要求全量替换。
 */
void saveAll(String sessionId, List<ConversationMessage> messages);
```

- [ ] **Step 2: 在实现类（`ConversationPersistRepository` 或 Redis 实现）中实现此方法**

```java
@Override
public void saveAll(String sessionId, List<ConversationMessage> messages) {
    // 先删除，再批量插入，保证 replace 语义
    delete(sessionId);
    messages.forEach(m -> append(sessionId, m.role(), m.content()));
}
```

---

### Task 5.2：SessionChatMemoryStore

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
        store.updateMessages("s1", List.of(UserMessage.from("hello"), AiMessage.from("hi")));
        verify(historyRepo).saveAll(eq("s1"), anyList());
    }

    @Test
    void deleteMessages_callsDelete() {
        store.deleteMessages("s1");
        verify(historyRepo).deleteBySessionId("s1");
    }
}
```

- [ ] **Step 2: 实现 `SessionChatMemoryStore.java`**

```java
// src/main/java/com/aria/conversation/infrastructure/ai/SessionChatMemoryStore.java
package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j ChatMemoryStore 适配器（ACL）。
 *
 * <p>LangChain4j {@link ChatMessage} 类型仅在此类内部使用，不向外暴露。
 * 对外仍使用项目领域类型 {@link ConversationMessage}。
 *
 * <p>{@link #updateMessages} 使用全量替换语义，依赖 {@link ConversationHistoryRepository#saveAll}。
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

    private ChatMessage toLangChain4jMessage(ConversationMessage m) {
        return switch (m.role()) {
            case "assistant" -> AiMessage.from(m.content());
            case "tool"      -> ToolExecutionResultMessage.from(m.content(), m.content(), m.content());
            default          -> UserMessage.from(m.content());
        };
    }

    private ConversationMessage toDomainMessage(ChatMessage m) {
        if (m instanceof AiMessage ai)
            return new ConversationMessage("assistant", ai.text(), 0L);
        if (m instanceof ToolExecutionResultMessage tr)
            return new ConversationMessage("tool", tr.text(), 0L);
        if (m instanceof UserMessage um)
            return new ConversationMessage("user", um.singleText(), 0L);
        return new ConversationMessage("user", m.toString(), 0L);
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -Dtest=SessionChatMemoryStoreTest -q 2>&1 | tail -5
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

---

### Task 5.3：HttpAuthStrategy 策略

- [ ] **Step 1: 创建接口 + 三个实现**

```java
// infrastructure/dit/pipeline/HttpAuthStrategy.java
package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

public interface HttpAuthStrategy {
    String authType();
    void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper);
}
```

```java
// BearerAuthStrategy.java
@Slf4j @Component
public class BearerAuthStrategy implements HttpAuthStrategy {
    @Override public String authType() { return "BEARER"; }
    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        try {
            String token = mapper.readTree(authConfig).path("token_encrypted").asText("");
            if (!token.isBlank()) headers.setBearerAuth(token);
        } catch (Exception e) {
            log.warn("[DIT] BEARER 认证头解析失败", e);
        }
    }
}
```

```java
// ApiKeyAuthStrategy.java
@Slf4j @Component
public class ApiKeyAuthStrategy implements HttpAuthStrategy {
    @Override public String authType() { return "API_KEY"; }
    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        try {
            var auth = mapper.readTree(authConfig);
            String headerName = auth.path("header").asText("X-API-Key");
            String value = auth.path("value_encrypted").asText("");
            if (!value.isBlank()) headers.set(headerName, value);
        } catch (Exception e) {
            log.warn("[DIT] API_KEY 认证头解析失败", e);
        }
    }
}
```

```java
// NoAuthStrategy.java
@Component
public class NoAuthStrategy implements HttpAuthStrategy {
    @Override public String authType() { return "NONE"; }
    @Override public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {}
}
```

- [ ] **Step 2: 修改 `HttpToolRunner` 构造函数和 `buildHeaders()`**

构造函数新增参数 `List<HttpAuthStrategy> authStrategies`，转为 Map：
```java
this.authStrategyMap = authStrategies.stream()
        .collect(Collectors.toMap(HttpAuthStrategy::authType, s -> s));
```

`buildHeaders()` 替换 if/else 为：
```java
authStrategyMap.getOrDefault(
        tool.authType() != null ? tool.authType() : "NONE",
        authStrategyMap.get("NONE"))
    .apply(headers, tool.authConfig(), objectMapper);
```

- [ ] **Step 3: 编译 + 测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

### Task 5.4：RouteResultHandler 策略 + DomainAgentService

> 本 Task 较大，分步实施。

- [ ] **Step 1: 创建 `RouteResultHandler` 接口**

```java
// application/service/route/RouteResultHandler.java
package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import reactor.core.publisher.Flux;
import java.util.Map;

public interface RouteResultHandler {
    boolean supports(RouteResult route);
    Flux<ChatEvent> handle(String sessionId, String userMessage,
                           RouteResult route, Map<String, Object> sessionCtx);
}
```

- [ ] **Step 2: 实现 `TransferRouteHandler`、`PendingRouteHandler`、`FallbackRouteHandler`**

各自只包含对应 RouteResult 类型的处理逻辑，从 `ChatAppService.buildDomainEventStream()` 中剥离。每个文件约 30-40 行，结构与 `AiProtocolHandler` 实现类一致。

（实现代码参见设计文档 Section 11.4）

- [ ] **Step 3: 修改 `ChatAppService.buildDomainEventStream()` 为策略 dispatch**

```java
private final List<RouteResultHandler> routeHandlers;

private Flux<ChatEvent> buildDomainEventStream(String sessionId, String userMessage,
                                                RouteResult route, Map<String, Object> sessionCtx) {
    return routeHandlers.stream()
            .filter(h -> h.supports(route))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "No handler for RouteResult: " + route.getClass().getSimpleName()))
            .handle(sessionId, userMessage, route, sessionCtx);
}
```

- [ ] **Step 4: 实现 `DomainAgentService`**

核心逻辑：per-request 构建 `DomainAssistant`，`ToolProvider` 实时从 DB 加载 `ToolConfig` 转 `ToolSpecification`，委托 `HttpToolRunner.execute()` 执行，`ChatModelListener` 注入 tool/transfer/domain_switch SSE 事件。

（完整实现参见设计文档 Section 4.7）

- [ ] **Step 5: ChatAppService domain 路径切换至 DomainAgentService**

```java
// stream() 方法 Phase 4 的第 3 段，将:
return streamChatWithDomain(sessionId, message, activeDomain, Map.of());
// 替换为:
return domainAgentService.streamChat(sessionId, activeDomain, message);
```

并补充 `SessionChatMemoryStore` 到 Phase 4 的 `recentHistory` 读取：
```java
List<dev.langchain4j.data.message.ChatMessage> recentHistory =
        sessionChatMemoryStore.getMessages(sessionId); // Phase 5 后正式启用
```

- [ ] **Step 6: 删除旧组件**

```bash
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/DitPipeline.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/ToolExecutor.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/pipeline/DomainIntentClassifier.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentClassifier.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/SlotExtractService.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/DynamicAiClient.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AiProtocolHandler.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/OpenAiCompatibleHandler.java
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/AnthropicHandler.java
```

- [ ] **Step 7: 编译 + 全量测试**

```bash
./mvnw test -pl ai-conversation/conversation-service -q 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add -A ai-conversation/conversation-service/src/
git commit -m "feat(dit): Phase5 - DomainAgentService LangChain4j function calling 替换 DitPipeline；RouteResultHandler/HttpAuthStrategy 策略模式重构"
```

## Phase 6：knowledge-service EmbeddingModel 替换（可与其他 Phase 并行）

**目标：** 用 LangChain4j `OpenAiEmbeddingModel` 替换手写 `OpenAiEmbeddingService`，保留热切换和批量向量化能力。

**涉及文件：**
- Modify: `ai-knowledge/knowledge-service/pom.xml`
- Create: `…/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java`
- Create: 对应测试文件

---

### Task 6.1：knowledge-service 调整依赖

- [ ] **Step 1: 修改 `ai-knowledge/knowledge-service/pom.xml`**

将原有：
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
</dependency>
```
替换为：
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
./mvnw compile -pl ai-knowledge/knowledge-service -am -q
```
Expected: `BUILD SUCCESS`

---

### Task 6.2：LangChain4jEmbeddingService

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingServiceTest.java
package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        AiModelConfig cfg = new AiModelConfig(1L, "emb", "OpenAI", "openai",
                "https://api.openai.com/v1", "sk-test",
                "text-embedding-3-small", 0.0, 0, 30);
        when(configProvider.getActiveEmbedding()).thenReturn(cfg);
        service = new LangChain4jEmbeddingService(configProvider, props);
    }

    @Test
    void embed_setsVectorsOnChunks() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(mockModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(vec))));
        service.overrideModelForTest(mockModel);

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("test text");
        service.embed(List.of(chunk));

        assertThat(chunk.getVector()).isEqualTo(vec);
    }

    @Test
    void encode_returnsVector() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.4f, 0.5f};
        when(mockModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(vec)));
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

- [ ] **Step 3: 实现 `LangChain4jEmbeddingService.java`**

```java
// src/main/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java
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
 * 基于 LangChain4j 的 Embedding 服务（替代手写 {@link OpenAiEmbeddingService}）。
 *
 * <p>支持任意 OpenAI 兼容端点（infinity / Ollama / ZhipuAI / OpenAI），
 * Caffeine 缓存按 config hash 热切换，无需重启。
 */
@Slf4j
@Service("realEmbeddingService")
@Profile("!test")
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider configProvider;
    private final EmbeddingProperties props;

    private final Cache<String, EmbeddingModel> modelCache = Caffeine.newBuilder()
            .maximumSize(5).expireAfterAccess(30, TimeUnit.MINUTES).build();

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
            EmbeddingModel model = getModel(); // 每批重拉 config，支持长任务热切换

            List<TextSegment> segments = batch.stream()
                    .map(c -> TextSegment.from(c.getContent())).toList();
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
        return modelCache.get(configHash(cfg), k -> {
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
./mvnw test -pl ai-knowledge/knowledge-service -Dtest=LangChain4jEmbeddingServiceTest -q 2>&1 | tail -5
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 全量测试**

```bash
./mvnw test -pl ai-knowledge/knowledge-service -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add ai-knowledge/knowledge-service/pom.xml \
        ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingService.java \
        ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/embedding/LangChain4jEmbeddingServiceTest.java
git commit -m "feat(knowledge): Phase6 - LangChain4j EmbeddingModel 替换 OpenAiEmbeddingService，Caffeine 热切换"
```

## 收尾：全量验证 + PR

### Task 7.1：全模块编译 + 测试

- [ ] **Step 1: 全量构建**

```bash
./mvnw clean test -q 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`，所有模块测试通过

- [ ] **Step 2: 确认旧类已无引用**

```bash
grep -r "DynamicAiClient\|IntentClassifier\|SlotExtractService\|DitPipeline\|ToolExecutor\|DomainIntentClassifier\|OpenAiEmbeddingService\|AiProtocolHandler\|OpenAiCompatibleHandler\|AnthropicHandler" \
  --include="*.java" \
  ai-conversation/conversation-service/src/main \
  ai-knowledge/knowledge-service/src/main
```
Expected: 无输出

- [ ] **Step 3: 验证域切换历史表存在**

```bash
psql -U postgres -d aria_dev -c "\d cs_session_domain_switch"
```
Expected: 表结构正常显示

- [ ] **Step 4: 验证 __system__ 域意图数据**

```bash
psql -U postgres -d aria_dev -c "SELECT code, description FROM cs_intent i JOIN cs_domain d ON d.id = i.domain_id WHERE d.code = '__system__' ORDER BY i.id"
```
Expected: 6 条意图记录（FAQ_QUERY / TRANSFER_REQUEST / COMPLAINT / CHITCHAT / OUT_OF_SCOPE / UNKNOWN）

---

### Task 7.2：提 PR

- [ ] **Step 1: 推分支**

```bash
git push -u origin feature/langchain4j-migration
```

- [ ] **Step 2: 创建 PR**

```bash
gh pr create \
  --title "feat: LangChain4j 1.1.0 全面迁移 + 域路由 + 策略模式重构（6 phases）" \
  --body "## 变更摘要

### 核心架构
- **Phase 1:** DynamicModelFactory + LlmModelBuilder 策略替换 DynamicAiClient，升级 LangChain4j 1.1.0
- **Phase 2:** ROUTER 模型类型扩展，前端三 Tab 管理（CHAT/EMBEDDING/ROUTER），__system__ 域意图动态化
- **Phase 3:** LangChain4jIntentService/SlotService 替换手写 JSON 解析，意图描述从 DB 读取
- **Phase 4:** DomainRouterService 小模型域路由 + SessionDomainSwitchRepository 切换历史持久化
- **Phase 5:** DomainAgentService LangChain4j 原生 function calling 替换 DitPipeline；RouteResultHandler/HttpAuthStrategy 策略模式重构
- **Phase 6:** LangChain4jEmbeddingService 替换 OpenAiEmbeddingService（Caffeine 热切换）

### 策略模式改造
- LlmModelBuilder：新增 LLM provider 只需加 @Component，不改 DynamicModelFactory
- HttpAuthStrategy：新增鉴权方式只需加 @Component，不改 HttpToolRunner
- RouteResultHandler：buildDomainEventStream 从 80 行压缩到 8 行
- SlotResolutionStrategy：DISCOVER/ASK_USER 副作用隔离，可独立测试

### 数据库变更
- migration-005: ai_model_config 扩展 ROUTER 类型 + cs_session_domain_switch 表 + __system__ 域数据

## 已验证
- 所有新组件有单元测试，./mvnw clean test 全量通过
- 旧类（DynamicAiClient / DitPipeline 等）已删除，无残留引用

## 注意
- PendingSlotRepository 暂保留，待断线重连场景专项验证后删除
- ROUTER 小模型默认指向本地 Ollama qwen2.5:0.5b，可在模型管理后台修改" \
  --base main
```
