# 后端 WebSocket 多会话统一连接架构改造 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将座席端 WebSocket 从「一会话一连接」改造为「一座席一连接多路复用」，新增 `AgentConnectionRegistry`、`AgentChannelWsHandler`，重构 `ChatWebSocketHandler`，支持 BROADCAST / KICK 双模式。

**Architecture:** 新增 `/ws/agent` 端点由 `AgentChannelWsHandler` 处理，消息通过 `AgentConnectionRegistry` 广播给座席所有在线连接；访客端 `/ws/chat/{sessionId}` 由重构后的 `ChatWebSocketHandler` 独立处理；消息路由通过 `SessionQueueService.getAgentId()` 查 Redis 完成。

**Tech Stack:** Java 21、Spring Boot WebSocket、Mockito 5（JUnit 5）、Redis（通过现有 `SessionQueueRepository`）

## Global Constraints

- 包根路径：`com.aria.conversation`
- 基础源码目录：`ai-conversation/conversation-service/src/main/java/com/aria/conversation/`
- 基础测试目录：`ai-conversation/conversation-service/src/test/java/com/aria/conversation/`
- 测试命令：`mvn -f ai-conversation/conversation-service/pom.xml test -Dtest=<ClassName>`
- 编译命令：`mvn -f ai-conversation/conversation-service/pom.xml compile -q`
- 所有新类注释密度与项目现有代码保持一致（类 Javadoc + 关键字段注释）
- 日志格式：`[前缀] 说明 key={}` 占位符风格，与现有代码一致
- `@RequiredArgsConstructor` + `final` 字段注入，禁用 `@Autowired`
- 不修改测试无关文件（`AGENTS.md`、SQL 文件等）

## 文件改动总览

| 操作 | 文件 | 所属任务 |
|------|------|---------|
| 新增 | `domain/MultiLoginMode.java` | Task 1 |
| 新增 | `infrastructure/websocket/VisitorNotifier.java` | Task 1 |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java`（添加 implements） | Task 1 |
| 修改 | `application/service/SessionQueueService.java`（新增 getAgentId） | Task 2 |
| 新增 | `infrastructure/websocket/AgentConnectionRegistry.java` | Task 3 |
| 新增 | `infrastructure/websocket/AgentChannelWsHandler.java` | Task 4 |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java`（重构 notifyAgent，删死代码） | Task 5 |
| 修改 | `infrastructure/config/WebSocketConfig.java` | Task 6 |
| 修改 | `resources/application.yml` | Task 6 |

---

### Task 1: MultiLoginMode 枚举 + VisitorNotifier 接口 + ChatWebSocketHandler 实现接口

**Files:**
- Create: `domain/MultiLoginMode.java`
- Create: `infrastructure/websocket/VisitorNotifier.java`
- Modify: `infrastructure/websocket/ChatWebSocketHandler.java`（implements VisitorNotifier）

**Interfaces:**
- Produces: `MultiLoginMode { BROADCAST, KICK }` — Task 4 使用
- Produces: `VisitorNotifier.notifyVisitor(String sessionId, Object payload)` / `closeVisitorSessionNormal(String sessionId)` — Task 4 使用

- [ ] **Step 1: 创建 MultiLoginMode 枚举**

```java
// domain/MultiLoginMode.java
package com.aria.conversation.domain;

/**
 * 座席多端登录策略。
 *
 * <p>通过 {@code agent.ws.multi-login-mode} 配置，由 Spring 自动将字符串值转换为枚举；
 * 配置值拼写错误时启动即失败，不会静默 fallback。
 */
public enum MultiLoginMode {
    /** 多端并发在线，所有连接均收到消息（默认） */
    BROADCAST,
    /** 新端登录时踢出旧端，推送 KICKED_OUT 后关闭旧连接 */
    KICK
}
```

- [ ] **Step 2: 创建 VisitorNotifier 接口**

```java
// infrastructure/websocket/VisitorNotifier.java
package com.aria.conversation.infrastructure.websocket;

/**
 * 访客消息推送接口。
 *
 * <p>解耦 {@link AgentChannelWsHandler} 对 {@link ChatWebSocketHandler} 的直接依赖，
 * 便于单元测试 Mock 和后续替换实现。
 */
public interface VisitorNotifier {

    /**
     * 向指定会话的访客推送消息。
     *
     * @param sessionId 会话 ID
     * @param payload   消息对象（将被序列化为 JSON）
     */
    void notifyVisitor(String sessionId, Object payload);

    /**
     * 以正常状态（code=1000 NORMAL）关闭访客端 WebSocket 连接。
     * 用于座席主动结束会话时通知访客。
     *
     * @param sessionId 会话 ID
     */
    void closeVisitorSessionNormal(String sessionId);
}
```

- [ ] **Step 3: 让 ChatWebSocketHandler 实现 VisitorNotifier**

找到 `ChatWebSocketHandler.java` 类声明行：
```java
public class ChatWebSocketHandler extends TextWebSocketHandler {
```
改为：
```java
public class ChatWebSocketHandler extends TextWebSocketHandler implements VisitorNotifier {
```

`notifyVisitor` 和 `closeVisitorSessionNormal` 方法已存在，加上 `@Override` 注解即可：
```java
@Override
public void notifyVisitor(String sessionId, Object payload) { ... }

@Override
public void closeVisitorSessionNormal(String sessionId) { ... }
```

- [ ] **Step 4: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/MultiLoginMode.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/VisitorNotifier.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java
git commit -m "feat(ws): 新增 MultiLoginMode 枚举和 VisitorNotifier 接口"
```

---

### Task 2: SessionQueueService 新增 getAgentId()

**Files:**
- Modify: `application/service/SessionQueueService.java`
- Test: `test/.../application/service/SessionQueueServiceGetAgentIdTest.java`

**Interfaces:**
- Consumes: `SessionQueueRepository.findById(String sessionId)` → `Optional<SessionQueueItem>`
- Produces: `SessionQueueService.getAgentId(String sessionId)` → `String | null` — Task 5 使用

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/conversation/application/service/SessionQueueServiceGetAgentIdTest.java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionQueueService.getAgentId()")
class SessionQueueServiceGetAgentIdTest {

    @Mock SessionQueueRepository queueRepository;

    // SessionQueueService 依赖较多，使用反射注入最小化 Mock
    private SessionQueueService service;

    @BeforeEach
    void setUp() throws Exception {
        // 只注入 queueRepository，其余依赖传 null（getAgentId 只用 queueRepository）
        service = new SessionQueueService(
                queueRepository, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("ACTIVE 会话返回 agentId")
    void active_session_returns_agentId() {
        SessionQueueItem item = new SessionQueueItem(
                "sess-001", "Alice", "咨询", "产品", 0L, SessionStatus.ACTIVE, "agent-001");
        when(queueRepository.findById("sess-001")).thenReturn(Optional.of(item));

        assertThat(service.getAgentId("sess-001")).isEqualTo("agent-001");
    }

    @Test
    @DisplayName("WAITING 会话（agentId 为 null）返回 null")
    void waiting_session_returns_null() {
        SessionQueueItem item = new SessionQueueItem(
                "sess-002", "Bob", "咨询", "产品", 0L, SessionStatus.WAITING, null);
        when(queueRepository.findById("sess-002")).thenReturn(Optional.of(item));

        assertThat(service.getAgentId("sess-002")).isNull();
    }

    @Test
    @DisplayName("会话不存在返回 null")
    void missing_session_returns_null() {
        when(queueRepository.findById("sess-999")).thenReturn(Optional.empty());

        assertThat(service.getAgentId("sess-999")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=SessionQueueServiceGetAgentIdTest -q 2>&1 | tail -20
```
期望：编译失败或 `getAgentId` 方法不存在

- [ ] **Step 3: 在 SessionQueueService 中新增方法**

在 `SessionQueueService.java` 中找到 `registerAgent` 方法前，插入：

```java
/**
 * 查询会话当前负责的座席 ID。
 *
 * <p>用于 WS 消息路由：{@code ChatWebSocketHandler.notifyAgent} 通过此方法
 * 将 sessionId 转换为 agentId，再交由 {@code AgentConnectionRegistry} 广播。
 *
 * @param sessionId 会话 ID
 * @return 负责此会话的座席 ID；会话处于 WAITING 状态或不存在时返回 {@code null}
 */
public String getAgentId(String sessionId) {
    return queueRepository.findById(sessionId)
            .map(SessionQueueItem::agentId)
            .orElse(null);
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=SessionQueueServiceGetAgentIdTest -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，3 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceGetAgentIdTest.java
git commit -m "feat(ws): SessionQueueService 新增 getAgentId() 方法"
```

---

### Task 3: AgentConnectionRegistry

**Files:**
- Create: `infrastructure/websocket/AgentConnectionRegistry.java`
- Test: `test/.../infrastructure/websocket/AgentConnectionRegistryTest.java`

**Interfaces:**
- Produces:
  - `register(String agentId, WebSocketSession session)`
  - `unregister(WebSocketSession session)`
  - `broadcast(String agentId, Object payload)`
  - `broadcastExcept(String agentId, WebSocketSession exclude, Object payload)`
  - `closeAllExcept(String agentId, WebSocketSession keep)`
  - `getAgentLock(String agentId)` → `Object`（供 KICK 模式加锁）

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistryTest.java
package com.aria.conversation.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AgentConnectionRegistry")
class AgentConnectionRegistryTest {

    private AgentConnectionRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new AgentConnectionRegistry(objectMapper);
    }

    private WebSocketSession openSession(String id) throws IOException {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("register 后 broadcast 能发送消息")
    void broadcast_sends_to_registered_session() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register("agent-1", session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("unregister 后 broadcast 不再发送")
    void unregister_stops_broadcast() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register("agent-1", session);
        registry.unregister(session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("broadcastExcept 排除指定 session")
    void broadcastExcept_skips_excluded_session() throws IOException {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);

        registry.broadcastExcept("agent-1", s2, java.util.Map.of("type", "KICKED_OUT"));

        verify(s1, times(1)).sendMessage(any(TextMessage.class));
        verify(s2, never()).sendMessage(any());
    }

    @Test
    @DisplayName("closeAllExcept 关闭除 keep 以外的连接")
    void closeAllExcept_closes_old_sessions() throws IOException {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");  // keep
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);

        registry.closeAllExcept("agent-1", s2);

        verify(s1, times(1)).close(any());
        verify(s2, never()).close(any());
    }

    @Test
    @DisplayName("同一 agentId 的锁对象每次返回相同实例")
    void getAgentLock_returns_same_instance() {
        Object lock1 = registry.getAgentLock("agent-1");
        Object lock2 = registry.getAgentLock("agent-1");
        assertThat(lock1).isSameAs(lock2);
    }

    @Test
    @DisplayName("broadcast 时 session 已关闭则跳过")
    void broadcast_skips_closed_session() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(false);
        registry.register("agent-1", session);

        registry.broadcast("agent-1", java.util.Map.of("type", "MESSAGE"));

        verify(session, never()).sendMessage(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentConnectionRegistryTest -q 2>&1 | tail -10
```
期望：编译失败，`AgentConnectionRegistry` 类不存在

- [ ] **Step 3: 实现 AgentConnectionRegistry**

```java
// infrastructure/websocket/AgentConnectionRegistry.java
package com.aria.conversation.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 座席 WebSocket 连接注册表。
 *
 * <p>职责：管理座席多端连接的注册/注销/推送，不感知业务逻辑（KICK/BROADCAST 由调用方决策）。
 *
 * <p>数据结构：
 * <ul>
 *   <li>{@code agentToSessions}：正向索引，agentId → 所有在线 WS 连接集合</li>
 *   <li>{@code sessionIdToAgentId}：反向索引，wsSession.getId() → agentId，供 unregister O(1) 清理</li>
 *   <li>{@code sendLocks}：per-session 发送锁，串行化 sendMessage 调用，防止并发帧损坏</li>
 *   <li>{@code agentLocks}：per-agentId 粗粒度锁，供 KICK 模式原子化 register+kick+close</li>
 * </ul>
 *
 * <p>⚠️ {@code agentToSessions} value 类型必须保持 {@link CopyOnWriteArraySet}（有序插入），
 * {@code broadcast} 遍历时按插入顺序加锁，替换为无序集合会引入死锁风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConnectionRegistry {

    private final ObjectMapper objectMapper;

    /** 正向索引：agentId → 该座席所有在线 WS 连接 */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSession>> agentToSessions
            = new ConcurrentHashMap<>();

    /** 反向索引：wsSession.getId() → agentId */
    private final ConcurrentHashMap<String, String> sessionIdToAgentId
            = new ConcurrentHashMap<>();

    /** per-session 发送锁：wsSession.getId() → lock */
    private final ConcurrentHashMap<String, Object> sendLocks
            = new ConcurrentHashMap<>();

    /** per-agentId 粗粒度锁，供 KICK 模式三步原子化使用 */
    private final ConcurrentHashMap<String, Object> agentLocks
            = new ConcurrentHashMap<>();

    /**
     * 注册新连接。
     *
     * @param agentId 座席 ID（由 AgentHandshakeInterceptor 写入 session attributes）
     * @param session WS 连接
     */
    public void register(String agentId, WebSocketSession session) {
        agentToSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionIdToAgentId.put(session.getId(), agentId);
        log.debug("[AgentRegistry] 注册连接 agentId={} wsId={}", agentId, session.getId());
    }

    /**
     * 注销连接。通过反向索引查找 agentId，调用方无需传入。
     * 连接关闭（afterConnectionClosed）和 transport error 时均调用此方法。
     *
     * @param session 待注销的 WS 连接
     */
    public void unregister(WebSocketSession session) {
        String agentId = sessionIdToAgentId.remove(session.getId());
        sendLocks.remove(session.getId());
        if (agentId == null) {
            return;
        }
        // 原子删除：空 Set 时同步移除 key，避免 TOCTOU 竞态
        agentToSessions.computeIfPresent(agentId, (k, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
        log.debug("[AgentRegistry] 注销连接 agentId={} wsId={}", agentId, session.getId());
    }

    /**
     * 向该座席所有在线连接广播消息。
     *
     * @param agentId 座席 ID
     * @param payload 消息对象（序列化为 JSON）
     */
    public void broadcast(String agentId, Object payload) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("[AgentRegistry] broadcast 跳过：agentId={} 无在线连接", agentId);
            return;
        }
        for (WebSocketSession session : sessions) {
            sendJson(session, payload);
        }
    }

    /**
     * 向该座席除 exclude 之外的所有连接广播消息。
     * 用于 KICK 模式向旧端推送 KICKED_OUT 信令。
     *
     * @param agentId 座席 ID
     * @param exclude 排除的连接（通常为新登录的连接）
     * @param payload 消息对象
     */
    public void broadcastExcept(String agentId, WebSocketSession exclude, Object payload) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(exclude.getId())) {
                sendJson(session, payload);
            }
        }
    }

    /**
     * 关闭该座席除 keep 之外的所有连接。
     * 用于 KICK 模式踢出旧连接，调用前应先 broadcastExcept 推送 KICKED_OUT。
     *
     * @param agentId 座席 ID
     * @param keep    保留的连接（新登录连接）
     */
    public void closeAllExcept(String agentId, WebSocketSession keep) {
        Set<WebSocketSession> sessions = agentToSessions.get(agentId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(keep.getId()) && session.isOpen()) {
                try {
                    session.close(CloseStatus.GOING_AWAY);
                } catch (IOException e) {
                    log.warn("[AgentRegistry] 关闭旧连接失败 wsId={} msg={}", session.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * 获取指定座席的 per-agentId 粗粒度锁。
     * KICK 模式下用于将 register+broadcastExcept+closeAllExcept 三步原子化。
     *
     * @param agentId 座席 ID
     * @return 锁对象（同一 agentId 始终返回同一实例）
     */
    public Object getAgentLock(String agentId) {
        return agentLocks.computeIfAbsent(agentId, k -> new Object());
    }

    /**
     * 向指定 WS 连接发送 JSON 消息。
     * 通过 per-session 锁串行化写帧，防止并发帧损坏。
     * 发送失败时主动关闭连接，触发 afterConnectionClosed 自动清理。
     */
    private void sendJson(WebSocketSession session, Object payload) {
        Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            if (!session.isOpen()) {
                return;
            }
            try {
                String json = objectMapper.writeValueAsString(payload);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("[AgentRegistry] 发送失败 wsId={} msg={}", session.getId(), e.getMessage());
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentConnectionRegistryTest -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，7 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistry.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistryTest.java
git commit -m "feat(ws): 新增 AgentConnectionRegistry"
```

---

### Task 4: AgentChannelWsHandler

**Files:**
- Create: `infrastructure/websocket/AgentChannelWsHandler.java`
- Test: `test/.../infrastructure/websocket/AgentChannelWsHandlerTest.java`

**Interfaces:**
- Consumes:
  - `AgentConnectionRegistry.register/unregister/broadcast/broadcastExcept/closeAllExcept/getAgentLock`
  - `VisitorNotifier.notifyVisitor(String sessionId, Object payload)`
  - `ConversationHistoryRepository.appendAgentMessage(String sessionId, String content)` → `long seq`
  - `MultiLoginMode { BROADCAST, KICK }`
- Produces: 注册到 `/ws/agent`，处理座席连接全生命周期

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/conversation/infrastructure/websocket/AgentChannelWsHandlerTest.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChannelWsHandler")
class AgentChannelWsHandlerTest {

    @Mock AgentConnectionRegistry registry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock ConversationHistoryRepository historyRepository;

    private AgentChannelWsHandler handler;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        handler = new AgentChannelWsHandler(registry, visitorNotifier, historyRepository, objectMapper);
        // 设置默认模式为 BROADCAST
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.BROADCAST);
    }

    private WebSocketSession sessionWithAgentId(String wsId, String agentId) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(wsId);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("agentId", agentId);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("BROADCAST 模式：连接建立时 register 并推送 CONNECTED")
    void broadcast_mode_registers_and_sends_connected() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-1", "agent-1");

        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-1", session);
        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("CONNECTED");
        }));
        // BROADCAST 模式不调用 broadcastExcept 和 closeAllExcept
        verify(registry, never()).broadcastExcept(any(), any(), any());
        verify(registry, never()).closeAllExcept(any(), any());
    }

    @Test
    @DisplayName("KICK 模式：连接建立时踢出旧端")
    void kick_mode_kicks_old_sessions() throws Exception {
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.KICK);

        WebSocketSession session = sessionWithAgentId("ws-2", "agent-2");
        when(registry.getAgentLock("agent-2")).thenReturn(new Object());

        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-2", session);
        verify(registry).broadcastExcept(eq("agent-2"), eq(session), argThat(p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) p;
            return "KICKED_OUT".equals(map.get("type"));
        }));
        verify(registry).closeAllExcept("agent-2", session);
    }

    @Test
    @DisplayName("MESSAGE 类型消息：写历史并转发给访客")
    void message_type_stores_history_and_notifies_visitor() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-3", "agent-3");
        when(historyRepository.appendAgentMessage("sess-1", "你好")).thenReturn(1L);

        String json = objectMapper.writeValueAsString(
                Map.of("type", "MESSAGE", "sessionId", "sess-1", "content", "你好"));
        handler.handleTextMessage(session, new TextMessage(json));

        verify(historyRepository).appendAgentMessage("sess-1", "你好");
        verify(visitorNotifier).notifyVisitor(eq("sess-1"), any());
    }

    @Test
    @DisplayName("TYPING 类型消息：直接转发给访客，不写历史")
    void typing_type_notifies_visitor_without_history() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-4", "agent-4");

        String json = objectMapper.writeValueAsString(
                Map.of("type", "TYPING", "sessionId", "sess-2", "timestamp", 1000L));
        handler.handleTextMessage(session, new TextMessage(json));

        verify(visitorNotifier).notifyVisitor(eq("sess-2"), any());
        verify(historyRepository, never()).appendAgentMessage(any(), any());
    }

    @Test
    @DisplayName("连接关闭时调用 unregister")
    void connection_closed_calls_unregister() throws Exception {
        WebSocketSession session = sessionWithAgentId("ws-5", "agent-5");
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(registry).unregister(session);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentChannelWsHandlerTest -q 2>&1 | tail -10
```
期望：编译失败，`AgentChannelWsHandler` 类不存在

- [ ] **Step 3: 实现 AgentChannelWsHandler**

```java
// infrastructure/websocket/AgentChannelWsHandler.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * 座席专用 WebSocket Handler。
 *
 * <p>注册到端点 {@code /ws/agent}，负责：
 * <ul>
 *   <li>连接建立/关闭时维护 {@link AgentConnectionRegistry}</li>
 *   <li>按 {@code agent.ws.multi-login-mode} 执行 BROADCAST 或 KICK 逻辑</li>
 *   <li>处理座席发送的消息（MESSAGE 写历史转发访客，TYPING 直接转发）</li>
 *   <li>向新连接推送 CONNECTED 确认信令</li>
 * </ul>
 *
 * <p>握手鉴权由 {@link AgentHandshakeInterceptor} 完成，token 无效时返回 HTTP 401，
 * 本 Handler 不会被调用。agentId 由 Interceptor 写入 {@code session.attributes["agentId"]}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChannelWsHandler extends TextWebSocketHandler {

    private static final String ATTR_AGENT_ID  = "agentId";
    private static final String MSG_TYPE_CONNECTED  = "CONNECTED";
    private static final String MSG_TYPE_KICKED_OUT = "KICKED_OUT";
    private static final String MSG_TYPE_MESSAGE    = "MESSAGE";
    private static final String MSG_TYPE_TYPING     = "TYPING";

    /** 单条消息最大字节数（64KB），与 ChatWebSocketHandler 保持一致 */
    private static final int MAX_MESSAGE_BYTES = 65536;

    private final AgentConnectionRegistry registry;
    private final VisitorNotifier visitorNotifier;
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Value("${agent.ws.multi-login-mode:BROADCAST}")
    private MultiLoginMode multiLoginMode;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String agentId = (String) session.getAttributes().get(ATTR_AGENT_ID);
        if (agentId == null) {
            log.warn("[AgentWS] agentId 缺失，关闭连接 wsId={}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (MultiLoginMode.KICK == multiLoginMode) {
            // KICK 模式：per-agentId 锁原子化三步，防止并发双踢竞态
            synchronized (registry.getAgentLock(agentId)) {
                registry.register(agentId, session);
                registry.broadcastExcept(agentId, session, Map.of("type", MSG_TYPE_KICKED_OUT));
                registry.closeAllExcept(agentId, session);
            }
        } else {
            // BROADCAST 模式：直接注册，无锁
            registry.register(agentId, session);
        }

        // 通知新端连接成功（锁外推送）
        sendJson(session, Map.of("type", MSG_TYPE_CONNECTED));
        log.info("[AgentWS] 座席连接建立 agentId={} wsId={} mode={}", agentId, session.getId(), multiLoginMode);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            log.warn("[AgentWS] 消息超过最大长度 wsId={} size={}", session.getId(), message.getPayloadLength());
            sendJson(session, Map.of("type", "ERROR", "message", "消息长度超过限制（最大 64KB）"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        Map<String, Object> body = parseBody(message.getPayload(), session.getId());
        String type      = (String) body.getOrDefault("type", MSG_TYPE_MESSAGE);
        String sessionId = (String) body.get("sessionId");
        String content   = (String) body.get("content");
        long   ts        = Instant.now().getEpochSecond();

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[AgentWS] 消息缺少 sessionId wsId={}", session.getId());
            return;
        }

        if (MSG_TYPE_TYPING.equals(type)) {
            // TYPING 信号：ephemeral，不写历史，直接转发给访客
            visitorNotifier.notifyVisitor(sessionId,
                    Map.of("type", MSG_TYPE_TYPING, "sessionId", sessionId, "timestamp", ts));
            return;
        }

        // MESSAGE：写历史，转发给访客
        if (content == null || content.isBlank()) {
            log.warn("[AgentWS] MESSAGE 内容为空 sessionId={} wsId={}", sessionId, session.getId());
            return;
        }
        long seq = historyRepository.appendAgentMessage(sessionId, content);
        visitorNotifier.notifyVisitor(sessionId, Map.of(
                "type", MSG_TYPE_MESSAGE,
                "sessionId", sessionId,
                "role", MessageRole.AGENT.getValue(),
                "content", content,
                "seq", seq,
                "timestamp", ts));
        log.debug("[AgentWS] agent→visitor sessionId={} seq={}", sessionId, seq);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        log.info("[AgentWS] 座席连接关闭 agentId={} wsId={} status={}",
                session.getAttributes().get(ATTR_AGENT_ID), session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("[AgentWS] 传输异常 agentId={} wsId={} msg={}",
                session.getAttributes().get(ATTR_AGENT_ID), session.getId(), ex.getMessage());
        registry.unregister(session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String payload, String wsId) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("[AgentWS] payload 非 JSON wsId={}", wsId);
            return Map.of("content", payload);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("[AgentWS] 发送失败 wsId={} msg={}", session.getId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentChannelWsHandlerTest -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，6 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/AgentChannelWsHandler.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/AgentChannelWsHandlerTest.java
git commit -m "feat(ws): 新增 AgentChannelWsHandler"
```

---

### Task 5: ChatWebSocketHandler 重构（移除死代码 + 改造 notifyAgent）

**Files:**
- Modify: `infrastructure/websocket/ChatWebSocketHandler.java`
- Test: `test/.../infrastructure/websocket/ChatWebSocketHandlerNotifyAgentTest.java`

**Interfaces:**
- Consumes:
  - `SessionQueueService.getAgentId(String sessionId)` → `String | null`（Task 2）
  - `AgentConnectionRegistry.broadcast(String agentId, Object payload)`（Task 3）
- Produces: `notifyAgent(String sessionId, Object payload)` 改造完成，TYPING 直接跳过，其余查 Redis 广播

- [ ] **Step 1: 读取现有 ChatWebSocketHandler 确认改动范围**

阅读完整文件，确认以下待删除/修改位置：
- 字段 `agentSessions`（约第 76 行）
- `VALID_ROLES` 中 `"agent"` 条目（约第 68 行）
- `afterConnectionEstablished` 中 `else { agentSessions.put ... }` 分支
- `afterConnectionClosed` 中 `else { agentSessions.remove ... }` 分支
- `handleTransportError` 中 `else { agentSessions.remove ... }` 分支
- `handleTextMessage` 中 `else { handleAgentMessage(...) }` 分支
- `handleAgentMessage` 整个方法
- `notifyAgent` 方法实现（保留方法签名，替换内部逻辑）

- [ ] **Step 2: 写失败测试（验证 notifyAgent 新行为）**

```java
// test/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandlerNotifyAgentTest.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler.notifyAgent 改造后行为")
class ChatWebSocketHandlerNotifyAgentTest {

    @Mock SessionQueueService sessionQueueService;
    @Mock AgentConnectionRegistry agentConnectionRegistry;
    @Mock ConversationHistoryRepository historyRepository;

    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatWebSocketHandler(historyRepository, new ObjectMapper(),
                sessionQueueService, agentConnectionRegistry);
    }

    @Test
    @DisplayName("TYPING 消息直接跳过，不查 Redis")
    void typing_skips_redis_lookup() {
        handler.notifyAgent("sess-1", Map.of("type", "TYPING", "sessionId", "sess-1"));

        verifyNoInteractions(sessionQueueService);
        verifyNoInteractions(agentConnectionRegistry);
    }

    @Test
    @DisplayName("有 agentId 时广播消息")
    void with_agentId_broadcasts_message() {
        when(sessionQueueService.getAgentId("sess-2")).thenReturn("agent-001");

        handler.notifyAgent("sess-2", Map.of("type", "MESSAGE", "content", "hello"));

        verify(agentConnectionRegistry).broadcast(eq("agent-001"), any());
    }

    @Test
    @DisplayName("agentId 为 null 时跳过广播（WAITING 状态）")
    void null_agentId_skips_broadcast() {
        when(sessionQueueService.getAgentId("sess-3")).thenReturn(null);

        handler.notifyAgent("sess-3", Map.of("type", "MESSAGE", "content", "hello"));

        verifyNoInteractions(agentConnectionRegistry);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=ChatWebSocketHandlerNotifyAgentTest -q 2>&1 | tail -10
```
期望：编译失败（ChatWebSocketHandler 构造器参数不匹配）

- [ ] **Step 4: 修改 ChatWebSocketHandler**

**4a. 新增字段**（在现有字段声明区域末尾添加）：

```java
private final SessionQueueService sessionQueueService;
private final AgentConnectionRegistry agentConnectionRegistry;
```

**4b. 简化 VALID_ROLES**（移除 "agent"）：

```java
// 改前
private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("chat", "agent");
// 改后
private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("chat");
```

**4c. 删除 agentSessions 字段**（整行删除）：

```java
// 删除此行：
private final ConcurrentHashMap<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
```

**4d. 修改 afterConnectionEstablished**：删除 else 分支（`agentSessions.put` 和 `notifyVisitor(AGENT_JOINED)` 相关代码），只保留访客分支逻辑。

**4e. 修改 afterConnectionClosed**：删除 else 分支 `agentSessions.remove(sessionId)`，只保留：

```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessions.remove(sessionId);
    log.info("[WS] visitor disconnected sessionId={}", sessionId);
}
```

**4f. 修改 handleTransportError**：同上，删除 else 分支，只保留访客分支。

**4g. 修改 handleTextMessage 中的 role 判断**：删除 `else { handleAgentMessage(...) }` 分支。

**4h. 删除 handleAgentMessage 整个方法**（约第 225-237 行）。

**4i. 替换 notifyAgent 实现**：

```java
/**
 * 通知座席。通过应用层查询 agentId，再由 {@link AgentConnectionRegistry} 广播。
 *
 * <p>TYPING 信号为 ephemeral，允许丢失，在入口直接跳过，不走 Redis 查询。
 *
 * @param sessionId 会话 ID
 * @param payload   消息对象
 */
public void notifyAgent(String sessionId, Object payload) {
    // TYPING 是 ephemeral 信号，允许丢失，跳过 Redis 路由
    if (payload instanceof Map<?, ?> m && MSG_TYPE_TYPING.equals(m.get("type"))) {
        log.debug("[WS] notifyAgent 跳过 TYPING sessionId={}", sessionId);
        return;
    }

    String agentId = sessionQueueService.getAgentId(sessionId);
    if (agentId == null) {
        log.warn("[WS] notifyAgent 跳过：sessionId={} 尚未分配座席或会话已关闭", sessionId);
        return;
    }
    agentConnectionRegistry.broadcast(agentId, payload);
}
```

**4j. 在 notifyVisitor 和 closeVisitorSessionNormal 上加 @Override**（Task 1 要求）：

```java
@Override
public void notifyVisitor(String sessionId, Object payload) { ... }

@Override
public void closeVisitorSessionNormal(String sessionId) { ... }
```

- [ ] **Step 5: 运行新旧测试全部通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="ChatWebSocketHandlerNotifyAgentTest,ChatWebSocketHandlerSessionIdTest" -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 6: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandlerNotifyAgentTest.java
git commit -m "refactor(ws): ChatWebSocketHandler 移除 agent 分支死代码，改造 notifyAgent 使用 Registry 广播"
```

---

### Task 6: WebSocketConfig + application.yml

**Files:**
- Modify: `infrastructure/config/WebSocketConfig.java`
- Modify: `resources/application.yml`

**Interfaces:**
- Consumes: `AgentChannelWsHandler`（Task 4）注入 Spring 容器
- Produces: `/ws/agent` 端点上线，`/ws/agent/*` 旧端点下线

- [ ] **Step 1: 修改 WebSocketConfig.java**

找到注册 `/ws/agent/*` 的代码块（注入 `chatWebSocketHandler` 和 `agentHandshakeInterceptor` 的那一段），替换为：

```java
// 改前（删除）：
registry.addHandler(chatWebSocketHandler, "/ws/agent/*")
        .addInterceptors(agentHandshakeInterceptor)
        .setAllowedOrigins(agentOrigins);

// 改后（新增）：
registry.addHandler(agentChannelWsHandler, "/ws/agent")
        .addInterceptors(agentHandshakeInterceptor)
        .setAllowedOrigins(agentOrigins);
```

在 `WebSocketConfig` 类中新增字段注入（`@RequiredArgsConstructor` 或构造器）：

```java
private final AgentChannelWsHandler agentChannelWsHandler;
```

同时移除对 `chatWebSocketHandler` 用于座席端点注册的引用（访客端 `/ws/chat/*` 那一行保留）。

- [ ] **Step 2: 修改 application.yml**

在 `app:` 配置块之外（或合适位置）新增：

```yaml
agent:
  ws:
    multi-login-mode: BROADCAST   # BROADCAST（默认）| KICK
```

- [ ] **Step 3: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/WebSocketConfig.java \
        ai-conversation/conversation-service/src/main/resources/application.yml
git commit -m "feat(ws): 注册新座席端点 /ws/agent，移除旧端点 /ws/agent/*"
```

---

### Task 7: 全量测试验证 + 收尾

**Files:**
- No new files — 验证阶段

**Interfaces:**
- Consumes: Task 1–6 全部产出

- [ ] **Step 1: 运行全部相关测试**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="AgentConnectionRegistryTest,AgentChannelWsHandlerTest,ChatWebSocketHandlerNotifyAgentTest,ChatWebSocketHandlerSessionIdTest,SessionQueueServiceGetAgentIdTest" \
    2>&1 | tail -20
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 2: 运行全模块测试（确认无回归）**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test -q 2>&1 | tail -20
```
期望：`BUILD SUCCESS`，无 FAILED 或 ERROR

- [ ] **Step 3: 检查死代码清除完整性**

```bash
grep -rn "agentSessions\|handleAgentMessage" \
    ai-conversation/conversation-service/src/main/java/ 2>/dev/null
```
期望：无任何输出（死代码已全部清除）

```bash
grep -rn "ChatWebSocketHandler" \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ \
    | grep -v "ChatWebSocketHandler.java"
```
期望：无输出（无其他文件直接引用 ChatWebSocketHandler，已通过 VisitorNotifier 接口解耦）

- [ ] **Step 4: 验证新端点注册**

```bash
grep -n "ws/agent" \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/WebSocketConfig.java
```
期望：只出现 `/ws/agent`（无路径参数），不出现 `/ws/agent/*`

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "test(ws): 全量测试验证通过，WebSocket 多会话统一连接架构改造完成"
```

---

## 自检清单

> 实现前执行者自查，不需要再次派发评审 agent

| 检查项 | 对应任务 |
|-------|---------|
| `MultiLoginMode` 枚举在 `domain` 层 | Task 1 |
| `VisitorNotifier` 接口在 `infrastructure/websocket` 层 | Task 1 |
| `ChatWebSocketHandler implements VisitorNotifier`，两个方法有 `@Override` | Task 1 |
| `SessionQueueService.getAgentId()` 方法存在，3 个单测通过 | Task 2 |
| `AgentConnectionRegistry` 6 个方法齐全，7 个单测通过 | Task 3 |
| `AgentChannelWsHandler` 使用 `VisitorNotifier` 接口（非 ChatWebSocketHandler）| Task 4 |
| KICK 模式三步在 `synchronized (registry.getAgentLock(agentId))` 内 | Task 4 |
| `ChatWebSocketHandler.notifyAgent` 中 TYPING 在入口直接 return | Task 5 |
| `agentSessions` 字段和 `handleAgentMessage` 方法已删除 | Task 5 |
| `/ws/agent/*` 旧端点已删除，`/ws/agent` 新端点已注册 | Task 6 |
| `application.yml` 有 `agent.ws.multi-login-mode: BROADCAST` 配置 | Task 6 |
| 全模块测试无回归 | Task 7 |
