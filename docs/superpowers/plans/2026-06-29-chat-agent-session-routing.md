# Chat → Agent Session Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 打通访客对话页（/chat）与座席工作台（/customerservice/agent）的会话路由链路，实现用户转人工 → 座席接单 → 实时对话的完整流程。

**Architecture:** 在 conversation-service 中新增会话队列管理，用 Redis Hash 存储会话元数据，用 Redis Pub/Sub 向座席推送实时事件（SSE
长连接）。前端 /chat 页面增加"转人工"按钮，座席工作台通过 SSE 订阅新会话事件并拉取真实队列数据。

**Tech Stack:** Spring Boot 3, Redis Pub/Sub, SSE (ServerSentEvent), Vue 3, TypeScript

---

## 文件变更清单

### 后端 (conversation-service)

- Create: `application/service/SessionQueueService.java` — 会话队列 CRUD + 发布 Pub/Sub 事件
- Create: `interfaces/rest/SessionQueueController.java` — REST + SSE 接口
- Modify: `application/service/ChatAppService.java` — 注入 SessionQueueService，转人工时创建队列项
- Modify: `interfaces/rest/ChatController.java` — 新增 POST /api/v1/chat/transfer 接口

### 前端 (web-antd)

- Create: `src/api/session/index.ts` — 会话队列 API 封装
- Modify: `src/views/chat-widget/index.vue` — 增加"转人工"按钮和触发逻辑
- Modify: `src/views/customerservice/agent/index.vue` — 替换 mock 数据，接入真实 API + SSE

---

## Task 1: 后端 — SessionQueueService（Redis 会话队列）

**Files:**

- Create:
  `ai-conversation/conversation-service/src/main/java/com/aidevplatform/conversation/application/service/SessionQueueService.java`

- [ ] **Step 1: 创建 SessionQueueService**

```java
package com.aidevplatform.conversation.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 会话队列服务。
 * 用 Redis Hash 存储等待中的会话元数据，Pub/Sub 推送实时事件给座席。
 *
 * Redis 数据结构：
 *   Hash  agent:session:queue         → {sessionId: JSON(SessionQueueItem)}
 *   Hash  agent:session:active:{agentId} → {sessionId: JSON(SessionQueueItem)}
 *   Pub/Sub channel: agent:session:events → JSON 事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionQueueService {

    private static final String QUEUE_KEY = "agent:session:queue";
    private static final String EVENT_CHANNEL = "agent:session:events";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- VO ----

    public record SessionQueueItem(
        String sessionId,
        String userName,
        String transferReason,
        String tag,
        long waitSince,    // epoch seconds
        String status      // WAITING | ACTIVE | CLOSED
    ) {}

    public record SessionEvent(String type, SessionQueueItem item) {}

    // ---- 队列操作 ----

    /** 用户请求转人工，加入等待队列并广播事件 */
    public SessionQueueItem enqueue(String sessionId, String userName, String transferReason, String tag) {
        SessionQueueItem item = new SessionQueueItem(
            sessionId, userName, transferReason, tag,
            Instant.now().getEpochSecond(), "WAITING"
        );
        try {
            redis.opsForHash().put(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(item));
            publish(new SessionEvent("ENQUEUE", item));
            log.info("[SessionQueue] enqueue sessionId={} userName={}", sessionId, userName);
        } catch (Exception e) {
            log.error("[SessionQueue] enqueue error", e);
        }
        return item;
    }

    /** 查询等待队列（所有 WAITING 状态） */
    public List<SessionQueueItem> getQueue() {
        Map<Object, Object> all = redis.opsForHash().entries(QUEUE_KEY);
        List<SessionQueueItem> result = new ArrayList<>();
        for (Object val : all.values()) {
            try {
                SessionQueueItem item = objectMapper.readValue((String) val, SessionQueueItem.class);
                if ("WAITING".equals(item.status())) result.add(item);
            } catch (Exception ignored) {}
        }
        result.sort(Comparator.comparingLong(SessionQueueItem::waitSince));
        return result;
    }

    /** 座席接入会话，状态变为 ACTIVE */
    public SessionQueueItem accept(String sessionId) {
        try {
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw == null) throw new IllegalArgumentException("Session not found: " + sessionId);
            SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
            SessionQueueItem updated = new SessionQueueItem(
                old.sessionId(), old.userName(), old.transferReason(),
                old.tag(), old.waitSince(), "ACTIVE"
            );
            redis.opsForHash().put(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(updated));
            publish(new SessionEvent("ACCEPTED", updated));
            return updated;
        } catch (Exception e) {
            log.error("[SessionQueue] accept error sessionId={}", sessionId, e);
            throw new RuntimeException(e);
        }
    }

    /** 结束或转交会话 */
    public void close(String sessionId) {
        try {
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw != null) {
                SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
                SessionQueueItem closed = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), "CLOSED"
                );
                redis.opsForHash().delete(QUEUE_KEY, sessionId);
                publish(new SessionEvent("CLOSED", closed));
            }
        } catch (Exception e) {
            log.error("[SessionQueue] close error sessionId={}", sessionId, e);
        }
    }

    // ---- Pub/Sub ----

    public void publish(SessionEvent event) {
        try {
            redis.convertAndSend(EVENT_CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[SessionQueue] publish error", e);
        }
    }

    public String getEventChannel() { return EVENT_CHANNEL; }
}
```

- [ ] **Step 2: 重新编译确认无错误**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn install -DskipTests -pl ai-conversation/conversation-service -am -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

---

## Task 2: 后端 — SessionQueueController（REST + SSE）

**Files:**

- Create:
  `ai-conversation/conversation-service/src/main/java/com/aidevplatform/conversation/interfaces/rest/SessionQueueController.java`

- [ ] **Step 1: 创建 Controller**

```java
package com.aidevplatform.conversation.interfaces.rest;

import com.aidevplatform.conversation.application.service.SessionQueueService;
import com.aidevplatform.conversation.application.service.SessionQueueService.SessionQueueItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 座席会话队列接口。
 *
 * GET  /api/v1/sessions/queue         → 获取等待队列列表
 * POST /api/v1/sessions/{id}/accept   → 接入会话
 * POST /api/v1/sessions/{id}/close    → 结束会话
 * GET  /api/v1/sessions/events        → SSE 实时事件流（座席订阅）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class SessionQueueController {

    private final SessionQueueService queueService;
    private final RedisMessageListenerContainer listenerContainer;

    // 每个 SSE 连接对应一个 Sink
    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /** 查询等待队列 */
    @GetMapping("/queue")
    public List<SessionQueueItem> getQueue() {
        return queueService.getQueue();
    }

    /** 接入会话 */
    @PostMapping("/{sessionId}/accept")
    public SessionQueueItem accept(@PathVariable String sessionId) {
        return queueService.accept(sessionId);
    }

    /** 结束/转交会话 */
    @PostMapping("/{sessionId}/close")
    public Map<String, String> close(@PathVariable String sessionId) {
        queueService.close(sessionId);
        return Map.of("message", "会话已结束", "sessionId", sessionId);
    }

    /**
     * SSE 事件流：座席长连接订阅会话队列变更事件。
     * 每当有用户转人工、座席接入、会话结束时推送。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events() {
        String sinkId = "sink-" + System.currentTimeMillis();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinks.put(sinkId, sink);

        // 订阅 Redis Pub/Sub，推送到此 sink
        MessageListener listener = (message, pattern) -> {
            String payload = new String(message.getBody());
            sink.tryEmitNext(payload);
        };
        listenerContainer.addMessageListener(listener,
            new ChannelTopic(queueService.getEventChannel()));

        return sink.asFlux()
            .map(data -> ServerSentEvent.<String>builder().data(data).build())
            // 心跳：每 20 秒发一次空注释保持连接
            .mergeWith(Flux.interval(Duration.ofSeconds(20))
                .map(i -> ServerSentEvent.<String>builder().comment("heartbeat").build()))
            .doOnCancel(() -> {
                sinks.remove(sinkId);
                listenerContainer.removeMessageListener(listener);
                log.info("[SSE] agent disconnected sinkId={}", sinkId);
            });
    }
}
```

- [ ] **Step 2: 在 conversation-service application.yml 确认 Redis 配置存在（已有，无需修改）**

- [ ] **Step 3: 注册 RedisMessageListenerContainer Bean（如无则添加配置类）**

创建 `infrastructure/config/RedisConfig.java`：

```java
package com.aidevplatform.conversation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn install -DskipTests -pl ai-conversation/conversation-service -am -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

---

## Task 3: 后端 — ChatController 新增转人工接口

**Files:**

- Modify:
  `ai-conversation/conversation-service/src/main/java/com/aidevplatform/conversation/interfaces/rest/ChatController.java`

- [ ] **Step 1: 注入 SessionQueueService，添加 transfer 接口**

在 `ChatController` 中添加：

```java
// 在类顶部添加字段
private final SessionQueueService sessionQueueService;

// 修改构造器
public ChatController(ChatAppService chatService, SessionQueueService sessionQueueService) {
    this.chatService = chatService;
    this.sessionQueueService = sessionQueueService;
}

// 新增接口
/**
 * 用户请求转人工。
 * POST /api/v1/chat/transfer
 * Body: { sessionId, userName, transferReason, tag }
 */
@PostMapping("/transfer")
public SessionQueueService.SessionQueueItem transfer(@RequestBody TransferRequest req) {
    String reason = req.getTransferReason() != null ? req.getTransferReason() : "用户主动请求转人工";
    String tag    = req.getTag()            != null ? req.getTag()            : "咨询";
    return sessionQueueService.enqueue(req.getSessionId(), req.getUserName(), reason, tag);
}

// 添加内部类
public static class TransferRequest {
    private String sessionId;
    private String userName;
    private String transferReason;
    private String tag;
    // getters & setters
    public String getSessionId()       { return sessionId; }
    public void   setSessionId(String v)       { this.sessionId = v; }
    public String getUserName()        { return userName; }
    public void   setUserName(String v)        { this.userName = v; }
    public String getTransferReason()  { return transferReason; }
    public void   setTransferReason(String v)  { this.transferReason = v; }
    public String getTag()             { return tag; }
    public void   setTag(String v)             { this.tag = v; }
}
```

- [ ] **Step 2: 编译并重启 conversation-service**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn install -DskipTests -pl ai-conversation/conversation-service -am -q 2>&1 | tail -5
# kill & restart
lsof -ti :8082 | xargs kill -9 2>/dev/null
cd ai-conversation/conversation-service
nohup mvn spring-boot:run > /Users/lycodeing/logs/cs-conversation.log 2>&1 &
sleep 20 && curl -s http://localhost:8082/api/v1/sessions/queue
```

Expected: `[]`（空数组，服务正常）

---

## Task 4: 前端 — 会话 API 封装

**Files:**

- Create: `apps/web-antd/src/api/session/index.ts`

- [ ] **Step 1: 创建 session API 文件**

```typescript
// src/api/session/index.ts
// rawRequestClient baseURL 为空，路径直接命中 /chat-api vite proxy
import { rawRequestClient } from '#/api/request';

const client = rawRequestClient;

export interface SessionQueueItem {
  sessionId: string;
  userName: string;
  transferReason: string;
  tag: string;
  waitSince: number; // epoch seconds
  status: 'ACTIVE' | 'CLOSED' | 'WAITING';
}

/** 获取等待队列 */
export async function getSessionQueueApi(): Promise<SessionQueueItem[]> {
  return client.get('/chat-api/sessions/queue');
}

/** 座席接入会话 */
export async function acceptSessionApi(sessionId: string): Promise<SessionQueueItem> {
  return client.post(`/chat-api/sessions/${sessionId}/accept`);
}

/** 结束会话 */
export async function closeSessionApi(sessionId: string): Promise<void> {
  return client.post(`/chat-api/sessions/${sessionId}/close`);
}

/** 用户请求转人工 */
export async function transferToAgentApi(params: {
  sessionId: string;
  userName: string;
  transferReason?: string;
  tag?: string;
}): Promise<SessionQueueItem> {
  return client.post('/chat-api/chat/transfer', params);
}

/**
 * 座席订阅 SSE 事件流。
 * 返回 EventSource 实例，调用方负责 close()。
 */
export function subscribeSessionEvents(
  onEvent: (event: { type: string; item: SessionQueueItem }) => void,
  onError?: () => void,
): EventSource {
  // SSE 通过浏览器原生 EventSource，不经过 axios
  const es = new EventSource('/chat-api/sessions/events');
  es.onmessage = (e) => {
    try {
      const data = JSON.parse(e.data);
      onEvent(data);
    } catch {
      // ignore malformed
    }
  };
  if (onError) es.onerror = onError;
  return es;
}
```

---

## Task 5: 前端 — /chat 页面增加"转人工"功能

**Files:**

- Modify: `apps/web-antd/src/views/chat-widget/index.vue`

- [ ] **Step 1: 在 script 顶部导入 API**

在 `<script lang="ts" setup>` 第一行后追加：

```typescript
import { transferToAgentApi } from '#/api/session';
```

- [ ] **Step 2: 添加转人工状态和方法**

在 `const streaming = ref(false);` 后添加：

```typescript
const transferred = ref(false); // 是否已转人工

async function requestTransfer() {
  if (transferred.value) return;
  try {
    await transferToAgentApi({
      sessionId: sessionId.value,
      userName: isAuth.value ? authLabel.value : '访客',
      transferReason: '用户主动请求转人工',
      tag: '咨询',
    });
    transferred.value = true;
    addMsg('ai', '✅ 已为您转接人工客服，请稍候，座席将在 1-3 分钟内接入。');
  } catch {
    addMsg('ai', '转接失败，请稍后重试或直接拨打客服热线。');
  }
}
```

- [ ] **Step 3: 在模板顶栏加"转人工"按钮**

在顶栏 `<div class="ml-auto ...">` 内，在已有的登录状态 `<span>` 前添加：

```html
<Button
  v-if="!transferred"
  size="small"
  @click="requestTransfer"
>
  <template #icon><Icon icon="lucide:headphones" /></template>
  转人工
</Button>
<Tag v-else color="success" class="text-xs">已转人工</Tag>
```

---

## Task 6: 前端 — 座席工作台接入真实 API + SSE

**Files:**

- Modify: `apps/web-antd/src/views/customerservice/agent/index.vue`

- [ ] **Step 1: 替换 import，引入真实 API**

在 `<script lang="ts" setup>` 中，在已有 import 后追加：

```typescript
import { onUnmounted } from 'vue';
import {
  acceptSessionApi,
  closeSessionApi,
  getSessionQueueApi,
  subscribeSessionEvents,
  type SessionQueueItem as ApiSessionItem,
} from '#/api/session';
import { rawRequestClient } from '#/api/request';
```

- [ ] **Step 2: 替换 queue 的加载逻辑**

将原有的硬编码 `queue` ref 声明：

```typescript
const queue = ref<QueueItem[]>([
  { id: 'q1', ... },
  { id: 'q2', ... },
]);
```

替换为：

```typescript
const queue = ref<QueueItem[]>([]);

// 从 API 加载等待队列
async function loadQueue() {
  try {
    const items = await getSessionQueueApi();
    queue.value = items.map((item) => ({
      id: item.sessionId,
      name: item.userName,
      color: '#f87171',
      waitMin: formatWaitTime(item.waitSince),
      reason: item.transferReason,
      tag: item.tag,
      tagColor: item.tag === '投诉' ? 'red' : item.tag === '退款' ? 'orange' : 'blue',
    }));
  } catch {
    // 加载失败时保持空队列
  }
}

function formatWaitTime(waitSince: number): string {
  const sec = Math.floor(Date.now() / 1000 - waitSince);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}
```

- [ ] **Step 3: 在 onMounted 中启动 SSE 订阅**

替换 `onMounted(loadRoles)` → 在 script 末尾添加：

```typescript
import { onMounted } from 'vue'; // 已有，确认存在

let eventSource: EventSource | null = null;

onMounted(async () => {
  await loadQueue();
  // 订阅 SSE 实时事件
  eventSource = subscribeSessionEvents((event) => {
    if (event.type === 'ENQUEUE') {
      // 新用户进队列，追加
      const item = event.item;
      if (!queue.value.find((q) => q.id === item.sessionId)) {
        queue.value.push({
          id: item.sessionId,
          name: item.userName,
          color: '#f87171',
          waitMin: '刚进入',
          reason: item.transferReason,
          tag: item.tag,
          tagColor: item.tag === '投诉' ? 'red' : item.tag === '退款' ? 'orange' : 'blue',
        });
        message.info(`新会话请求：${item.userName}`);
      }
    } else if (event.type === 'ACCEPTED' || event.type === 'CLOSED') {
      // 从队列移除
      queue.value = queue.value.filter((q) => q.id !== event.item.sessionId);
    }
  });
});

onUnmounted(() => {
  eventSource?.close();
});
```

- [ ] **Step 4: 替换 acceptQueue 方法，调用真实 API**

将原有 `acceptQueue` 替换为：

```typescript
async function acceptQueue(item: QueueItem) {
  if (concurrent.value >= MAX_CONCURRENT) {
    message.warning('已达最大并发数（5），请先结束其他会话');
    return;
  }
  try {
    await acceptSessionApi(item.id);
    // 从队列移除
    queue.value = queue.value.filter((q) => q.id !== item.id);
    // 加载该会话历史消息
    const history = await rawRequestClient.get(
      `/chat-api/chat/history?sessionId=${item.id}`
    ) as Array<{ role: string; content: string }>;
    const msgs: Msg[] = history.map((h, i) => ({
      id: i + 1,
      role: h.role === 'user' ? 'user' : 'ai',
      text: h.content,
    }));
    sessions.value.forEach((s) => (s.active = false));
    sessions.value.push({
      id: item.id,
      name: item.name,
      nameChar: item.name[0]!,
      color: item.color,
      min: '刚接入',
      active: true,
      sessionCode: `#${item.id}`,
      transferReason: item.reason,
      msgs: msgs.length > 0 ? msgs : [{ id: 1, role: 'ai', text: '您好！请问有什么可以帮您？' }],
      userInfo: [{ label: '姓名', value: item.name }],
      slots: [],
      chunks: [],
      memory: '无历史记忆。',
    });
    message.success(`已接入会话：${item.name}`);
  } catch {
    message.error('接入失败，请重试');
  }
}
```

- [ ] **Step 5: 替换 doCloseSession，调用真实 API**

```typescript
async function doCloseSession() {
  const sid = activeSession.value?.id;
  if (!sid) return;
  try {
    await closeSessionApi(sid);
  } catch { /* 忽略关闭失败 */ }
  sessions.value = sessions.value.filter((s) => s.id !== sid);
  if (sessions.value.length > 0) sessions.value[0]!.active = true;
  message.success('会话已结束，正在生成长期记忆摘要...');
}
```

---

## Task 7: 验证全流程

- [ ] **Step 1: 重新编译并重启后端**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn install -DskipTests -q 2>&1 | tail -5
lsof -ti :8082 | xargs kill -9 2>/dev/null
sleep 2
cd ai-conversation/conversation-service
nohup mvn spring-boot:run > /Users/lycodeing/logs/cs-conversation.log 2>&1 &
sleep 20 && curl -s http://localhost:8082/api/v1/sessions/queue
```

Expected: `[]`

- [ ] **Step 2: 模拟用户转人工**

```bash
curl -s -X POST http://localhost:8082/api/v1/chat/transfer \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-001","userName":"测试用户","transferReason":"测试转人工","tag":"咨询"}' \
  | python3 -m json.tool
```

Expected: `{"sessionId":"test-001","userName":"测试用户","status":"WAITING",...}`

- [ ] **Step 3: 查询队列确认数据存在**

```bash
curl -s http://localhost:8082/api/v1/sessions/queue | python3 -m json.tool
```

Expected: 包含 `test-001` 的数组

- [ ] **Step 4: 打开浏览器验证端对端**

1. 打开 http://localhost:5667/chat
2. 发送几条消息
3. 点击「转人工」按钮
4. 打开 http://localhost:5667/customerservice/agent（需登录）
5. 确认左侧等待队列中出现该用户
6. 点击「接入会话」，确认右侧显示对话历史
7. 点击「结束会话」，确认用户从队列消失
