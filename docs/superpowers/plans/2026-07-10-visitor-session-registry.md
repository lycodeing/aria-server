### Task 2: 重构 ChatWebSocketHandler — 移除 session 管理，委托给 VisitorSessionRegistry

**Files:**
- Modify: `infrastructure/websocket/ChatWebSocketHandler.java`
- Test: 现有测试应全部通过，无需新增测试（逻辑委托，不新增行为）

**Interfaces:**
- Consumes: `VisitorSessionRegistry`（Task 1）替换 visitorSessions map + sendLocks + heartbeat 相关代码
- Produces: `ChatWebSocketHandler` 只剩协议适配职责（parsePath + handleTextMessage + notifyAgent）

- [ ] **Step 1: 阅读当前 ChatWebSocketHandler 完整文件，确认要删除的字段和方法**

需要删除的内容：
- `visitorSessions` 字段（ConcurrentHashMap）
- `sendLocks` 字段（ConcurrentHashMap）
- `presenceRegistry` 字段（改由 VisitorSessionRegistry 内部管理）
- `podIdentity` 字段（改由 VisitorSessionRegistry 内部管理）
- `visitorHeartbeatScheduler` 字段
- `visitorHeartbeats` 字段
- `sendJson()` 私有方法（改由 VisitorSessionRegistry 内部实现）
- `closeStaleSession()` 私有方法（改由 VisitorSessionRegistry.register 内部处理）
- `@PreDestroy shutdown()` 方法（移到 VisitorSessionRegistry）
- `notifyVisitor()` 实现（委托给 visitorSessionRegistry）
- `closeVisitorSessionNormal()` 实现（委托给 visitorSessionRegistry）

需要新增的内容：
- `visitorSessionRegistry` 字段（final，Lombok 注入）

- [ ] **Step 2: 实现重构后的 ChatWebSocketHandler**

新的字段列表（严格按顺序，与 Lombok 构造器参数顺序一致）：

```java
private final ObjectMapper objectMapper;
private final ConversationHistoryRepository historyRepository;
private final SessionQueueService sessionQueueService;
private final AgentConnectionRegistry agentConnectionRegistry;
private final VisitorSessionRegistry visitorSessionRegistry;
private final WsMessageRouter router;
```

`afterConnectionEstablished` 访客路径改为：
```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessionRegistry.register(sessionId, session);
    log.info("[WS] visitor connected sessionId={}", sessionId);
    visitorSessionRegistry.notifyVisitor(sessionId, WsConnectedMessage.forVisitor(sessionId));
}
```

`afterConnectionClosed` 访客路径改为：
```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessionRegistry.unregister(sessionId, session);
    log.info("[WS] visitor disconnected sessionId={}", sessionId);
}
```

`handleTransportError` 访客路径改为：
```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessionRegistry.unregister(sessionId, session);
}
```

`notifyVisitor` 委托：
```java
@Override
public void notifyVisitor(String sessionId, Object payload) {
    visitorSessionRegistry.notifyVisitor(sessionId, payload);
}

@Override
public void closeVisitorSessionNormal(String sessionId) {
    visitorSessionRegistry.closeVisitorSessionNormal(sessionId);
}
```

移除：`sendJson()`、`closeStaleSession()`、`@PreDestroy shutdown()`、所有心跳相关代码。

移除 import：`ConcurrentHashMap`、`Executors`、`ScheduledExecutorService`、`ScheduledFuture`、`PreDestroy`、`PodIdentity`、`WsPresenceRegistry`（这两个由 VisitorSessionRegistry 管理）。

- [ ] **Step 3: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错

- [ ] **Step 4: 运行现有相关测试确认无回归**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="ChatWebSocketHandlerNotifyAgentTest,ChatWebSocketHandlerSessionIdTest,ChatWebSocketHandlerPresenceTest" -q 2>&1 | tail -10
```

注意：`ChatWebSocketHandlerPresenceTest` 现有测试需要同步更新——它 mock 的是 `presenceRegistry` 和 `podIdentity`，现在改为 mock `visitorSessionRegistry`：

```java
// 更新 ChatWebSocketHandlerPresenceTest
@Mock VisitorSessionRegistry visitorSessionRegistry;

// setUp 中的 handler 构造器参数对应新字段顺序
handler = new ChatWebSocketHandler(
        new ObjectMapper(), historyRepository, sessionQueueService,
        agentConnectionRegistry, visitorSessionRegistry, null /* router */);

// 测试断言改为
verify(visitorSessionRegistry).register("sess-001", session);
```

期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandlerPresenceTest.java
git commit -m "refactor(ws): ChatWebSocketHandler 委托 session 管理给 VisitorSessionRegistry，职责纯化为协议适配"
```

---

### Task 3: 更新 WsMessageRouter + WsDeliveryConsumer + 移除循环依赖配置

**Files:**
- Modify: `infrastructure/websocket/cluster/WsMessageRouter.java`
- Modify: `infrastructure/websocket/cluster/WsDeliveryConsumer.java`
- Modify: `resources/application.yml`

**Interfaces:**
- Consumes: `VisitorSessionRegistry`（Task 1）替换 `VisitorNotifier` + `ApplicationContext` 查找

- [ ] **Step 1: 修改 WsMessageRouter**

将 `ApplicationContext` 字段和运行时查找改回直接注入 `VisitorSessionRegistry`：

```java
// 移除 ApplicationContext 相关代码
// 改为直接注入（无循环，VisitorSessionRegistry 不依赖 WsMessageRouter）
private final PodIdentity              podIdentity;
private final WsPresenceRegistry       presenceRegistry;
private final AgentConnectionRegistry  agentRegistry;
private final VisitorSessionRegistry   visitorSessionRegistry;
private final RabbitTemplate           rabbitTemplate;
private final ObjectMapper             objectMapper;
```

`sendToVisitor` 本地路径改为：
```java
if (podIdentity.isLocal(pod)) {
    visitorSessionRegistry.notifyVisitor(sessionId, payload);
}
```

移除 `ApplicationContext` import，移除 `VisitorNotifier` import。

- [ ] **Step 2: 修改 WsDeliveryConsumer**

将 `VisitorNotifier visitorNotifier` 改为 `VisitorSessionRegistry visitorSessionRegistry`：

```java
private final AgentConnectionRegistry  agentRegistry;
private final VisitorSessionRegistry   visitorSessionRegistry;
private final ObjectMapper             objectMapper;
```

`VISITOR` case 改为：
```java
case VISITOR -> {
    Object payload = restorePayload(cmd);
    visitorSessionRegistry.notifyVisitor(cmd.targetId(), payload);
}
```

移除 `VisitorNotifier` import，添加 `VisitorSessionRegistry` import（同包，不需要 import，直接使用）。

- [ ] **Step 3: 移除 application.yml 中的 allow-circular-references**

删除以下配置：
```yaml
spring:
  main:
    allow-circular-references: true
```
及其注释。

- [ ] **Step 4: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsMessageRouter.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsDeliveryConsumer.java \
        ai-conversation/conversation-service/src/main/resources/application.yml
git commit -m "refactor(ws): WsMessageRouter + WsDeliveryConsumer 注入 VisitorSessionRegistry，移除 allow-circular-references"
```

---

### Task 4: 全量测试验证 + 重新打包部署

**Files:**
- No new files

- [ ] **Step 1: 运行全量测试**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test -q 2>&1 | tail -15
```
期望：`BUILD SUCCESS`，所有测试通过，无 FAILED/ERROR

- [ ] **Step 2: 验证无循环依赖配置**

```bash
grep -n "allow-circular-references" \
    ai-conversation/conversation-service/src/main/resources/application.yml
```
期望：无输出

- [ ] **Step 3: 验证 VisitorSessionRegistry 替代了 ChatWebSocketHandler 的 session 管理**

```bash
grep -n "visitorSessions\|visitorHeartbeat\|closeStaleSession\|ScheduledExecutorService" \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java
```
期望：无输出（全部迁移到 VisitorSessionRegistry）

- [ ] **Step 4: 打包**

```bash
mvn -f ai-conversation/conversation-service/pom.xml package -DskipTests -q && echo "打包成功"
```
期望：打包成功

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "test(ws): 全量测试验证通过，VisitorSessionRegistry 改造完成，循环依赖彻底消除"
```

### Task 1: 新增 VisitorSessionRegistry

**Files:**
- Create: `infrastructure/websocket/VisitorSessionRegistry.java`
- Test: `test/.../infrastructure/websocket/VisitorSessionRegistryTest.java`

**Interfaces:**
- Consumes: `WsPresenceRegistry`（cluster 包）；`PodIdentity`（cluster 包）；`ObjectMapper`
- Produces:
  - `register(String sessionId, WebSocketSession session)` — 连接建立时调用
  - `unregister(String sessionId, WebSocketSession session)` — 原子条件删除，防重连竞态
  - `notifyVisitor(String sessionId, Object payload)` — 实现 VisitorNotifier
  - `closeVisitorSessionNormal(String sessionId)` — 实现 VisitorNotifier
  - `shutdown()` — @PreDestroy

- [ ] **Step 1: 写失败测试**

```java
// test/.../infrastructure/websocket/VisitorSessionRegistryTest.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VisitorSessionRegistry")
class VisitorSessionRegistryTest {

    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private VisitorSessionRegistry registry;

    @BeforeEach
    void setUp() {
        when(podIdentity.get()).thenReturn("pod-A");
        registry = new VisitorSessionRegistry(new ObjectMapper(), presenceRegistry, podIdentity);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    @Test
    @DisplayName("register 时调用 presenceRegistry.registerVisitor")
    void register_calls_presence() {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        verify(presenceRegistry).registerVisitor("sess-1", "pod-A");
    }

    @Test
    @DisplayName("unregister 原子删除：本 session 是当前活跃连接时才清理 presence")
    void unregister_atomic_removes_presence_when_active() {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        registry.unregister("sess-1", session);
        verify(presenceRegistry).unregisterVisitor("sess-1");
    }

    @Test
    @DisplayName("unregister 原子删除：本 session 已不是活跃连接时不清理 presence（防重连竞态）")
    void unregister_skips_presence_when_replaced() {
        WebSocketSession oldSession = openSession("ws-old");
        WebSocketSession newSession = openSession("ws-new");
        registry.register("sess-1", oldSession);
        registry.register("sess-1", newSession);   // 新连接替换旧连接
        registry.unregister("sess-1", oldSession);  // 旧连接关闭
        verify(presenceRegistry, never()).unregisterVisitor("sess-1");
    }

    @Test
    @DisplayName("notifyVisitor 向 session 发送 JSON 消息")
    void notifyVisitor_sends_message() throws Exception {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        registry.notifyVisitor("sess-1", java.util.Map.of("type", "MESSAGE"));
        verify(session).sendMessage(any(TextMessage.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=VisitorSessionRegistryTest -q 2>&1 | tail -5
```
期望：编译失败，`VisitorSessionRegistry` 类不存在

- [ ] **Step 3: 实现 VisitorSessionRegistry**

```java
// infrastructure/websocket/VisitorSessionRegistry.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 访客 WebSocket 连接注册表。
 *
 * <p>职责：管理访客单连接的注册/注销/推送，同步维护 Redis presence + 启停心跳。
 * 与 {@link AgentConnectionRegistry} 对称，是 infrastructure 层的 Session Repository。
 *
 * <p>实现 {@link VisitorNotifier} 接口，是 {@link WsMessageRouter} 和
 * {@link com.aria.conversation.infrastructure.websocket.cluster.WsDeliveryConsumer}
 * 的本地访客推送入口，与 {@link ChatWebSocketHandler}（WS 协议适配器）不存在循环依赖。
 *
 * <p>访客每个 sessionId 始终只有一个活跃连接（重连时 {@link #register} 替换旧连接）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorSessionRegistry implements VisitorNotifier {

    private final ObjectMapper      objectMapper;
    private final WsPresenceRegistry presenceRegistry;
    private final PodIdentity       podIdentity;

    /** sessionId → 活跃 WS 连接 */
    private final ConcurrentHashMap<String, WebSocketSession> visitorSessions  = new ConcurrentHashMap<>();

    /** per-session 发送锁：wsSession.getId() → lock */
    private final ConcurrentHashMap<String, Object>           sendLocks        = new ConcurrentHashMap<>();

    /** per-session 心跳任务：wsSession.getId() → ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats     = new ConcurrentHashMap<>();

    /** 心跳调度器：内部初始化，不作为 @Bean 注入 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2);

    /**
     * 访客连接建立：注册 session、写 Redis presence、启动心跳。
     * 若已存在旧连接（重连场景），关闭旧连接并清理旧 sendLock/heartbeat。
     *
     * @param sessionId 会话 ID
     * @param session   WS 连接
     */
    public void register(String sessionId, WebSocketSession session) {
        WebSocketSession oldSession = visitorSessions.put(sessionId, session);
        closeStaleSession(sessionId, oldSession);
        presenceRegistry.registerVisitor(sessionId, podIdentity.get());
        ScheduledFuture<?> hb = heartbeatScheduler.scheduleAtFixedRate(
                () -> presenceRegistry.refreshVisitor(sessionId),
                30, 30, TimeUnit.SECONDS);
        heartbeats.put(session.getId(), hb);
        log.debug("[VisitorRegistry] 注册连接 sessionId={} wsId={}", sessionId, session.getId());
    }

    /**
     * 访客连接断开：原子条件删除，防止重连竞态。
     * 仅当 {@code session} 仍是 {@code sessionId} 的活跃连接时，才清理 presence。
     * 若访客已重连（新 session 已替换旧 session），旧 session 关闭不影响新 session 的 presence。
     *
     * @param sessionId 会话 ID
     * @param session   断开的 WS 连接
     */
    public void unregister(String sessionId, WebSocketSession session) {
        sendLocks.remove(session.getId());
        ScheduledFuture<?> hb = heartbeats.remove(session.getId());
        if (hb != null) hb.cancel(false);
        boolean removed = visitorSessions.remove(sessionId, session);
        if (removed) {
            presenceRegistry.unregisterVisitor(sessionId);
            log.debug("[VisitorRegistry] 注销连接 sessionId={} wsId={}", sessionId, session.getId());
        }
    }

    /**
     * 向访客推送消息（本地 session 直推）。
     * 若访客不在线或 session 已关闭，打印 warn 日志后跳过。
     */
    @Override
    public void notifyVisitor(String sessionId, Object payload) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs == null || !vs.isOpen()) {
            log.warn("[VisitorRegistry] notifyVisitor 跳过：sessionId={} 不在线", sessionId);
            return;
        }
        sendJson(sessionId, vs, payload);
    }

    /**
     * 以正常状态（code=1000 NORMAL）关闭访客端 WS。
     * 用于座席主动结束会话时通知访客端。
     */
    @Override
    public void closeVisitorSessionNormal(String sessionId) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs != null && vs.isOpen()) {
            try {
                vs.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] closeNormal IO 异常 sessionId={} msg={}", sessionId, e.getMessage());
            } finally {
                boolean removed = visitorSessions.remove(sessionId, vs);
                if (removed) {
                    presenceRegistry.unregisterVisitor(sessionId);
                    ScheduledFuture<?> hb = heartbeats.remove(vs.getId());
                    if (hb != null) hb.cancel(false);
                    sendLocks.remove(vs.getId());
                }
            }
        }
    }

    /**
     * 优雅关闭心跳调度器。
     * Spring 容器停止时调用，确保 JVM 可以正常退出。
     */
    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[VisitorRegistry] 心跳调度器已关闭");
    }

    /**
     * 关闭已被替换的旧连接，清理其 sendLock 和心跳。
     */
    private void closeStaleSession(String sessionId, WebSocketSession stale) {
        if (stale == null) return;
        sendLocks.remove(stale.getId());
        ScheduledFuture<?> hb = heartbeats.remove(stale.getId());
        if (hb != null) hb.cancel(false);
        if (stale.isOpen()) {
            try {
                stale.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] 关闭旧连接失败 sessionId={} wsId={} msg={}",
                        sessionId, stale.getId(), e.getMessage());
            }
        }
    }

    /**
     * 向指定 WS 连接发送 JSON 消息，使用 per-session 锁防止并发帧损坏。
     */
    private void sendJson(String sessionId, WebSocketSession session, Object payload) {
        Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            if (!session.isOpen()) return;
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[VisitorRegistry] payload 序列化失败 sessionId={}", sessionId, e);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] 发送失败 sessionId={} msg={}", sessionId, e.getMessage());
                try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=VisitorSessionRegistryTest -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，4 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/VisitorSessionRegistry.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/VisitorSessionRegistryTest.java
git commit -m "feat(ws): 新增 VisitorSessionRegistry，实现 VisitorNotifier，消除循环依赖根因"
```

---

# VisitorSessionRegistry 改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提取 `VisitorSessionRegistry` 组件，将访客 session 管理从 `ChatWebSocketHandler` 中分离，消除 `ChatWebSocketHandler ↔ WsMessageRouter` 的循环依赖，使架构与座席侧 `AgentConnectionRegistry` 完全对称，符合 DDD infrastructure 层职责划分原则。

**Architecture:** 新增 `VisitorSessionRegistry implements VisitorNotifier`，持有访客 `visitorSessions` map、presence 注册/心跳、帧发送锁。`ChatWebSocketHandler` 委托给 `VisitorSessionRegistry`，不再直接持有 session map。`WsMessageRouter` 改为注入 `VisitorSessionRegistry` 替代 `VisitorNotifier`，循环依赖彻底消除。

**Tech Stack:** Java 17、Spring Boot WebSocket、Lombok、JUnit 5 + Mockito

## Global Constraints

- 模块：`ai-conversation/conversation-service`
- 包根：`com.aria.conversation.infrastructure.websocket`
- 编译命令：`mvn -f ai-conversation/conversation-service/pom.xml compile -q`
- 测试命令：`mvn -f ai-conversation/conversation-service/pom.xml test -Dtest=<ClassName> -q`
- Java 版本：17（不使用 Java 21 特性）
- `@RequiredArgsConstructor` + `final` 字段注入，禁用 `@Autowired`（循环依赖处理除外）
- `application.yml` 中的 `spring.main.allow-circular-references: true` 在本次改造完成后移除
- 所有 public 类和方法必须有 Javadoc
- `AgentConnectionRegistry` 代码风格是参考基准

## 文件改动总览

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `infrastructure/websocket/VisitorSessionRegistry.java` | 访客 session 存储+推送，实现 VisitorNotifier |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java` | 移除 session 管理，委托给 VisitorSessionRegistry |
| 修改 | `infrastructure/websocket/cluster/WsMessageRouter.java` | VisitorNotifier → VisitorSessionRegistry |
| 修改 | `infrastructure/websocket/cluster/WsDeliveryConsumer.java` | VisitorNotifier → VisitorSessionRegistry |
| 修改 | `resources/application.yml` | 移除 allow-circular-references |

---
