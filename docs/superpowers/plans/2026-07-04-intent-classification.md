# 意图识别（Intent Classification）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 chat 流程中加入意图识别，让系统能自动判断用户是要"问问题"、"转人工"、"闲聊"还是"投诉"，并按不同路径处理。

**Architecture:** 在 `ChatAppService.streamChat()` 里，用已有的 `DynamicAiClient` 发一次轻量 LLM 分类请求，与 RAG 检索并行执行（`CompletableFuture.allOf`，不叠加延迟）。根据意图枚举决定：走 AI 问答、自动转人工、跳过 RAG 直接回复，或拒答。

**Tech Stack:** Java 21, Spring Boot 3.3.5, `DynamicAiClient`（已有）, `SessionQueueService`（已有）, JUnit 5 + Mockito（已有）

---

## 文件改动总览

| 操作 | 文件 | 说明 |
|---|---|---|
| 新建 | `infrastructure/ai/IntentType.java` | 意图枚举（5个值） |
| 新建 | `infrastructure/ai/IntentResult.java` | 分类结果 record（含置信度） |
| 新建 | `infrastructure/ai/IntentClassifier.java` | 调 LLM 做意图分类的 Spring Bean |
| 修改 | `application/service/ChatAppService.java` | 注入 IntentClassifier，并行执行，加路由分叉 |
| 新建测试 | `infrastructure/ai/IntentClassifierTest.java` | 单元测试（Mock DynamicAiClient） |
| 新建测试 | `application/service/ChatAppServiceIntentTest.java` | 意图路由单元测试 |

---
## 背景知识（写代码前先读这里）

### 项目包路径
所有代码在 `com.aria.conversation` 下，主要分三层：
- `interfaces/rest/` — Controller（HTTP 入口）
- `application/service/` — 应用服务（业务编排）
- `infrastructure/ai/` — AI 基础设施（LLM 调用）

### 现有的核心类
- **`DynamicAiClient`**：封装好的 LLM 客户端，`chat(messages, systemPrompt)` 发一次非流式请求并返回字符串。就是用这个来做意图分类。
- **`ChatMessage`**：一条消息，两个字段：`role`（"user"/"assistant"/"system"）和 `content`（文本内容）。
- **`SessionQueueService.enqueue(sessionId, userName, reason, tag)`**：触发转人工，把会话放入等待队列。
- **`ChatAppService.streamChat()`**：主聊天流程，我们主要改这里。

### 意图分类怎么工作？
简单来说：把用户说的话发给 LLM，LLM 返回一个 JSON，告诉我们用户的意图是什么。

例如用户说"我要投诉你们的服务"，LLM 返回：
```json
{"intent": "COMPLAINT", "confidence": 0.95}
```

然后程序根据 intent 的值决定下一步：
- `FAQ_QUERY` → 正常 RAG + LLM 回答
- `TRANSFER_REQUEST` → 自动转人工，不问用户
- `COMPLAINT` → 也自动转人工
- `CHITCHAT` → 跳过 RAG，直接 LLM 闲聊
- `OUT_OF_SCOPE` → 回复"我只能回答 xxx 相关的问题"
- `UNKNOWN` → 兜底，走正常流程

---
## Task 1: 创建意图枚举 IntentType

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentType.java`

- [ ] **Step 1: 创建枚举文件**

```java
package com.aria.conversation.infrastructure.ai;

/**
 * 用户意图枚举。
 *
 * <p>LLM 意图分类器返回这些值之一，主流程根据此值决定路由。
 *
 * <ul>
 *   <li>{@link #FAQ_QUERY}        — 知识问答，走 RAG + LLM 正常流程</li>
 *   <li>{@link #TRANSFER_REQUEST} — 用户明确/隐含要求转人工，自动入队</li>
 *   <li>{@link #COMPLAINT}        — 投诉，视为高优先级，自动转人工</li>
 *   <li>{@link #CHITCHAT}         — 闲聊/问候，跳过 RAG 直接 LLM 回复</li>
 *   <li>{@link #OUT_OF_SCOPE}     — 与业务完全无关，返回拒答模板</li>
 *   <li>{@link #UNKNOWN}          — 分类失败兜底，走 FAQ_QUERY 流程</li>
 * </ul>
 */
public enum IntentType {
    FAQ_QUERY,
    TRANSFER_REQUEST,
    COMPLAINT,
    CHITCHAT,
    OUT_OF_SCOPE,
    UNKNOWN
}
```

- [ ] **Step 2: 提交**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentType.java
git commit -m "feat(intent): add IntentType enum"
```

---

## Task 2: 创建分类结果 IntentResult

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentResult.java`

- [ ] **Step 1: 创建 record 文件**

```java
package com.aria.conversation.infrastructure.ai;

/**
 * 意图分类结果。
 *
 * @param intent     识别到的意图
 * @param confidence 置信度 0.0~1.0，LLM 返回字段缺失时默认 1.0
 */
public record IntentResult(IntentType intent, double confidence) {

    /** 兜底结果，分类失败时使用 */
    public static final IntentResult UNKNOWN = new IntentResult(IntentType.UNKNOWN, 1.0);

    /** 判断是否需要自动转人工（TRANSFER_REQUEST 或 COMPLAINT） */
    public boolean requiresTransfer() {
        return intent == IntentType.TRANSFER_REQUEST || intent == IntentType.COMPLAINT;
    }

    /** 判断是否可以跳过 RAG 检索 */
    public boolean skipRag() {
        return intent == IntentType.CHITCHAT || intent == IntentType.OUT_OF_SCOPE;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentResult.java
git commit -m "feat(intent): add IntentResult record"
```

---
## Task 3: 创建意图分类器 IntentClassifier

这是核心类。它把用户的消息发给 LLM，LLM 用 JSON 格式回复意图，这里负责解析结果。

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentClassifier.java`

- [ ] **Step 1: 创建 IntentClassifier**

```java
package com.aria.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户意图分类器。
 *
 * <p>调用 {@link DynamicAiClient#chat} 发起一次非流式 LLM 请求，
 * 要求 LLM 以 JSON 格式返回意图分类结果。
 *
 * <p>Prompt 设计参考 AWS Bedrock 实践：用轻量模型做分类，
 * 只定义业务需要的有限意图类别，不依赖外部 NLU 服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    /**
     * 意图分类系统 Prompt。
     * 明确告诉 LLM 只输出 JSON，不要任何多余解释。
     */
    private static final String CLASSIFY_SYSTEM_PROMPT = """
            你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
            {"intent": "<意图>", "confidence": <0.0到1.0的小数>}

            意图取值说明：
            - FAQ_QUERY：用户在咨询产品、服务、政策等业务相关问题
            - TRANSFER_REQUEST：用户明确或隐含地要求转人工客服（如"我要真人"、"转客服"、"人工"）
            - COMPLAINT：用户在投诉、表达强烈不满（如"投诉"、"要求赔偿"、"太差了"）
            - CHITCHAT：闲聊、问候、与业务无关的日常对话（如"你好"、"今天天气"）
            - OUT_OF_SCOPE：询问与本业务完全无关的话题（如问数学题、写代码）
            - UNKNOWN：无法判断

            只输出 JSON，不要解释。
            """;

    private final DynamicAiClient aiClient;
    private final ObjectMapper objectMapper;

    /**
     * 对用户消息进行意图分类。
     *
     * <p>分类失败（LLM 返回格式错误、网络异常等）时返回 {@link IntentResult#UNKNOWN}，
     * 不抛出异常，保证主流程不因分类失败中断。
     *
     * @param userMessage 用户输入的原始消息
     * @return 意图分类结果
     */
    public IntentResult classify(String userMessage) {
        try {
            List<ChatMessage> messages = List.of(new ChatMessage("user", userMessage));
            String response = aiClient.chat(messages, CLASSIFY_SYSTEM_PROMPT);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }

    /**
     * 解析 LLM 返回的 JSON 字符串为 IntentResult。
     *
     * <p>容错设计：
     * <ul>
     *   <li>LLM 可能在 JSON 外包裹 markdown 代码块（```json ... ```），需要提取</li>
     *   <li>intent 字段值不在枚举中时，降级为 UNKNOWN</li>
     *   <li>confidence 字段缺失时，默认 1.0</li>
     * </ul>
     */
    IntentResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return IntentResult.UNKNOWN;
        }
        // 提取 JSON：LLM 有时会输出 ```json\n{...}\n```
        String json = extractJson(response.trim());
        try {
            JsonNode node = objectMapper.readTree(json);
            String intentStr = node.path("intent").asText("UNKNOWN").toUpperCase();
            double confidence = node.path("confidence").asDouble(1.0);
            IntentType intent;
            try {
                intent = IntentType.valueOf(intentStr);
            } catch (IllegalArgumentException ex) {
                log.warn("[Intent] 未知意图值: {}, 降级为 UNKNOWN", intentStr);
                intent = IntentType.UNKNOWN;
            }
            return new IntentResult(intent, confidence);
        } catch (Exception e) {
            log.warn("[Intent] JSON 解析失败: {}", json, e);
            return IntentResult.UNKNOWN;
        }
    }

    /** 从可能包含 markdown 代码块的字符串中提取 JSON 部分 */
    private String extractJson(String text) {
        // 去掉 ```json 和 ``` 包裹
        if (text.startsWith("```")) {
            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}');
            if (start >= 0 && end >= start) {
                return text.substring(start, end + 1);
            }
        }
        return text;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/ai/IntentClassifier.java
git commit -m "feat(intent): add IntentClassifier using DynamicAiClient"
```

---
## Task 4: 为 IntentClassifier 编写单元测试

**Files:**
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/IntentClassifierTest.java`

- [ ] **Step 1: 写测试**

```java
package com.aria.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntentClassifier 意图分类器")
class IntentClassifierTest {

    @Mock
    private DynamicAiClient aiClient;

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier(aiClient, new ObjectMapper());
    }

    // ---- parseResponse 单元测试（不调 LLM）----

    @Test
    @DisplayName("parseResponse: 标准 JSON 正确解析")
    void parseResponse_standard() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}");
        assertEquals(IntentType.FAQ_QUERY, result.intent());
        assertEquals(0.9, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("parseResponse: markdown 代码块自动提取 JSON")
    void parseResponse_markdown() {
        String response = "```json\n{\"intent\":\"TRANSFER_REQUEST\",\"confidence\":0.95}\n```";
        IntentResult result = classifier.parseResponse(response);
        assertEquals(IntentType.TRANSFER_REQUEST, result.intent());
    }

    @Test
    @DisplayName("parseResponse: 未知意图值降级为 UNKNOWN")
    void parseResponse_unknownIntent() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"BANANA\",\"confidence\":0.8}");
        assertEquals(IntentType.UNKNOWN, result.intent());
    }

    @Test
    @DisplayName("parseResponse: confidence 缺失时默认 1.0")
    void parseResponse_missingConfidence() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"CHITCHAT\"}");
        assertEquals(IntentType.CHITCHAT, result.intent());
        assertEquals(1.0, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("parseResponse: 空字符串返回 UNKNOWN")
    void parseResponse_empty() {
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse("").intent());
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse(null).intent());
    }

    @Test
    @DisplayName("parseResponse: 非法 JSON 返回 UNKNOWN")
    void parseResponse_invalidJson() {
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse("not json at all").intent());
    }

    // ---- classify 集成测试（Mock DynamicAiClient）----

    @Test
    @DisplayName("classify: LLM 正常返回时解析意图")
    void classify_normal() {
        when(aiClient.chat(anyList(), anyString()))
                .thenReturn("{\"intent\":\"COMPLAINT\",\"confidence\":0.92}");

        IntentResult result = classifier.classify("我要投诉你们的服务太差了");

        assertEquals(IntentType.COMPLAINT, result.intent());
        assertEquals(0.92, result.confidence(), 0.001);
        verify(aiClient).chat(anyList(), anyString());
    }

    @Test
    @DisplayName("classify: LLM 抛出异常时降级为 UNKNOWN，不抛出")
    void classify_aiException_fallsBackToUnknown() {
        when(aiClient.chat(anyList(), anyString())).thenThrow(new RuntimeException("AI 超时"));

        IntentResult result = classifier.classify("测试消息");

        assertEquals(IntentType.UNKNOWN, result.intent());
        // 不应抛出异常
    }

    // ---- IntentResult 辅助方法测试 ----

    @Test
    @DisplayName("requiresTransfer: TRANSFER_REQUEST 和 COMPLAINT 为 true")
    void requiresTransfer() {
        assertTrue(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9).requiresTransfer());
        assertTrue(new IntentResult(IntentType.COMPLAINT, 0.9).requiresTransfer());
        assertFalse(new IntentResult(IntentType.FAQ_QUERY, 0.9).requiresTransfer());
        assertFalse(new IntentResult(IntentType.CHITCHAT, 0.9).requiresTransfer());
    }

    @Test
    @DisplayName("skipRag: CHITCHAT 和 OUT_OF_SCOPE 为 true")
    void skipRag() {
        assertTrue(new IntentResult(IntentType.CHITCHAT, 0.9).skipRag());
        assertTrue(new IntentResult(IntentType.OUT_OF_SCOPE, 0.9).skipRag());
        assertFalse(new IntentResult(IntentType.FAQ_QUERY, 0.9).skipRag());
        assertFalse(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9).skipRag());
    }
}
```

- [ ] **Step 2: 运行测试，确认全部通过**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service \
    -Dtest=IntentClassifierTest -q
```

预期输出：`BUILD SUCCESS`，0 failures

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/ai/IntentClassifierTest.java
git commit -m "test(intent): add IntentClassifier unit tests"
```

---
## Task 5: 修改 ChatAppService 接入意图路由

这是改动最大的一步。在 `streamChat()` 里加入意图识别，并行执行意图分类和 RAG 检索。

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java`

- [ ] **Step 1: 在文件顶部加 import 和字段，替换原有 `streamChat` 方法**

找到 `ChatAppService.java` 中现有的 `streamChat(String sessionId, String userMessage, List<KnowledgeSearchResult.Hit> hits)` 方法，替换整个类为以下内容（保留所有原有方法，仅在注入和 streamChat 上做修改）：

**新增字段（在构造函数里注入 IntentClassifier 和 SessionQueueService）：**

```java
// 在 ChatAppService 顶部加两个常量
private static final String OUT_OF_SCOPE_REPLY =
        "抱歉，我是专业的客服助手，只能回答业务相关的问题，无法帮您解答这个问题。";
private static final String TRANSFER_AUTO_REASON = "系统识别到用户需要人工服务";
private static final String TRANSFER_DEFAULT_TAG = "咨询";

// 新增两个字段
private final IntentClassifier intentClassifier;
private final SessionQueueService sessionQueueService;
```

**修改构造函数：**

```java
public ChatAppService(DynamicAiClient aiClient,
                      ConversationHistoryRepository historyRepository,
                      KnowledgeClient knowledgeServiceClient,
                      IntentClassifier intentClassifier,
                      SessionQueueService sessionQueueService) {
    this.aiClient             = aiClient;
    this.historyRepository    = historyRepository;
    this.knowledgeServiceClient      = knowledgeServiceClient;
    this.intentClassifier     = intentClassifier;
    this.sessionQueueService  = sessionQueueService;
}
```

**替换 `streamChat(String sessionId, String userMessage, List<KnowledgeSearchResult.Hit> hits)` 方法：**

```java
/**
 * 流式对话（带预检索 hits），含意图路由。
 *
 * <p>路由逻辑：
 * <ol>
 *   <li>意图分类与 RAG 检索并行执行，取两者最大延迟，不叠加</li>
 *   <li>TRANSFER_REQUEST / COMPLAINT → 自动入队转人工，返回提示流</li>
 *   <li>OUT_OF_SCOPE → 返回拒答模板流，不调 LLM</li>
 *   <li>CHITCHAT → 跳过 RAG，直接 LLM 回复</li>
 *   <li>FAQ_QUERY / UNKNOWN → 正常 RAG + LLM 流程</li>
 * </ol>
 */
public Flux<String> streamChat(String sessionId, String userMessage,
                               List<KnowledgeSearchResult.Hit> hits) {
    historyRepository.append(sessionId, ROLE_USER, userMessage);

    // 意图分类（已在调用方并行完成，hits 已传入；此处单独发起意图分类）
    IntentResult intent = intentClassifier.classify(userMessage);
    log.debug("[Chat] sessionId={} intent={} confidence={}", sessionId, intent.intent(), intent.confidence());

    // 路由：需要转人工
    if (intent.requiresTransfer()) {
        try {
            sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
            log.info("[Chat] 自动转人工 sessionId={} intent={}", sessionId, intent.intent());
        } catch (Exception e) {
            log.warn("[Chat] 自动转人工失败 sessionId={}", sessionId, e);
        }
        String reply = intent.intent() == IntentType.COMPLAINT
                ? "非常抱歉给您带来了不好的体验，我已为您转接人工客服，请稍候。"
                : "好的，我已为您转接人工客服，请稍候。";
        historyRepository.append(sessionId, ROLE_ASSISTANT, reply);
        return Flux.just(reply);
    }

    // 路由：超出业务范围
    if (intent.intent() == IntentType.OUT_OF_SCOPE) {
        historyRepository.append(sessionId, ROLE_ASSISTANT, OUT_OF_SCOPE_REPLY);
        return Flux.just(OUT_OF_SCOPE_REPLY);
    }

    // 路由：闲聊 → 跳过 RAG，hits 传空列表
    List<KnowledgeSearchResult.Hit> effectiveHits =
            intent.skipRag() ? List.of() : hits;

    String systemPrompt = buildSystemPrompt(effectiveHits);
    List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
    StringBuilder assistantReply = new StringBuilder();

    return aiClient.streamChat(aiPrompt, systemPrompt)
            .map(content -> {
                assistantReply.append(content);
                return content;
            })
            .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
            .onErrorResume(e -> Flux.just("抱歉，AI 服务暂时不可用，请稍后重试。"))
            .doFinally(signal -> {
                if (!assistantReply.isEmpty()) {
                    historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                }
            });
}
```

> ⚠️ 注意：`IntentType` 需要 import：`import com.aria.conversation.infrastructure.ai.IntentType;`
> 以及 `IntentResult`、`IntentClassifier` 也需要 import。

- [ ] **Step 2: 编译确认无报错**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/ChatAppService.java
git commit -m "feat(intent): integrate intent routing in ChatAppService"
```

---
## Task 6: ChatAppService 意图路由单元测试

**Files:**
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceIntentTest.java`

- [ ] **Step 1: 写测试**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.ai.IntentClassifier;
import com.aria.conversation.infrastructure.ai.IntentResult;
import com.aria.conversation.infrastructure.ai.IntentType;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 意图路由")
class ChatAppServiceIntentTest {

    @Mock private DynamicAiClient aiClient;
    @Mock private ConversationHistoryRepository historyRepository;
    @Mock private KnowledgeClient knowledgeServiceClient;
    @Mock private IntentClassifier intentClassifier;
    @Mock private SessionQueueService sessionQueueService;

    private ChatAppService service;

    @BeforeEach
    void setUp() {
        service = new ChatAppService(aiClient, historyRepository, knowledgeServiceClient,
                                     intentClassifier, sessionQueueService);
        // 默认 findAll 返回空列表，避免 NPE
        when(historyRepository.findAll(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("FAQ_QUERY: 正常调用 RAG + LLM 流式回复")
    void faqQuery_callsAiStream() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("这是", "回答"));

        Flux<String> result = service.streamChat("s1", "退款政策是什么？", List.of());

        StepVerifier.create(result)
                .expectNext("这是", "回答")
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
        verify(sessionQueueService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER_REQUEST: 自动入队转人工，返回提示文本，不调 LLM")
    void transferRequest_enqueuedAndNoAi() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.95));

        Flux<String> result = service.streamChat("s2", "我要找真人客服", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("人工客服"))
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s2"), anyString(), anyString(), anyString());
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("COMPLAINT: 自动入队转人工，回复包含道歉语")
    void complaint_enqueuedWithApology() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.COMPLAINT, 0.93));

        Flux<String> result = service.streamChat("s3", "你们服务太差了，我要投诉", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("抱歉") && msg.contains("人工客服"))
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s3"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("OUT_OF_SCOPE: 返回拒答模板，不调 LLM")
    void outOfScope_returnsTemplate() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.OUT_OF_SCOPE, 0.88));

        Flux<String> result = service.streamChat("s4", "帮我解一道微积分题", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("只能回答业务相关"))
                .verifyComplete();
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("CHITCHAT: 跳过 RAG（hits 有值也不用），直接调 LLM")
    void chitchat_skipsRag() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.CHITCHAT, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("你好！"));
        // 模拟 hits 有内容，但 CHITCHAT 应该跳过
        KnowledgeSearchResult.Hit hit = mock(KnowledgeSearchResult.Hit.class);

        Flux<String> result = service.streamChat("s5", "你好", List.of(hit));

        StepVerifier.create(result)
                .expectNext("你好！")
                .verifyComplete();
        // 验证 LLM 被调用时传入的 systemPrompt 不含【参考资料】（RAG 被跳过）
        verify(aiClient).streamChat(anyList(), argThat(prompt -> !prompt.contains("【参考资料】")));
    }

    @Test
    @DisplayName("意图分类异常（UNKNOWN）时降级走正常 FAQ 流程")
    void unknown_fallsBackToFaqFlow() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(IntentResult.UNKNOWN);
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("正常回答"));

        Flux<String> result = service.streamChat("s6", "随便问个问题", List.of());

        StepVerifier.create(result)
                .expectNext("正常回答")
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service \
    -Dtest="IntentClassifierTest,ChatAppServiceIntentTest" -q
```

预期输出：`BUILD SUCCESS`，0 failures

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/ChatAppServiceIntentTest.java
git commit -m "test(intent): add ChatAppService intent routing tests"
```

---
## Task 7: 手动冒烟测试（可选但推荐）

启动服务后用 curl 验证意图路由是否生效。

**前提：** `conversation-service` 已启动（端口 8082），LLM 配置已激活。

- [ ] **Step 1: 测试转人工意图自动触发**

```bash
curl -s -N -X POST http://localhost:8082/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-intent-001","message":"我要找真人客服，现在！"}' \
  --no-buffer
```

预期：SSE 流返回包含"人工客服"的提示，不是正常 AI 回答。

- [ ] **Step 2: 测试投诉自动转人工**

```bash
curl -s -N -X POST http://localhost:8082/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-intent-002","message":"你们的服务太差了，我要投诉！"}' \
  --no-buffer
```

预期：SSE 流返回包含"抱歉"和"人工客服"的文本。

- [ ] **Step 3: 测试闲聊跳过 RAG**

```bash
curl -s -N -X POST http://localhost:8082/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-intent-003","message":"你好，今天天气真好"}' \
  --no-buffer
```

预期：正常回复，不带 `event:sources`（因为 RAG 被跳过，没有 hits）。

- [ ] **Step 4: 验证转人工队列**

```bash
# 查看等待队列，应该能看到 test-intent-001 和 test-intent-002
curl -s http://localhost:8082/api/v1/sessions/queue | python3 -m json.tool
```

---

## 设计决策说明

以下是一些关键设计决策，方便后续维护时理解为什么这样做。

### 为什么意图分类不和 RAG 并行？

当前实现是先分类再决定要不要做 RAG。原因：
- `CHITCHAT` 和 `OUT_OF_SCOPE` 不需要 RAG，并行执行 RAG 会浪费资源
- `TRANSFER_REQUEST` 和 `COMPLAINT` 完全不走 LLM，并行 RAG 完全浪费

如果后续性能压测发现 `FAQ_QUERY` 占比极高（>80%），可以改为"意图分类与 RAG 同时发起，根据意图决定是否使用 RAG 结果"。但目前 YAGNI，先保持简单。

### 为什么自动转人工时用"访客"作为 userName？

`sessionId` 里没有用户姓名信息，`enqueue()` 需要 userName 参数。这里用"访客"作为占位，实际项目里可以从 session 上下文或前端传参获取。

### 意图置信度阈值

当前没有设置置信度阈值过滤，LLM 返回什么意图就用什么。如果上线后发现误触发转人工的情况（比如 confidence 0.3 的 TRANSFER_REQUEST），可以在 `ChatAppService` 加：

```java
// 置信度低于 0.7 时降级为 UNKNOWN
if (intent.confidence() < 0.7) {
    intent = IntentResult.UNKNOWN;
}
```

---

## 验收标准

- [ ] 所有新建测试通过（`IntentClassifierTest` + `ChatAppServiceIntentTest`）
- [ ] 原有测试不受影响（`SessionStatusTest` + `ChatWebSocketHandlerSessionIdTest`）
- [ ] 用户说"转人工/找真人/投诉"时，会话自动进入等待队列
- [ ] 用户闲聊时，SSE 响应里没有 `event:sources`
- [ ] 用户问业务无关问题时，返回拒答模板而不是 LLM 自由发挥
