# VisitorSessionRegistry 改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提取 `VisitorSessionRegistry` 组件，消除 `ChatWebSocketHandler ↔ WsMessageRouter` 的循环依赖，使架构与 `AgentConnectionRegistry` 完全对称，符合 DDD infrastructure 层职责划分原则。

**Architecture:** 新增 `VisitorSessionRegistry implements VisitorNotifier`，持有访客 session map、presence 注册/心跳、帧发送锁。`ChatWebSocketHandler` 委托给 `VisitorSessionRegistry`，只保留协议适配职责（parsePath + handleTextMessage + notifyAgent）。`WsMessageRouter` 改为直接注入 `VisitorSessionRegistry`，彻底消除循环依赖，同时移除 `application.yml` 中的 `allow-circular-references: true` 配置。

**Tech Stack:** Java 17、Spring Boot WebSocket、Lombok、JUnit 5 + Mockito

## Global Constraints

- 模块：`ai-conversation/conversation-service`
- 包根：`com.aria.conversation.infrastructure.websocket`
- 编译命令：`mvn -f ai-conversation/conversation-service/pom.xml compile -q`
- 测试命令：`mvn -f ai-conversation/conversation-service/pom.xml test -Dtest=<ClassName> -q`
- Java 版本：17
- `@RequiredArgsConstructor` + `final` 字段注入，禁用 `@Autowired`
- 所有 public 类和方法必须有 Javadoc
- `AgentConnectionRegistry` 是 `VisitorSessionRegistry` 的参考基准（对称设计）

## 文件改动总览

| 操作 | 文件 |
|------|------|
| 新增 | `infrastructure/websocket/VisitorSessionRegistry.java` |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java` |
| 修改 | `infrastructure/websocket/cluster/WsMessageRouter.java` |
| 修改 | `infrastructure/websocket/cluster/WsDeliveryConsumer.java` |
| 修改 | `resources/application.yml` |

---

### Task 1: 新增 VisitorSessionRegistry

**Files:**
- Create: `infrastructure/websocket/VisitorSessionRegistry.java`
- Test: `test/.../infrastructure/websocket/VisitorSessionRegistryTest.java`

**Interfaces:**
- Consumes: `WsPresenceRegistry`、`PodIdentity`、`ObjectMapper`
- Produces:
  - `register(String sessionId, WebSocketSession session)` — 注册连接，写 presence，启动心跳
  - `unregister(String sessionId, WebSocketSession session)` — 原子条件删除，防重连竞态
  - `notifyVisitor(String sessionId, Object payload)` — 实现 VisitorNotifier，本地推送
  - `closeVisitorSessionNormal(String sessionId)` — 实现 VisitorNotifier，正常关闭
  - `shutdown()` — @PreDestroy 关闭心跳调度器

- [ ] **Step 1: 写失败测试**

创建文件 `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/VisitorSessionRegistryTest.java`：

```java
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
    @DisplayName("unregister：本 session 是活跃连接时清理 presence")
    void unregister_removes_presence_when_active() {
        WebSocketSession session = openSession("ws-1");
        registry.register("sess-1", session);
        registry.unregister("sess-1", session);
        verify(presenceRegistry).unregisterVisitor("sess-1");
    }

    @Test
    @DisplayName("unregister：本 session 已被新连接替换时不清理 presence（防重连竞态）")
    void unregister_skips_presence_when_replaced() {
        WebSocketSession oldSession = openSession("ws-old");
        WebSocketSession newSession = openSession("ws-new");
        registry.register("sess-1", oldSession);
        registry.register("sess-1", newSession);
        registry.unregister("sess-1", oldSession);
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

创建文件 `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/VisitorSessionRegistry.java`：

```java
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
 * 访客 WebSocket 连接注册表，实现 {@link VisitorNotifier} 接口。
 *
 * <p>与 {@link AgentConnectionRegistry} 对称，是 infrastructure 层的访客 Session Repository。
 * 职责：管理访客单连接的注册/注销/推送，同步维护 Redis presence + 启停心跳。
 *
 * <p>将 session 管理从 {@link ChatWebSocketHandler} 中分离，消除
 * ChatWebSocketHandler ↔ WsMessageRouter 的 Spring Bean 循环依赖：
 * WsMessageRouter 注入本类（无循环），ChatWebSocketHandler 也注入本类（无循环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorSessionRegistry implements VisitorNotifier {

    private final ObjectMapper       objectMapper;
    private final WsPresenceRegistry presenceRegistry;
    private final PodIdentity        podIdentity;

    /** sessionId → 活跃 WS 连接（每个访客始终只有一个活跃连接） */
    private final ConcurrentHashMap<String, WebSocketSession> visitorSessions =
            new ConcurrentHashMap<>();

    /** per-session 发送锁：wsSession.getId() → lock，串行化 sendMessage */
    private final ConcurrentHashMap<String, Object> sendLocks =
            new ConcurrentHashMap<>();

    /** per-session 心跳任务：wsSession.getId() → ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats =
            new ConcurrentHashMap<>();

    /** 心跳调度器：内部初始化，不作为 @Bean 注入 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2);

    /**
     * 访客连接建立：注册 session、写 Redis presence、启动 30s 心跳。
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
     * 若访客已重连（新 session 已替换旧连接），旧连接关闭不影响新连接的 presence。
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
     * 用于座席主动结束会话时通知访客端显示"会话已结束"提示。
     */
    @Override
    public void closeVisitorSessionNormal(String sessionId) {
        WebSocketSession vs = visitorSessions.get(sessionId);
        if (vs != null && vs.isOpen()) {
            try {
                vs.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("[VisitorRegistry] closeNormal IO 异常 sessionId={} msg={}",
                        sessionId, e.getMessage());
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
     * Spring 容器停止时调用，确保 JVM 可以正常退出（非守护线程池须显式关闭）。
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

### Task 2: 重构 ChatWebSocketHandler + 更新 WsMessageRouter/WsDeliveryConsumer + 移除循环依赖配置

**Files:**
- Modify: `infrastructure/websocket/ChatWebSocketHandler.java`
- Modify: `infrastructure/websocket/cluster/WsMessageRouter.java`
- Modify: `infrastructure/websocket/cluster/WsDeliveryConsumer.java`
- Modify: `resources/application.yml`
- Update test: `test/.../ChatWebSocketHandlerPresenceTest.java`

**Interfaces:**
- Consumes: `VisitorSessionRegistry`（Task 1）

- [ ] **Step 1: 重构 ChatWebSocketHandler**

新的字段列表（`@RequiredArgsConstructor` 构造器顺序）：

```java
private final ObjectMapper objectMapper;
private final ConversationHistoryRepository historyRepository;
private final SessionQueueService sessionQueueService;
private final AgentConnectionRegistry agentConnectionRegistry;
private final VisitorSessionRegistry visitorSessionRegistry;
private final WsMessageRouter router;
```

删除以下字段：`visitorSessions`、`sendLocks`、`presenceRegistry`、`podIdentity`、`visitorHeartbeatScheduler`、`visitorHeartbeats`

删除以下方法：`sendJson()`、`closeStaleSession()`、`@PreDestroy shutdown()`

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
    sendLocks.remove(session.getId());  // 注意：sendLocks 这里指的是 WS session 级别的，
                                         // 实际上已经移到 VisitorSessionRegistry 内部，直接删除这行
    visitorSessionRegistry.unregister(sessionId, session);
    log.info("[WS] visitor disconnected sessionId={}", sessionId);
}
```

注意：`afterConnectionClosed` 中原有的 `sendLocks.remove(session.getId())` 也要删除，sendLocks 已移到 VisitorSessionRegistry 内部。

`handleTransportError` 访客路径改为：
```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessionRegistry.unregister(sessionId, session);
}
```

`notifyVisitor` 和 `closeVisitorSessionNormal` 委托：
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

删除 import：`ConcurrentHashMap`（如果只被 sendLocks/visitorSessions 使用）、`Executors`、`ScheduledExecutorService`、`ScheduledFuture`、`PreDestroy`、`PodIdentity`、`WsPresenceRegistry`、`TimeUnit`（如果只被心跳用）

- [ ] **Step 2: 更新 ChatWebSocketHandlerPresenceTest**

```java
// 字段改为注入 VisitorSessionRegistry
@Mock VisitorSessionRegistry visitorSessionRegistry;

// handler 构造器参数对应新字段顺序
handler = new ChatWebSocketHandler(
        new ObjectMapper(), historyRepository, sessionQueueService,
        agentConnectionRegistry, visitorSessionRegistry, null /* router */);

// 断言改为
verify(visitorSessionRegistry).register("sess-001", session);
```

- [ ] **Step 3: 修改 WsMessageRouter**

将 `ApplicationContext` 相关代码移除，改为直接注入 `VisitorSessionRegistry`：

```java
// 字段
private final PodIdentity             podIdentity;
private final WsPresenceRegistry      presenceRegistry;
private final AgentConnectionRegistry agentRegistry;
private final VisitorSessionRegistry  visitorSessionRegistry;
private final RabbitTemplate          rabbitTemplate;
private final ObjectMapper            objectMapper;
```

`sendToVisitor` 本地路径：
```java
if (podIdentity.isLocal(pod)) {
    visitorSessionRegistry.notifyVisitor(sessionId, payload);
}
```

移除 `ApplicationContext` import，移除 `VisitorNotifier` import。

- [ ] **Step 4: 修改 WsDeliveryConsumer**

```java
// 字段
private final AgentConnectionRegistry agentRegistry;
private final VisitorSessionRegistry  visitorSessionRegistry;
private final ObjectMapper            objectMapper;
```

`VISITOR` case：
```java
case VISITOR -> {
    Object payload = restorePayload(cmd);
    visitorSessionRegistry.notifyVisitor(cmd.targetId(), payload);
}
```

移除 `VisitorNotifier` import（同包 VisitorSessionRegistry 无需 import）。

- [ ] **Step 5: 移除 application.yml 中的 allow-circular-references**

删除：
```yaml
  main:
    allow-circular-references: true
    # 及其注释行
```

- [ ] **Step 6: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错（无循环依赖错误）

- [ ] **Step 7: 运行全量测试**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test -q 2>&1 | tail -15
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 8: 打包**

```bash
mvn -f ai-conversation/conversation-service/pom.xml package -DskipTests -q && echo "打包成功"
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(ws): 提取 VisitorSessionRegistry，消除循环依赖，ChatWebSocketHandler 纯化为协议适配器"
```
