# Agent 取消与可靠性改造技术方案

## 1. 背景与目标

### 1.1 背景

aria-server 的 AI 对话核心链路基于 LangChain4j AiServices + Reactor Flux，以 SSE（Server-Sent Events）方式向前端推送 token 流和工具事件流。在实际使用中，Agent 一次完整的执行可能包含：

- 多轮 LLM 推理（streaming token 输出）
- 1～N 次工具调用（域 HTTP 工具、MCP 工具、内置工具）
- 外部状态变更（Redis 入队、域切换审计、MQ 发布）

当用户在执行过程中主动取消（点击"停止"）或网络中断时，**仅断开 SSE 连接无法回滚已发生的外部状态变更**，会导致：

| 场景 | 问题 |
|------|------|
| 工具调用执行到一半 | HTTP 工具已发出请求（如查询订单、扣费），无法撤回 |
| `transfer_to_agent` 已入队 | Redis 状态变为 WAITING，前端不知情，下次打开才能恢复 |
| `switch_domain` 已写 Redis | 域切换成功但审计日志未写，两步不一致 |
| 网络抖动前端重试 | 同一条消息触发两次 LLM 执行，两次工具调用 |
| enqueue MQ 发布失败 | Redis 有入队记录，DB 无持久化，Redis 重启后数据丢失 |

### 1.2 设计目标

本方案围绕以下四个层次进行改造，与业界"Agent 取消"最佳实践对齐：

1. **可取消点检查**：在任务执行的关键节点检查取消状态，用户点击取消后尽快停止后续操作
2. **工具调用幂等性**：防止重复触发工具调用造成副作用
3. **状态机补偿机制**：对已发生的外部状态变更提供补偿路径，保证最终一致
4. **用户反馈设计**：取消操作有明确的实时反馈，用户知道"哪些已完成、哪些已停止"

### 1.3 非目标

- 不引入分布式事务（Saga/TCC），保持轻量设计
- 不改造人工客服侧（坐席端 WS、CSAT、会话转交）的现有逻辑
- 不修改 MCP 工具的幂等性（由 MCP 服务端自行保证）
- Phase 1 不处理多实例部署下的取消信号广播（Phase 2 补充）

### 1.4 技术栈约束

| 组件 | 版本 / 实现 |
|------|------------|
| LangChain4j | 1.1.0（AiServices + langchain4j-reactor） |
| Reactor | Spring WebFlux（Flux / Sinks） |
| 缓存 | Redis（Spring Data Redis） |
| 消息队列 | RabbitMQ（Spring AMQP） |
| 持久化 | PostgreSQL + MyBatis-Plus |

## 2. 现状分析

### 2.1 执行链路架构

```
ChatController (POST /stream → SSE)
  └─ ChatAppService.stream()
       ├─ 已接入人工 → appendAndHint()
       ├─ 有 domainCode → streamDomain()
       │    ├─ DomainSessionAppService.resolveActiveDomain()   # Redis 读写（阻塞）
       │    ├─ IntentService.classify()                        # 意图分类（阻塞）
       │    └─ DomainAgentService.streamChat()                 # LLM loop
       │         ├─ assistant.chat() → tokenFlux               # LangChain4j Flux<String>
       │         └─ eventSink (Sinks.Many)                     # 工具事件推送
       │              └─ DomainToolProviderFactory.build()
       │                   ├─ MCP 工具（外部服务）
       │                   ├─ 域 HTTP 工具 (HttpToolRunner.block())
       │                   └─ 内置工具 (switch_domain / transfer_to_agent)
       └─ 无 domainCode → FaqChatAppService.stream()
```

**关键特征：**
- `assistant.chat()` 返回 `Flux<String>`（langchain4j-reactor 模块），与 Reactor cancel 语义兼容
- `HttpToolRunner.execute()` 内部使用 `.block()`，运行在 `boundedElastic` 线程，**Reactor cancel 信号无法中断正在 block 的线程**
- `BuiltinTools.transferToAgent()` 和 `switchDomain()` 在工具执行线程内同步写 Redis / MQ

### 2.2 四层现状对照

#### 第一层：可取消点检查

| 检查项 | 现状 |
|--------|------|
| 用户主动取消端点 | ❌ 不存在 `POST /stream/cancel`，前端只能断开 SSE |
| SSE 断开后 LLM 调用是否终止 | ⚠️ Reactor cancel 向上传播，理论上可关闭 HTTP，但无保证 |
| 工具链中间取消检查点 | ❌ 无，多工具链中用户取消后第 2、3 个工具依然执行 |
| `switch_domain` / `transfer_to_agent` 前置检查 | ❌ 无 |

#### 第二层：工具调用幂等性

| 检查项 | 现状 |
|--------|------|
| 会话接入 CAS 原子操作 | ✅ `compareAndSetStatus()` 防并发抢接 |
| MQ 重投幂等（消息持久化） | ✅ `DuplicateKeyException` 静默跳过 |
| `transfer_to_agent` 重复调用 | ✅ 捕获 `SessionEnqueueException` 幂等兜底 |
| Chat 请求本身幂等键 | ❌ 无 `requestId`，网络重试触发两次 LLM 执行 |
| LLM turn 内重复工具调用 | ❌ 无去重，LLM 幻觉出重复调用时两次都会执行 |

#### 第三层：状态机补偿机制

| 检查项 | 现状 |
|--------|------|
| `SessionStatus` 状态机合法性校验 | ✅ `transitionTo()` 枚举，非法转换 throw |
| DB 事务回滚 | ✅ `@Transactional(rollbackFor = Exception.class)` |
| `saveDomainSwitch()` 两步非原子 | ⚠️ 已知风险，代码注释明确标注，无补偿 |
| `enqueue()` MQ 失败 | ❌ `publishSafely()` 吞掉异常，Redis 有数据 DB 无记录 |
| 工具失败后状态回退 | ⚠️ 返回 error string 给 LLM，无状态回退 |

#### 第四层：用户反馈设计

| 检查项 | 现状 |
|--------|------|
| `tool_call` / `tool_done` 事件（含耗时、错误） | ✅ `DomainToolProviderFactory` 完整包装 |
| `transfer` / `domain_switch` / `error` 事件 | ✅ `ChatEvent.EventType` 词汇完整 |
| `GET /chat/state` 状态恢复接口 | ✅ 前端 onMounted 兜底检测 |
| 取消操作反馈事件 `CANCELLED` | ❌ 不存在 |
| 多工具链步骤进度 | ❌ 无 "第 N/M 步" 进度事件 |
| LLM 推理中心跳（工具调用之间） | ❌ 无，前端看起来像"卡住" |

### 2.3 最高风险场景

```
用户点击"停止"时序分析（当前行为）：

T=0    用户发送消息
T=1    transfer_to_agent 执行：Redis WAITING 写入成功
T=2    MQ SESSION_START 发布成功（DB 持久化触发）
T=2.5  用户点击"停止" / 网络中断
T=2.5  SSE 断开，前端认为"没有发生转接"
T=3    前端 UI 停留在 AI 对话状态
       → 用户实际已在 WAITING 队列，但前端不知
       → 下次刷新才能通过 GET /chat/state 恢复
       → 中间窗口期：访客等待，无坐席感知
```

## 3. 第一层：可取消点设计

### 3.1 设计思路

取消机制需要同时处理两种执行上下文：

| 上下文 | 取消方式 |
|--------|---------|
| Reactor Flux 链（token 流） | `takeUntilOther(cancelTrigger.asMono())` — 依赖 Reactor cancel 信号传播 |
| `boundedElastic` blocking 线程（工具执行） | Redis 标志位轮询 — `isCancelled(sessionId)` 前置检查 |

两者需要配合：当用户触发取消时，同时 ① 发射 Reactor cancel 信号关闭 LLM HTTP 连接，② 在 Redis 设置标志位阻止后续工具执行。

### 3.2 新增组件：`CancellationRegistry`

```java
// infrastructure/cancellation/CancellationRegistry.java
@Component
@RequiredArgsConstructor
public class CancellationRegistry {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "chat:cancel:";
    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 内存注册表：sessionId → 取消触发器 Sink。
     * 仅对当前实例有效；多实例部署场景见 §3.6（Phase 2 扩展点）。
     */
    private final ConcurrentHashMap<String, Sinks.One<Void>> activeSinks =
            new ConcurrentHashMap<>();

    /**
     * Agent 流启动时注册，返回取消触发器。
     * 调用方将此 Sink 传入 takeUntilOther() 以实现 Reactor 层面的取消。
     */
    public Sinks.One<Void> register(String sessionId) {
        Sinks.One<Void> sink = Sinks.one();
        activeSinks.put(sessionId, sink);
        return sink;
    }

    /**
     * 触发取消：内存 Sink 发射完成信号 + Redis 写入标志位。
     * Redis 标志供 blocking 工具线程轮询；Sink 供 Flux 链响应。
     */
    public void cancel(String sessionId) {
        // 1. 触发 Reactor cancel 信号
        Sinks.One<Void> sink = activeSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitEmpty();
        }
        // 2. Redis 标志：工具 blocking 线程无法感知 Reactor cancel，靠此轮询
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, "1", TTL);
    }

    /** 流自然结束时清理注册，避免内存泄漏 */
    public void unregister(String sessionId) {
        activeSinks.remove(sessionId);
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    /** 工具执行前检查（blocking 线程调用） */
    public boolean isCancelled(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }
}
```

### 3.3 `DomainAgentService` 改造

在 `tokenFlux` 上挂 `takeUntilOther`，并在 `doFinally` 中区分正常结束与取消：

```java
// DomainAgentService.java — streamChat() 关键改动（省略未变动部分）

@RequiredArgsConstructor
public class DomainAgentService {

    private final CancellationRegistry cancellationRegistry; // 新增注入

    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        // ... systemPrompt、tools 组装与现有逻辑完全一致 ...

        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        // ① 注册取消触发器
        Sinks.One<Void> cancelTrigger = cancellationRegistry.register(sessionId);

        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                // ... 与现有一致 ...
                .toolProvider(toolProviderFactory.build(domainTools, eventSink, builtinTools, sessionId))
                .build();

        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(content -> ChatEvent.token(content, objectMapper))
                // ② takeUntilOther：cancelTrigger 发射时截断 Flux
                //    Reactor 向上传播 cancel → LangChain4j reactor 模块关闭 LLM HTTP 连接
                .takeUntilOther(cancelTrigger.asMono())
                .doFinally(signal -> {
                    log.info("[DomainAgent] done sessionId={} signal={}", sessionId, signal);
                    // ③ 取消检查必须在 unregister() 之前执行，否则 Redis key 已被删除无法判断
                    //    takeUntilOther 触发时 doFinally 收到 ON_COMPLETE（不是 CANCEL），
                    //    因此必须通过 isCancelled() 检测用户 API 取消；
                    //    CANCEL signal 仅在 SSE 客户端断开时由 Reactor 反压传播产生。
                    boolean wasCancelled = cancellationRegistry.isCancelled(sessionId);
                    // ④ 清理注册（自然完成 / 取消 / 错误 均需清理）
                    cancellationRegistry.unregister(sessionId);
                    if (wasCancelled || signal == SignalType.CANCEL) {
                        // ⑤ 取消时发送语义明确的 cancelled 事件，让前端停止 loading
                        eventSink.tryEmitNext(ChatEvent.cancelled(objectMapper));
                    }
                    eventSink.tryEmitComplete();
                });

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)));
    }
}
```

> **注意**：`takeUntilOther` 的语义是"在 other 发射第一个信号时完成（complete）下游"。`doFinally` 收到的是 `SignalType.ON_COMPLETE`（不是 `CANCEL`），因此不能用 signal 类型判断用户取消——必须通过 `cancellationRegistry.isCancelled()` 来区分用户 API 取消和自然完成（详见 §7.5.1）。`SignalType.CANCEL` 仅在 SSE 客户端断开连接时产生。

### 3.4 `DomainToolProviderFactory` 工具前置检查

工具执行是 blocking 调用，无法被 Reactor cancel 中断。在每次工具执行前主动检查取消标志：

```java
// DomainToolProviderFactory.java

// build() 方法新增 sessionId 参数
public ToolProvider build(List<ToolConfig> domainTools,
                           Sinks.Many<ChatEvent> eventSink,
                           BuiltinTools builtinTools,
                           String sessionId) {          // ← 新增
    return request -> {
        Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
        loadMcpTools(toolMap, eventSink, request, sessionId);
        loadDomainTools(toolMap, domainTools, eventSink, sessionId);
        toolMap.putAll(builtinTools.buildToolSpecs());
        return new ToolProviderResult(toolMap);
    };
}

// buildHttpExecutor() 新增取消检查
private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink,
                                        String sessionId) {
    return (ToolExecutionRequest req, Object memId) -> {
        // ⑤ 工具执行前：如果已取消则跳过，返回 [CANCELLED] 信号给 LLM
        if (cancellationRegistry.isCancelled(sessionId)) {
            log.info("[ToolFactory] HTTP 工具跳过（已取消）tool={} sessionId={}", tc.code(), sessionId);
            emitToolDone(tc.code(), false, "已取消", 0L, eventSink);
            return "[CANCELLED] 操作已取消，请告知用户操作已停止。";
        }
        long start = System.currentTimeMillis();
        emitToolCall(tc.code(), eventSink);
        // ... 其余执行逻辑与现有完全一致 ...
    };
}

// wrapWithSseEvents() 同样需要取消检查（MCP 工具同为外部调用）
// I-1 修复：原版本仅对 HTTP 工具做了取消检查，MCP 工具遗漏，补充如下：
private ToolExecutor wrapWithSseEvents(String name, ToolExecutor delegate,
                                        Sinks.Many<ChatEvent> eventSink, String sessionId) {
    return (req, memId) -> {
        // ⑥ MCP 工具执行前检查取消标志（与 HTTP 工具保持一致）
        if (cancellationRegistry.isCancelled(sessionId)) {
            log.info("[ToolFactory] MCP 工具跳过（已取消）tool={} sessionId={}", name, sessionId);
            emitToolDone(name, false, "已取消", 0L, eventSink);
            return "[CANCELLED] 操作已取消，请告知用户操作已停止。";
        }
        long start = System.currentTimeMillis();
        emitToolCall(name, eventSink);
        var span = tracer.nextSpan().name("tool." + name).tag("tool.type", "mcp").start();
        try (var ignored = tracer.withSpan(span)) {
            String result = delegate.execute(req, memId);
            long durationMs = System.currentTimeMillis() - start;
            span.tag("tool.success", "true");
            emitToolDone(name, true, null, durationMs, eventSink);
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            span.tag("tool.success", "false").error(e);
            emitToolDone(name, false, e.getMessage(), durationMs, eventSink);
            throw e;
        } finally {
            span.end();
        }
    };
}
```

### 3.5 新增取消端点

```java
// ChatController.java — 新增接口

/**
 * 取消正在进行的 Agent 生成。
 *
 * <p>前端在用户点击"停止"按钮时调用此接口。服务端触发：
 * <ol>
 *   <li>内存 Sink 发射完成信号 → Reactor Flux 截断 → LLM HTTP 连接关闭</li>
 *   <li>Redis 写入取消标志 → 后续工具执行前置检查跳过</li>
 * </ol>
 *
 * <p>接口幂等：若 sessionId 无活跃生成流，静默成功，不返回错误。
 */
@CrossOrigin(origins = "*")
@PostMapping("/stream/cancel")
public R<Void> cancelStream(@RequestParam String sessionId) {
    if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
        return R.fail(400, "非法的 sessionId 格式");
    }
    cancellationRegistry.cancel(sessionId);
    log.info("[Chat] 用户取消 Agent 生成 sessionId={}", sessionId);
    return R.ok(null);
}
```

### 3.6 多实例部署扩展点（Phase 2）

当前 `activeSinks` 存储在单个 JVM 内存中。多实例部署时，`POST /stream/cancel` 请求可能路由到**没有活跃流的节点**，导致内存 Sink 无法触发，只有 Redis 标志位生效（工具层取消有效，LLM 层取消无效）。

Phase 2 扩展方案：将 Redis Keyspace Notifications 替代内存 Sink，监听 `chat:cancel:*` key 的 SET 事件，所有节点均可响应：

```java
// Phase 2 扩展伪代码
@EventListener
public void onCancelKeySet(RedisKeyspaceEvent event) {
    String sessionId = extractSessionId(event.getKey());
    Sinks.One<Void> sink = activeSinks.get(sessionId);
    if (sink != null) sink.tryEmitEmpty();
}
```

## 4. 第二层：幂等性设计

### 4.1 现有幂等覆盖范围

以下场景已有完整的幂等保障，**本次改造不涉及**：

| 场景 | 实现 |
|------|------|
| 座席并发抢接同一会话 | `SessionQueueRepository.compareAndSetStatus()` Redis CAS |
| MQ 重投导致消息重复持久化 | `DuplicateKeyException` 静默跳过（`(session_id, seq)` 唯一索引） |
| `transfer_to_agent` 重复调用 | 捕获 `SessionEnqueueException`，继续发 SSE |
| 座席并发转交同一会话 | `compareAndSetAgentId()` Redis CAS |

### 4.2 缺口一：Chat 请求缺少幂等键

**问题**：前端发送消息后遇到网络超时，重试同一条消息，服务端会执行两次完整的 LLM 调用和工具调用链，触发两次外部副作用（如两次查询、两次扣费）。

**方案**：前端为每次消息生成 `requestId`（UUID），服务端用 Redis `setIfAbsent` 在短时窗内去重。

#### 4.2.1 前端约定

前端每次 `POST /stream` 时携带 `requestId`：

```json
{
  "sessionId": "sess_abc123",
  "message": "查询我的订单状态",
  "domainCode": "ecommerce",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

`requestId` 由前端生成（`crypto.randomUUID()`），**同一条消息的重试必须使用相同的 `requestId`**，新消息生成新的 `requestId`。

#### 4.2.2 服务端实现

```java
// ChatController.ChatRequest — 新增字段
@Data
public static class ChatRequest {
    private String sessionId;
    private String message;
    private String domainCode;
    /**
     * 请求幂等键（UUID），前端每次消息生成，同一消息重试使用相同值。
     * 可选：为 null 时不做幂等检查（向后兼容）。
     */
    private String requestId;
}
```

```java
// ChatAppService.java — stream() 前置幂等检查
@Service
@RequiredArgsConstructor
public class ChatAppService {

    private final StringRedisTemplate redisTemplate;
    /**
     * requestId 幂等窗口。
     * 设为 2 分钟而非 30 秒的原因：单次 Agent turn 含 3-5 个工具调用时，
     * 总耗时可能超过 30 秒；TTL 期间若前端重试，两次 LLM 执行并发运行，
     * 与幂等目标相悖。2 分钟覆盖合理的最坏情况执行时长。
     */
    private static final Duration REQUEST_IDEMPOTENCY_TTL = Duration.ofMinutes(2);
    private static final String REQ_KEY_PREFIX = "chat:req:";

    public Flux<ChatEvent> stream(String sessionId, String message,
                                   String domainCode, String requestId) {
        // ① 幂等检查：requestId 不为空时才校验
        if (requestId != null && !requestId.isBlank()) {
            String key = REQ_KEY_PREFIX + sessionId + ":" + requestId;
            // 使用 "processing" 值而非 "1"，区分"处理中"和 key 不存在两种状态。
            // setIfAbsent 返回 true 说明首次写入，可以继续处理；
            // 返回 false 说明该 requestId 已在处理中或已完成，拒绝重复执行。
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(key, "processing", REQUEST_IDEMPOTENCY_TTL);
            if (!Boolean.TRUE.equals(isNew)) {
                log.warn("[Chat] 重复请求被拦截 sessionId={} requestId={}", sessionId, requestId);
                return Flux.just(ChatEvent.error("请勿重复提交，请等待上一条消息处理完成", objectMapper));
            }
            // 请求完成后将状态更新为 "done"（保持 key 存活直到 TTL，
            // 防止 TTL 内再次提交同 requestId 被误判为新请求）。
            // 具体实现：在 tokenFlux.doFinally() 中调用 redisTemplate.opsForValue().set(key, "done", 剩余TTL)
        }

        // ② 现有路由逻辑不变
        if (sessionQueueService.isActive(sessionId)) {
            return faqChatService.appendAndHint(sessionId, message);
        }
        if (StringUtils.isNotBlank(domainCode)) {
            return streamDomain(sessionId, message, domainCode);
        }
        return faqChatService.stream(sessionId, message);
    }
}
```

```java
// ChatController.java — streamChat() 传递 requestId
public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest req) {
    // ... 现有校验逻辑不变 ...
    return chatService.stream(sessionId, req.getMessage(),
                               req.getDomainCode(), req.getRequestId())  // ← 新增 requestId
            .map(this::toSse)
            .concatWith(doneStream());
}
```

> **TTL 设计**：TTL 设为 **2 分钟**（而非 30 秒）。单次 Agent turn 含 3-5 个工具调用时总耗时可能超过 30 秒；若 TTL 在请求仍处理中时到期，前端用相同 `requestId` 重试会穿透，触发两次 LLM 执行。2 分钟覆盖合理的最坏情况执行时长。Redis key 的值采用 `"processing"` 写入，请求完成后更新为 `"done"`，防止 TTL 内的重复提交被误判为首次请求。

### 4.3 缺口二：LLM turn 内重复工具调用

**问题**：LLM 在同一 turn 内可能因幻觉或 prompt 设计问题重复调用同一工具（如连续两次调用 `query_order`）。当前 `HttpToolRunner` 无感知地执行两次，外部系统收到两次请求。

**方案**：在 `DomainToolProviderFactory` 的工具执行器中维护 per-turn 调用记录，对重复调用返回缓存结果。

```java
// DomainToolProviderFactory.build() 中新增 per-turn 调用缓存

public ToolProvider build(List<ToolConfig> domainTools,
                           Sinks.Many<ChatEvent> eventSink,
                           BuiltinTools builtinTools,
                           String sessionId) {
    // build() 的 ToolProvider lambda 由 LangChain4j 在每次 LLM turn 时调用（一次请求可多 turn）。
    // turnCallCache 必须在 lambda 内部创建，保证生命周期 = 单次 LLM turn，不跨 turn 污染。
    // LangChain4j 在同一 turn 内串行调用工具，无并发写，使用普通 HashMap 即可。
    return request -> {
        Map<String, String> turnCallCache = new HashMap<>(); // ← 每 turn 新建，不跨 turn 复用

        Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
        loadMcpTools(toolMap, eventSink, request, sessionId);
        loadDomainTools(toolMap, domainTools, eventSink, sessionId, turnCallCache); // ← 传入缓存
        toolMap.putAll(builtinTools.buildToolSpecs());
        return new ToolProviderResult(toolMap);
    };
}

private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink,
                                        String sessionId,
                                        Map<String, String> turnCallCache) {
    return (ToolExecutionRequest req, Object memId) -> {
        // 取消检查
        if (cancellationRegistry.isCancelled(sessionId)) {
            emitToolDone(tc.code(), false, "已取消", 0L, eventSink);
            return "[CANCELLED] 操作已取消。";
        }

        // 幂等去重：同 turn 内相同工具+参数 → 返回缓存结果
        String cacheKey = tc.code() + ":" + (req.arguments() != null ? req.arguments() : "");
        String cached = turnCallCache.get(cacheKey);
        if (cached != null) {
            log.warn("[ToolFactory] 同 turn 重复工具调用，返回缓存 tool={} sessionId={}",
                    tc.code(), sessionId);
            emitToolDone(tc.code(), true, null, 0L, eventSink);
            return cached;
        }

        // 正常执行
        long start = System.currentTimeMillis();
        emitToolCall(tc.code(), eventSink);
        var span = tracer.nextSpan().name("tool." + tc.code()).tag("tool.type", "http").start();
        try (var ignored = tracer.withSpan(span)) {
            Map<String, Object> args = parseArgs(req.arguments());
            ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
            long durationMs = System.currentTimeMillis() - start;
            if (result.isSuccess()) {
                span.tag("tool.success", "true");
                emitToolDone(tc.code(), true, null, durationMs, eventSink);
                turnCallCache.put(cacheKey, result.getResponse()); // ← 缓存结果
                return result.getResponse();
            } else {
                span.tag("tool.success", "false").error(new RuntimeException(result.getErrorMsg()));
                emitToolDone(tc.code(), false, result.getErrorMsg(), durationMs, eventSink);
                return "工具执行失败: " + result.getErrorMsg();
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            span.tag("tool.success", "false").error(e);
            emitToolDone(tc.code(), false, e.getMessage(), durationMs, eventSink);
            return "工具执行失败: " + e.getMessage();
        } finally {
            span.end();
        }
    };
}
```

> **设计权衡**：`turnCallCache` 按 `toolCode + arguments` 作为 key，参数相同才命中缓存。参数不同的重复调用（如查询不同订单 ID）视为合法，不命中缓存。此 Map 不跨 turn 复用（每次 `build()` 新建），不影响多轮对话。

## 5. 第三层：状态机补偿设计

### 5.1 现有状态机强制约束（保留）

`SessionStatus.transitionTo()` 的状态机枚举校验是已有的防护层，**本次不修改**：

```
AI_CHAT → WAITING → ACTIVE → CLOSED
AI_CHAT → CLOSED
WAITING → CLOSED
```

非法转换直接抛出 `IllegalStateException`，Redis CAS 操作确保并发安全。这套机制是补偿设计的基础前提。

### 5.2 问题一：`enqueue()` 的 Redis-MQ 非原子性

#### 当前问题

```java
// SessionQueueService.enqueue() — 当前实现
queueRepository.save(item);            // ← 第一步：Redis 写入
publishEvent(ENQUEUE);                 // ← Fanout，失败只 warn（publishSafely）
publishSessionStart(...);             // ← Direct → DB 持久化，失败只 warn（publishSafely）
```

**风险场景**：Redis 写入成功，但 `publishSessionStart`（触发 DB 持久化的 MQ 消息）发布失败 → Redis 有记录，DB 无持久化 → Redis TTL 到期或 Redis 重启后，会话彻底丢失，既无法在坐席侧看到，也无法通过 DB 查询恢复。

#### 补偿方案：MQ 失败时回滚 Redis

将 `publishSessionStart` 从 `publishSafely`（吞异常）升级为**带补偿的有序操作**：SESSION_START（持久化关键路径）失败时主动回滚 Redis 入队，并向调用方抛出业务异常：

```java
// SessionQueueService.enqueue() — 改造后
public SessionQueueItem enqueue(String sessionId, String userName,
                                 String transferReason, String tag) {
    // 业务时间检查（不变）
    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
    if (!businessHoursService.isOpen(now)) {
        String nextOpen = businessHoursService.nextOpenTime(now);
        throw new ServiceOfflineException("当前不在服务时间...", nextOpen);
    }

    SessionQueueItem item = new SessionQueueItem(
            sessionId, userName, transferReason, tag,
            Instant.now().getEpochSecond(), SessionStatus.WAITING, null);

    // 第一步：Redis 入队
    try {
        queueRepository.save(item);
    } catch (IllegalStateException e) {
        log.error("[SessionQueue] enqueue 失败 sessionId={}", sessionId, e);
        throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
    }

    // 第二步：Fanout 广播（非关键路径，失败可容忍）
    try {
        publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
    } catch (org.springframework.amqp.AmqpException e) {
        log.warn("[SessionQueue] ENQUEUE Fanout 发布失败（非关键），继续执行 sessionId={}", sessionId, e);
    }

    // 第三步：SESSION_START（关键路径：触发 DB 持久化）
    // 失败时补偿回滚 Redis，并向调用方抛出专属异常 SessionEnqueueMqFailedException，
    // 与"已入队（幂等）"场景的 SessionEnqueueException 明确区分，
    // 避免 BuiltinTools.transferToAgent() 误判为幂等而继续发 SSE transfer 事件。
    try {
        publisher.publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
    } catch (org.springframework.amqp.AmqpException e) {
        log.error("[SessionQueue] SESSION_START MQ 发布失败，补偿回滚 Redis 入队 sessionId={}", sessionId, e);
        queueRepository.delete(sessionId);  // 补偿：Redis 入队回滚
        throw new SessionEnqueueMqFailedException("服务暂时不可用，请稍后重试", sessionId, e);
    }

    log.info("[SessionQueue] enqueue 成功 sessionId={} userName={}", sessionId, userName);
    return item;
}
```

> **设计说明**：Fanout 事件（广播给坐席侧 SSE）失败可容忍——坐席端会在下次刷新时重新加载队列；但 SESSION_START（触发 DB 持久化）失败不可容忍，必须保证 Redis 和 DB 的一致性，否则 Redis TTL 到期后数据永久丢失。

### 5.3 问题二：`saveDomainSwitch()` 两步非原子性

#### 当前问题

`DomainSessionAppService.saveDomainSwitch()` 的已知注释：

> "先更新 Redis 激活域绑定，再写入审计日志。两步操作相互独立，若第二步失败，Redis 已更新但审计记录缺失（最终一致，非原子）。业务可接受此风险；如需强一致，需引入补偿机制。"

#### 补偿方案：审计失败不回滚 Redis，但记录补偿日志

域切换的 Redis 更新（业务核心）比审计日志（辅助记录）优先级更高。补偿策略：审计失败时**不回滚 Redis**（回滚会破坏用户当前的域绑定），而是写入补偿日志，供后续异步补偿或人工对账：

```java
// DomainSessionAppService.saveDomainSwitch() — 改造后

public void saveDomainSwitch(String sessionId, String fromDomain,
                              String toDomain, String userMessage, String reason) {
    // 第一步：Redis 更新（核心业务）
    sessionDomainRepo.save(sessionId, toDomain);
    log.info("[DomainSwitch] Redis 更新完成 sessionId={} {} → {}", sessionId, fromDomain, toDomain);

    // 第二步：审计日志（辅助记录）
    try {
        domainSwitchRepo.record(new DomainSwitchRecord(
                sessionId, fromDomain, toDomain,
                SwitchType.LLM_TOOL, userMessage, reason, null));
    } catch (Exception e) {
        // 审计失败：不回滚 Redis（回滚会破坏用户当前域绑定）
        // 写入补偿日志，供后续对账
        log.error("[DomainSwitch] 审计日志写入失败，已写补偿日志 sessionId={} {} → {}",
                sessionId, fromDomain, toDomain, e);
        writeDomainSwitchCompensationLog(sessionId, fromDomain, toDomain, userMessage, reason);
    }
}

/**
 * 写入域切换补偿日志（Redis List，TTL 7 天）。
 * 后续可由定时任务读取并重试写入审计表。
 */
private void writeDomainSwitchCompensationLog(String sessionId, String fromDomain,
                                               String toDomain, String userMessage, String reason) {
    try {
        String log = String.format("{\"sessionId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"," +
                "\"msg\":\"%s\",\"reason\":\"%s\",\"ts\":%d}",
                sessionId, fromDomain, toDomain, userMessage, reason, Instant.now().getEpochSecond());
        redisTemplate.opsForList().rightPush("compensation:domain_switch", log);
        redisTemplate.expire("compensation:domain_switch", Duration.ofDays(7));
    } catch (Exception ex) {
        // 补偿日志本身也失败：只能记录 ERROR 日志，供人工排查
        log.error("[DomainSwitch] 补偿日志写入也失败 sessionId={}", sessionId, ex);
    }
}
```

### 5.4 问题三：工具失败后的状态一致性

#### 场景分析

`transfer_to_agent` 工具执行分两步（顺序不可颠倒）：

```
步骤一：sessionQueueService.enqueue()   → Redis 状态 WAITING
步骤二：eventSink.tryEmitNext(transfer)  → 前端 SSE 通知
```

若步骤一成功、步骤二（SSE 发射）失败，前端不知道转接已发生。

**当前已有的处理**：`BuiltinTools.transferToAgent()` 中步骤二失败只记录 error log，不影响 Redis 状态。前端通过 `GET /chat/state` onMounted 检查可恢复，这个兜底已经足够。

**额外补充**：在 `canceled` 场景下，若 `transfer_to_agent` 已经执行完毕（Redis WAITING），用户随后取消了 Agent 生成，Redis 中的 WAITING 状态**不应该**被取消操作回滚——用户已经在队列里了，应保留并通知用户：

```java
// CancellationRegistry.cancel() 改造：取消时检查 session 是否已在队列中

public void cancel(String sessionId) {
    Sinks.One<Void> sink = activeSinks.remove(sessionId);
    if (sink != null) {
        sink.tryEmitEmpty();
    }
    redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, "1", TTL);
    // 注：不在此处回滚 transfer_to_agent 的 WAITING 状态
    // 若用户已入队，cancelled 事件由 ChatEvent.cancelledWithTransfer() 通知前端
    // 前端收到 cancelled 后应同时检查 transferred 标志
}
```

前端接收到 `cancelled` 事件后，应立即调用 `GET /chat/state` 确认最终状态，如果是 WAITING 则按转接流程处理。

### 5.5 补偿机制总览

| 场景 | 补偿策略 | 回滚目标 |
|------|---------|---------|
| enqueue SESSION_START MQ 失败 | 主动删除 Redis 入队记录，抛异常 | Redis → 一致 |
| saveDomainSwitch 审计失败 | 写补偿日志（Redis List），不回滚 Redis | 审计 → 最终一致 |
| transfer_to_agent 后用户取消 | 保留 WAITING 状态，前端通过 `/chat/state` 恢复 | 不回滚 |
| DB 事务失败 | `@Transactional` 自动回滚（已覆盖） | DB → 一致 |

## 6. 第四层：用户反馈设计

### 6.1 现有事件词汇（保留）

`ChatEvent.EventType` 已有的事件类型设计良好，**本次全部保留**：

| 事件类型 | 触发场景 | 数据格式 |
|---------|---------|---------|
| `tool_call` | 工具开始执行 | `{"toolCode":"...","status":"running"}` |
| `tool_done` | 工具执行完成 | `{"toolCode":"...","success":true,"durationMs":120}` |
| `transfer` | AI 工具触发转接人工 | `{"intentCode":"agent_transfer","message":"..."}` |
| `domain_switch` | 域切换完成 | `{"targetDomain":"..."}` |
| `error` | 业务错误 | `{"message":"..."}` |
| `offline` | 非服务时间拦截 | `{"message":"...","nextOpenTime":"09:00"}` |
| `auth_required` | 需要短信验证 | `{"reason":"..."}` |
| `csat_request` | 评价邀请 | `{"csatId":...,"message":"..."}` |
| `done` | SSE 流结束 | `[DONE]` |
| `sources` | 知识库溯源 | `[{"docId":"...","label":"..."}]` |

### 6.2 新增：`cancelled` 事件

取消操作需要语义明确的事件通知前端停止 loading 状态。

#### 6.2.1 `ChatEvent` 新增常量和工厂方法

```java
// ChatEvent.java — 新增内容

public static final class EventType {
    // ... 现有常量不变 ...

    /**
     * Agent 生成被用户主动取消，data 为 JSON：{"message":"已取消","completedSteps":N}。
     *
     * <p>前端收到后须：
     * <ol>
     *   <li>停止 loading 状态，显示已输出的部分内容</li>
     *   <li>调用 {@code GET /chat/state} 确认 session 最终状态
     *       （取消前可能已发生 transfer_to_agent，需按转接流程处理）</li>
     *   <li>启用发送按钮，允许用户继续对话</li>
     * </ol>
     */
    public static final String CANCELLED = "cancelled";
}

/**
 * Agent 生成被取消。data 为 JSON：{"message":"已取消"}。
 */
public static ChatEvent cancelled(ObjectMapper mapper) {
    return new ChatEvent(EventType.CANCELLED,
            SseJson.encode(mapper, Map.of("message", "已取消")));
}
```

#### 6.2.2 前端处理约定

前端 SSE 解析器在收到 `cancelled` 事件后的行为：

```typescript
// 前端 SSE 处理伪代码（约定，非服务端代码）
eventSource.addEventListener('cancelled', (e) => {
  const { message } = JSON.parse(e.data);
  
  // 1. 停止 loading，显示已生成内容
  stopLoading();
  showPartialContent();
  
  // 2. 检查是否发生了 transfer（取消前可能已入队）
  fetch(`/api/v1/chat/state?sessionId=${sessionId}`)
    .then(res => res.json())
    .then(({ status }) => {
      if (status === 'WAITING' || status === 'ACTIVE') {
        // 转接已发生，切换到等待人工界面
        switchToHumanAgentMode(status);
      }
    });
  
  // 3. 允许用户继续发消息
  enableMessageInput();
});
```

### 6.3 补充：`tool_call` / `tool_done` 的跳过场景

取消后工具被跳过时，`tool_done` 仍会发射（`success: false, error: "已取消"`），前端无需特殊处理，现有的 `tool_done` 渲染逻辑天然兼容。

```json
// 工具被取消跳过时的 tool_done 事件
{
  "toolCode": "query_order",
  "success": false,
  "durationMs": 0,
  "error": "已取消"
}
```

### 6.4 补充：LLM 推理中的心跳（可选，Phase 2）

当前 LLM 推理期间（工具调用之间）没有任何事件，前端看起来像"卡住了"。可在 Phase 2 引入 `thinking` 心跳事件：

```java
// Phase 2 可选扩展：thinking 心跳事件
public static final String THINKING = "thinking";  // data: {"status":"thinking"}
```

实现思路：在 `DomainAgentService` 中，工具 `tool_done` 事件触发后到下一个 `tool_call` 触发前，如果超过 2 秒没有 token 输出，向 `eventSink` 发射一次 `thinking` 心跳。此功能不影响核心逻辑，在 Phase 2 单独评估。

### 6.5 事件时序示例

#### 正常完成

```
→ tool_call {"toolCode":"query_order","status":"running"}
→ tool_done  {"toolCode":"query_order","success":true,"durationMs":230}
→ (token stream) "您的订单..."
→ done
```

#### 用户取消（工具执行中）

```
→ tool_call  {"toolCode":"query_order","status":"running"}
  [用户点击停止 → POST /stream/cancel]
→ tool_done  {"toolCode":"query_order","success":false,"durationMs":0,"error":"已取消"}
→ cancelled  {"message":"已取消"}
→ done
```

#### 用户取消（transfer_to_agent 已完成）

```
→ transfer   {"intentCode":"agent_transfer","message":"已为您转接..."}
  [用户点击停止 → POST /stream/cancel]
→ cancelled  {"message":"已取消"}
→ done
  [前端收到 cancelled，调用 GET /chat/state → WAITING]
  [前端切换到等待人工界面]
```

#### 重复请求被拦截

```
[第二次 POST /stream 携带相同 requestId]
→ error {"message":"请勿重复提交，请等待上一条消息处理完成"}
→ done
```

## 7. 改造清单与注意事项

### 7.1 文件改动汇总

| 文件路径 | 改动类型 | 改动要点 |
|---------|---------|---------|
| `infrastructure/cancellation/CancellationRegistry.java` | **新增** | Redis 标志 + 内存 Sink 双轨取消机制 |
| `application/service/DomainAgentService.java` | 注入 + 改动 | 注入 `CancellationRegistry`；`register()` + `takeUntilOther()` + `doFinally` 区分 CANCEL signal；`build()` 新增 `sessionId` 参数 |
| `application/service/tool/DomainToolProviderFactory.java` | 改动 | `build()` 新增 `sessionId` 参数；每个工具执行前调用 `isCancelled()`；新增 per-turn `turnCallCache` |
| `interfaces/rest/ChatController.java` | 新增端点 + 字段 | `POST /stream/cancel` 端点；`ChatRequest` 新增 `requestId` 字段 |
| `application/service/ChatAppService.java` | 改动 | `stream()` 新增 `requestId` 参数；Redis `setIfAbsent` 幂等检查 |
| `application/service/ChatEvent.java` | 新增 | `CANCELLED` 常量 + `cancelled()` 工厂方法 |
| `application/service/SessionQueueService.java` | 改动 | `enqueue()` SESSION_START 失败时补偿回滚 Redis |
| `application/service/DomainSessionAppService.java` | 改动 | `saveDomainSwitch()` 审计失败时写补偿日志 |

### 7.2 新增依赖

本次改造**不引入新的外部依赖**，复用现有组件：

- `StringRedisTemplate`（已存在）— 取消标志位 + 幂等 key + 补偿日志
- `Sinks.One<Void>`（Reactor Core，已引入）— 取消触发器
- `ConcurrentHashMap`（JDK）— per-turn 工具调用缓存

### 7.3 配置项

建议在 `application.yml` 中外置以下参数（当前方案内联了默认值）：

```yaml
conversation:
  cancellation:
    ttl-minutes: 5          # 取消标志 Redis TTL（默认 5 分钟）
  idempotency:
    request-ttl-minutes: 2  # requestId 幂等窗口（默认 2 分钟，需覆盖最坏情况下多工具链执行耗时）
  compensation:
    domain-switch-log-ttl-days: 7  # 域切换补偿日志 TTL（默认 7 天）
```

### 7.4 分阶段交付计划

#### Phase 1（本次改造，P0~P1）

| 优先级 | 内容 | 预估工作量 |
|--------|------|----------|
| P0 | `CancellationRegistry` + `POST /stream/cancel` + `takeUntilOther` | 1 天 |
| P0 | 工具执行前 `isCancelled()` 检查 + `CANCELLED` 事件 | 0.5 天 |
| P1 | `requestId` 幂等键 + Chat 请求去重 | 0.5 天 |
| P1 | `enqueue()` SESSION_START 失败补偿回滚 | 0.5 天 |
| P2 | per-turn 工具调用缓存去重 | 0.5 天 |
| P2 | `saveDomainSwitch()` 补偿日志 | 0.5 天 |

#### Phase 2（多实例 + 增强，后续迭代）

| 内容 | 说明 |
|------|------|
| Redis Keyspace Notifications 广播取消信号 | 解决多实例下内存 Sink 无法跨节点触发的问题 |
| 补偿日志消费定时任务 | 定时读取 `compensation:domain_switch` 重试审计写入 |
| `thinking` 心跳事件 | LLM 推理期间防止前端"卡顿"感 |
| 多工具链进度事件 | 在 `tool_call` 中增加 `stepIndex/totalSteps` 字段 |

### 7.5 关键注意事项

#### 7.5.1 `takeUntilOther` 的信号语义（已修正）

**重要更正**：`takeUntilOther(cancelTrigger.asMono())` 在 `cancelTrigger` 发射后，
`FluxTakeUntilOther` 会调用下游的 `onComplete()`——因此 `doFinally` 收到的是
`SignalType.ON_COMPLETE`，**不是** `SignalType.CANCEL`。

`SignalType.CANCEL` 仅在 SSE 客户端主动断开连接时，由 Reactor 反压机制从下游向上传播产生。

正确做法：在 `doFinally` 中通过 `CancellationRegistry.isCancelled()` 判断用户 API 取消，
而非依赖 signal 类型。两种取消路径均需覆盖：

```java
.doFinally(signal -> {
    // ① 必须在 unregister() 之前检查，否则 Redis key 已被清除
    boolean wasCancelled = cancellationRegistry.isCancelled(sessionId);
    cancellationRegistry.unregister(sessionId);
    // wasCancelled → 用户调用 POST /stream/cancel（takeUntilOther 触发，signal = ON_COMPLETE）
    // CANCEL      → SSE 客户端断开连接（Reactor 反压传播，signal = CANCEL）
    if (wasCancelled || signal == SignalType.CANCEL) {
        eventSink.tryEmitNext(ChatEvent.cancelled(objectMapper));
    }
    eventSink.tryEmitComplete();
})
```

#### 7.5.2 `eventSink.tryEmitComplete()` 的幂等性

`doFinally` 中调用 `tryEmitComplete()` 是幂等的，多次调用不会报错（`FAIL_NON_SERIALIZED` 只在并发场景下发生，`doFinally` 是单线程触发）。可以安全调用。

#### 7.5.3 `isCancelled()` 的 Redis 网络开销

每次工具执行前都调用 `redisTemplate.hasKey()`，引入一次 Redis 网络往返。当工具链较长（5+ 个工具）时，这些检查会积累延迟。

**优化方案**：将 Redis 标志位缓存到 `CancellationRegistry` 的 `activeSinks` map 中——触发取消时同时设置一个内存标志，`isCancelled()` 优先检查内存标志，降级到 Redis：

```java
private final ConcurrentHashMap<String, Boolean> cancelledFlags = new ConcurrentHashMap<>();

public void cancel(String sessionId) {
    cancelledFlags.put(sessionId, Boolean.TRUE);  // 内存标志（当前实例有效）
    // ... Sink + Redis 逻辑不变 ...
}

public boolean isCancelled(String sessionId) {
    // 优先检查内存（无网络开销），兜底检查 Redis（多实例场景）
    return Boolean.TRUE.equals(cancelledFlags.get(sessionId))
        || Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
}

public void unregister(String sessionId) {
    activeSinks.remove(sessionId);
    cancelledFlags.remove(sessionId);
    redisTemplate.delete(KEY_PREFIX + sessionId);
}
```

#### 7.5.4 `enqueue()` 补偿方案的幂等性

`BuiltinTools.transferToAgent()` 中捕获 `SessionEnqueueException` 的逻辑（幂等兜底）仍然有效，但补偿方案在 SESSION_START MQ 失败时现在抛出专属子类 `SessionEnqueueMqFailedException`，需在 `transferToAgent()` 中明确区分：

| 异常类型 | 含义 | `transferToAgent()` 处理 |
|---------|------|------------------------|
| `SessionEnqueueMqFailedException` | MQ 失败，Redis 已回滚，会话**未入队** | 返回错误提示，**不发** SSE transfer |
| `SessionEnqueueException`（基类） | 会话已在队列（幂等兜底） | 继续发 SSE transfer（与现有逻辑一致） |

```java
// 新增专属异常类
public class SessionEnqueueMqFailedException extends SessionEnqueueException {
    public SessionEnqueueMqFailedException(String message, String sessionId, Throwable cause) {
        super(message, sessionId, cause);
    }
}
```

```java
// BuiltinTools.transferToAgent() 改造——catch 顺序：子类在前，父类在后
try {
    sessionQueueService.enqueue(...);
} catch (SessionEnqueueMqFailedException e) {
    // MQ 失败导致回滚：Redis 无记录，不应发 SSE transfer
    log.error("[BuiltinTool] 入队因 MQ 失败被回滚 sessionId={}", ctx.sessionId(), e);
    return "转接失败，服务暂时不可用，请稍后重试或点击「转人工」按钮。";
} catch (SessionEnqueueException e) {
    // 已入队（幂等兜底）：继续发 SSE，让前端同步 UI
    log.warn("[BuiltinTool] 会话已入队，继续发 SSE sessionId={}", ctx.sessionId(), e);
}
```

#### 7.5.5 `requestId` 的向后兼容

`requestId` 字段在 `ChatRequest` 中为可选（`private String requestId`，无 `@NotBlank`），为 null 时跳过幂等检查，老版本前端无需改动即可正常使用。

### 7.6 测试要点

| 测试场景 | 验证内容 |
|---------|---------|
| 正常取消（LLM 生成中） | 收到 `cancelled` 事件，后续不再有 token |
| 取消（工具执行中） | 工具被跳过，`tool_done` 含 `error: "已取消"` |
| 取消（transfer_to_agent 已完成） | Redis WAITING 状态保留；`GET /chat/state` 返回 WAITING |
| 重复 requestId 请求 | 第二次请求被拦截，返回 error 事件 |
| enqueue MQ 断开 | SESSION_START 失败 → Redis 回滚 → 调用方收到异常 |
| 并发取消（两个请求同时调用 cancel） | 幂等，两次调用均返回成功，Sink 只触发一次 |
| 多工具链取消（第 1 个工具完成后取消） | 第 2、3 个工具被跳过，各自发 `tool_done(cancelled)` |

### 7.7 与 LangChain4j 版本的兼容性

当前项目使用 LangChain4j **1.1.0**，`langchain4j-reactor` 模块的 `Flux<String>` 返回类型通过内部 `TokenStreamAdapter` 实现，下游 Flux 取消会通过 `ReactorAdapters` 传播到 `StreamingChatResponseHandler`，进而关闭 HTTP 连接。

如果升级到 1.1.0+ 版本，`StreamingHandle.cancel()` 是更直接的取消 API（在回调中调用），可在 Phase 2 评估是否切换到 `TokenStream` 模式以获得更确定性的取消保证。当前用 `takeUntilOther` 的方案在 1.1.0 上已验证可行。
