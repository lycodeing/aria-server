# SSE 魔法值消除 + transfer 语义事件改造清单

**涉及模块**: ai-conversation/conversation-service, ai-customerservice-frontend  
**改造类型**: 代码质量重构 + 功能补全  
**优先级**: P1（transfer 事件是功能缺口，前端无法自动切换人工模式）

---

## 背景 & 问题

### 问题 1 — ToolCallResult.status 是魔法字符串

```java
// 当前：分散在多处的字符串字面量，拼写错误编译期无法发现
if ("SUCCESS".equals(status)) { ... }
ToolCallResult.builder().status("ERROR").build();
Map.of("status", "running")  // 前后端约定全靠人脑记忆
```

### 问题 2 — ChatEvent 工厂方法里的 event type 字符串无集中定义

```java
// 当前：字符串散落在 ChatEvent.java 各工厂方法里
return new ChatEvent("tool_call", json);
return new ChatEvent("sources", json);
// 前端 SSE 解析里的 "tool_call" "sources" 必须和这里完全一致，靠人工对齐
```

### 问题 3 — 工具事件用 Map.of() 构造 JSON payload

```java
// 当前：ChatAppService.java 363-368 行
toolEvents.add(ChatEvent.toolCall(objectMapper.writeValueAsString(
        Map.of("tool", tr.getToolCode(), "status", "running"))));
toolEvents.add(ChatEvent.toolDone(objectMapper.writeValueAsString(
        Map.of("tool", tr.getToolCode(),
                "status", tr.getStatus(),      // String，无类型保障
                "duration_ms", tr.getDurationMs()))));
```

### 问题 4 — TransferResult 没有发 transfer 语义事件（功能缺口）

```java
// 当前：只发普通文字，前端收不到"切换人工模式"信号
return Flux.just(ChatEvent.data(r.replyMessage()));
// 结果：transferred.value 永远不会被前端自动设为 true
//       用户看到提示语但 WebSocket 不会建立
//       坐席侧收不到后续用户消息
```

---

## 改造清单

### 改造 1：新增 `ToolStatus` 枚举

**文件**: `infrastructure/dit/pipeline/ToolStatus.java`（新建）

```java
package com.aria.conversation.infrastructure.dit.pipeline;

/**
 * 工具调用执行状态枚举。
 *
 * <p>替代 ToolCallResult 中的魔法字符串 "SUCCESS"/"ERROR"/"TIMEOUT"/"SKIPPED"，
 * 实现编译期类型安全，消除跨层字符串约定。
 */
public enum ToolStatus {

    /** 工具调用成功，有有效响应 */
    SUCCESS,

    /** 工具调用失败（HTTP 非 2xx 或解析异常） */
    ERROR,

    /** 工具调用超时 */
    TIMEOUT,

    /** 工具被跳过（前置条件不满足） */
    SKIPPED
}
```

**文件**: `infrastructure/dit/pipeline/ToolCallResult.java`（修改）

```java
// 改动点 1：status 字段由 String 改为 ToolStatus 枚举
// 改动点 2：isSuccess() 改为枚举比较
// 改动点 3：三个静态工厂方法的 status 赋值由字符串改为枚举常量

@Data
@Builder
public class ToolCallResult {

    private String toolCode;
    private ToolStatus status;          // ← String → ToolStatus
    private String response;
    private Integer httpStatus;
    private long durationMs;
    private String errorMsg;

    /** 工具是否执行成功 */
    public boolean isSuccess() {
        return ToolStatus.SUCCESS == status;  // ← "SUCCESS".equals(status) 改为枚举比较
    }

    public static ToolCallResult success(String toolCode, String response,
                                         int httpStatus, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.SUCCESS)   // ← 枚举常量
                .response(response)
                .httpStatus(httpStatus)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult error(String toolCode, String errorMsg, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.ERROR)     // ← 枚举常量
                .errorMsg(errorMsg)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult timeout(String toolCode, long durationMs) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.TIMEOUT)   // ← 枚举常量
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult skipped(String toolCode) {
        return ToolCallResult.builder()
                .toolCode(toolCode)
                .status(ToolStatus.SKIPPED)   // ← 枚举常量
                .build();
    }
}
```

**受影响文件**（需同步修改）:
- `ToolExecutor.java` — `tr.getStatus()` 改为 `tr.getStatus().name()` 再传给日志/序列化
- `HttpToolRunner.java` — 返回值构造处无需改动，已走工厂方法

---

### 改造 2：`ChatEvent` 集中定义事件类型常量 + 新增 transfer 工厂方法

**文件**: `application/service/ChatEvent.java`（修改）

```java
package com.aria.conversation.application.service;

/**
 * 对话流事件，封装 SSE 事件类型和数据。
 *
 * <p>Application 层返回此对象，Interface 层（Controller）负责将其转换为
 * {@link org.springframework.http.codec.ServerSentEvent}。
 *
 * <p><b>事件类型约定（前后端共同遵守）</b>：所有 SSE event 名称统一在
 * {@link EventType} 内部类中定义，前端 SSE 解析必须引用相同字符串。
 */
public record ChatEvent(String eventType, String data) {

    // ----------------------------------------------------------------
    // SSE event type 集中定义（消除魔法字符串，前后端约定唯一来源）
    // 前端对应 currentEvent 判断逻辑必须与此处保持一致
    // ----------------------------------------------------------------
    public static final class EventType {
        /** 知识库溯源标签，data 为 JSON 数组 [{docId, label}] */
        public static final String SOURCES    = "sources";
        /** 槽位缺失，等待用户文字输入，data 为提示语字符串 */
        public static final String SLOT_ASK   = "slot_ask";
        /** 槽位发现候选项，等待用户选择，data 为 JSON 数组 [{id, label}] */
        public static final String CANDIDATES = "candidates";
        /** 工具调用开始，data 为 ToolCallPayload JSON */
        public static final String TOOL_CALL  = "tool_call";
        /** 工具调用完成，data 为 ToolDonePayload JSON */
        public static final String TOOL_DONE  = "tool_done";
        /** 自动转人工，data 为 TransferPayload JSON，前端收到后切换 WebSocket 模式 */
        public static final String TRANSFER   = "transfer";
        /** 业务错误，data 为错误描述字符串 */
        public static final String ERROR      = "error";

        private EventType() { /* 工具类，不允许实例化 */ }
    }

    // ----------------------------------------------------------------
    // 工厂方法（每个方法只使用 EventType 常量，不出现字符串字面量）
    // ----------------------------------------------------------------

    /** 普通 AI 回复 token（无 event 字段，SSE 默认事件） */
    public static ChatEvent data(String data) {
        return new ChatEvent(null, data);
    }

    /** 知识库溯源标签 */
    public static ChatEvent sources(String json) {
        return new ChatEvent(EventType.SOURCES, json);
    }

    /** 槽位缺失询问 */
    public static ChatEvent slotAsk(String json) {
        return new ChatEvent(EventType.SLOT_ASK, json);
    }

    /** 槽位候选项 */
    public static ChatEvent candidates(String json) {
        return new ChatEvent(EventType.CANDIDATES, json);
    }

    /** 工具调用开始 */
    public static ChatEvent toolCall(String json) {
        return new ChatEvent(EventType.TOOL_CALL, json);
    }

    /** 工具调用完成 */
    public static ChatEvent toolDone(String json) {
        return new ChatEvent(EventType.TOOL_DONE, json);
    }

    /**
     * 自动转人工信号。
     *
     * <p>前端收到此事件后必须：
     * <ol>
     *   <li>将 transferred 状态设为 true</li>
     *   <li>持久化到 localStorage（页面刷新后恢复）</li>
     *   <li>建立 WebSocket 连接到坐席系统</li>
     * </ol>
     */
    public static ChatEvent transfer(String json) {
        return new ChatEvent(EventType.TRANSFER, json);
    }
}
```

---

### 改造 3：新增 SSE Payload 记录类（替换 Map.of()）

**目录**: `application/service/payload/`（新建）

**文件**: `ToolCallPayload.java`

```java
package com.aria.conversation.application.service.payload;

/**
 * 工具调用开始的 SSE payload（对应 event:tool_call）。
 *
 * <p>序列化为 JSON 后发给前端，前端按字段名解析，
 * 不再依赖 Map 的任意字符串 key。
 *
 * @param tool   工具标识（即 cs_tool.code）
 * @param status 固定为 "running"，表示调用进行中
 */
public record ToolCallPayload(String tool, String status) {

    /** 构造"调用中"状态的 payload */
    public static ToolCallPayload running(String toolCode) {
        return new ToolCallPayload(toolCode, "running");
    }
}
```

**文件**: `ToolDonePayload.java`

```java
package com.aria.conversation.application.service.payload;

import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;

/**
 * 工具调用完成的 SSE payload（对应 event:tool_done）。
 *
 * @param tool       工具标识
 * @param status     执行结果，取 ToolStatus.name()（SUCCESS/ERROR/TIMEOUT/SKIPPED）
 * @param durationMs 工具执行耗时（毫秒）
 */
public record ToolDonePayload(String tool, String status, long durationMs) {

    /** 从 ToolCallResult 构造 payload，status 取枚举 name() 保证与前端约定一致 */
    public static ToolDonePayload from(ToolCallResult result) {
        return new ToolDonePayload(
                result.getToolCode(),
                result.getStatus().name(),  // 枚举 → 字符串，统一用 .name()
                result.getDurationMs()
        );
    }
}
```

**文件**: `TransferPayload.java`

```java
package com.aria.conversation.application.service.payload;

/**
 * 自动转人工的 SSE payload（对应 event:transfer）。
 *
 * <p>前端收到后根据 reason 可展示不同的提示，并统一触发 WebSocket 建立流程。
 *
 * @param reason 转人工原因标识（如意图 code，便于前端埋点/日志）
 */
public record TransferPayload(String reason) {}
```

---

### 改造 4：`ChatAppService` 修改两处

**文件**: `application/service/ChatAppService.java`

#### 4-A：TransferResult 分支补发 transfer 事件

```java
// 改动位置：buildDomainEventStream() 方法，TransferResult 分支
// 改动前（只发文字，无 transfer 信号）：
return Flux.just(ChatEvent.data(r.replyMessage()));

// 改动后（文字 + transfer 信号，前端可切换 WS 模式）：
if (route instanceof RouteResult.TransferResult r) {
    try {
        sessionQueueService.enqueue(sessionId, "访客",
                TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
    } catch (Exception e) {
        log.warn("[DIT] 自动转人工失败 sessionId={}", sessionId, e);
    }
    historyRepository.append(sessionId, ROLE_ASSISTANT, r.replyMessage());
    try {
        String transferJson = objectMapper.writeValueAsString(
                new TransferPayload(r.reason()));          // ← 结构化 payload，非 Map.of()
        return Flux.just(
                ChatEvent.data(r.replyMessage()),          // 文字提示（用户可读）
                ChatEvent.transfer(transferJson)           // 语义事件（前端切换 WS）
        );
    } catch (JsonProcessingException e) {
        log.warn("[DIT] transfer payload 序列化失败 sessionId={}", sessionId, e);
        return Flux.just(ChatEvent.data(r.replyMessage())); // 降级：只发文字
    }
}
```

#### 4-B：工具事件序列化改用 Payload 记录类

```java
// 改动位置：ExecuteResult 分支内，工具事件构造循环（原 363-368 行）
// 改动前：
toolEvents.add(ChatEvent.toolCall(objectMapper.writeValueAsString(
        Map.of("tool", tr.getToolCode(), "status", "running"))));
toolEvents.add(ChatEvent.toolDone(objectMapper.writeValueAsString(
        Map.of("tool", tr.getToolCode(),
                "status", tr.getStatus(),          // String，无类型保障
                "duration_ms", tr.getDurationMs()))));

// 改动后（使用 Payload 记录类，字段名有编译期保障）：
toolEvents.add(ChatEvent.toolCall(objectMapper.writeValueAsString(
        ToolCallPayload.running(tr.getToolCode()))));       // ← 结构化，无魔法字符串
toolEvents.add(ChatEvent.toolDone(objectMapper.writeValueAsString(
        ToolDonePayload.from(tr))));                        // ← 枚举 .name() 统一转换
```

---

### 改造 5：前端 SSE 解析补充 `transfer` 事件处理

**文件**: `apps/src/views/chat-widget/index.vue`

```typescript
// 改动位置：replyFor() 函数内 SSE 解析 for 循环，currentEvent 判断分支区域
// 在 tool_done 处理块之后，slot_ask 之前，新增：

} else if (currentEvent === 'transfer') {
  // 收到自动转人工信号：切换到人工接入模式
  // 1. 设置前端人工模式状态
  transferred.value = true;
  // 2. 持久化到 localStorage，刷新页面后能恢复 WS 连接
  localStorage.setItem(`chat_transferred_${sessionId.value}`, '1');
  // 3. 建立 WebSocket，开始接收坐席消息
  connectVisitorWsWithRetry(sessionId.value);
  // 注：对应的文字提示已通过普通 data: 事件渲染到气泡，此处无需重复显示
}
```

---

## 受影响文件汇总

| 文件 | 操作 | 说明 |
|------|------|------|
| `infrastructure/dit/pipeline/ToolStatus.java` | **新建** | ToolCallResult.status 的枚举定义 |
| `infrastructure/dit/pipeline/ToolCallResult.java` | **修改** | status 字段 String→ToolStatus，isSuccess() 改枚举比较 |
| `application/service/ChatEvent.java` | **修改** | 新增 EventType 常量类，新增 transfer() 工厂方法 |
| `application/service/payload/ToolCallPayload.java` | **新建** | tool_call 事件结构化 payload |
| `application/service/payload/ToolDonePayload.java` | **新建** | tool_done 事件结构化 payload |
| `application/service/payload/TransferPayload.java` | **新建** | transfer 事件结构化 payload |
| `application/service/ChatAppService.java` | **修改** | TransferResult 补发 transfer 事件；工具事件改用 Payload 类 |
| `apps/src/views/chat-widget/index.vue` | **修改** | SSE 解析新增 transfer 事件，触发 WS 建立 |

---

## 改造后 SSE 事件契约（前后端共同遵守）

| SSE event | data 格式 | 前端处理 |
|-----------|-----------|---------|
| `(无)` | AI 回复文字 token | 追加到气泡 text |
| `sources` | `[{docId, label}]` | 显示溯源标签 |
| `slot_ask` | 提示语字符串 | 显示输入框 |
| `candidates` | `[{id, label}]` | 显示候选项列表 |
| `tool_call` | `{tool, status:"running"}` | 气泡内显示工具转圈 |
| `tool_done` | `{tool, status, durationMs}` | 更新为完成状态 + 耗时 |
| `transfer` | `{reason}` | 切换到人工模式，建立 WS |
| `error` | 错误描述字符串 | 显示错误气泡 |
| `done` | `[DONE]` | 结束流式状态 |
