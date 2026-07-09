# WebSocket 集群改造 阶段 2-4：多 Pod Presence 与跨 Pod 路由 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 conversation-service 构建完整的 WS 多 Pod 路由能力：presence 注册表（Redis）、跨 Pod 消息投递（RabbitMQ Direct）、KICK 模式分布式锁（Redisson），使访客和座席 WS 连接可落在任意 Pod 并正确互通。

**Architecture:** 每个 Pod 启动时通过 `PodIdentity` 获取唯一标识（RabbitMQ AnonymousQueue 名称）。连接建立时写入 Redis presence（`WsPresenceRegistry`）。消息推送时 `WsMessageRouter` 查询 presence 决定本地直推或通过 `ws.delivery` Direct Exchange 跨 Pod 投递，目标 Pod 的 `WsDeliveryConsumer` 接收后本地推送。KICK 模式改用 Redisson `RLock` 保证多 Pod 下单端登录语义。

**Tech Stack:** Java 21、Spring Boot WebSocket、Redisson 3.27.2、RabbitMQ、Redis（RedisCacheHelper）

## Global Constraints

- 模块：`ai-conversation/conversation-service`
- 基础源码目录：`ai-conversation/conversation-service/src/main/java/com/aria/conversation/`
- 基础测试目录：`ai-conversation/conversation-service/src/test/java/com/aria/conversation/`
- 编译命令：`mvn -f ai-conversation/conversation-service/pom.xml compile -q`
- 测试命令：`mvn -f ai-conversation/conversation-service/pom.xml test -Dtest=<ClassName> -q`
- Redisson 版本：`3.27.2`（精确版本）
- 新建文件统一放入 `infrastructure/websocket/cluster/` 子包（完整包名 `com.aria.conversation.infrastructure.websocket.cluster`）
- WS presence Redis Key 前缀常量统一追加到 `infrastructure/cache/ConversationCacheKeys.java`
- RabbitMQ 常量、Redis 锁 Key 前缀放入 `infrastructure/websocket/cluster/WsClusterConstants.java`
- `@RequiredArgsConstructor` + `final` 字段注入，禁用 `@Autowired`
- 日志格式：`[前缀] 说明 key={}` 占位符风格
- 所有 public 类和方法必须有 Javadoc
- 测试命令全量：`mvn -f ai-conversation/conversation-service/pom.xml test -q`

## 文件改动总览

| 操作 | 文件 | 所属任务 |
|------|------|---------|
| 修改 | `pom.xml` | Task 1 |
| 修改 | `infrastructure/cache/ConversationCacheKeys.java` | Task 1 |
| 新增 | `infrastructure/websocket/cluster/WsClusterConstants.java` | Task 1 |
| 新增 | `infrastructure/websocket/cluster/PodIdentity.java` | Task 2 |
| 新增 | `infrastructure/websocket/cluster/WsPresenceRegistry.java` | Task 3 |
| 修改 | `infrastructure/websocket/AgentConnectionRegistry.java` | Task 4 |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java` | Task 5 |
| 新增 | `infrastructure/websocket/cluster/WsDeliveryCommand.java` | Task 6 |
| 新增 | `infrastructure/websocket/cluster/WsMessageRouter.java` | Task 7 |
| 新增 | `infrastructure/websocket/cluster/WsDeliveryConsumer.java` | Task 7 |
| 修改 | `infrastructure/websocket/AgentChannelWsHandler.java` | Task 8 |
| 修改 | `infrastructure/config/RabbitMQConfig.java`（仅注释确认无需改动） | Task 7 |

---

### Task 1: pom.xml + WsClusterConstants + ConversationCacheKeys

**Files:**
- Modify: `ai-conversation/conversation-service/pom.xml`
- Modify: `infrastructure/cache/ConversationCacheKeys.java`
- Create: `infrastructure/websocket/cluster/WsClusterConstants.java`

**Interfaces:**
- Produces: `RedissonClient` Bean 可注入；`WsClusterConstants` 常量；`ConversationCacheKeys.WS_VISITOR_POD_PREFIX` / `WS_AGENT_PODS_PREFIX`

- [ ] **Step 1: 在 pom.xml 中添加 Redisson 依赖**

在 `<dependencies>` 中添加（放在 spring-retry 依赖之后）：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

- [ ] **Step 2: 追加 WS presence Key 前缀到 ConversationCacheKeys.java**

在类末尾 `}` 前插入：

```java
/** WS 访客 presence key 前缀，格式：{@code ws:visitor:pod:{sessionId}} */
public static final String WS_VISITOR_POD_PREFIX = "ws:visitor:pod:";

/** WS 座席 presence key 前缀，格式：{@code ws:agent:pods:{agentId}} */
public static final String WS_AGENT_PODS_PREFIX = "ws:agent:pods:";
```

- [ ] **Step 3: 创建 WsClusterConstants.java**

```java
// infrastructure/websocket/cluster/WsClusterConstants.java
package com.aria.conversation.infrastructure.websocket.cluster;

/**
 * WS 集群相关魔法字符串常量。
 *
 * <p>集中定义 RabbitMQ Exchange 名称和 Redis 锁 key 前缀，
 * 避免散落在多个类中重复硬编码。
 * WS presence Redis key 前缀见 {@link com.aria.conversation.infrastructure.cache.ConversationCacheKeys}。
 */
public final class WsClusterConstants {

    private WsClusterConstants() {}

    /** WS 跨 Pod 投递 Direct Exchange 名称 */
    public static final String WS_DELIVERY_EXCHANGE = "ws.delivery";

    /** KICK 模式分布式锁 key 前缀，格式：{@code ws:kick:agent:{agentId}} */
    public static final String KICK_LOCK_KEY_PREFIX = "ws:kick:agent:";
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```
期望：无报错

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/pom.xml \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/cache/ConversationCacheKeys.java \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsClusterConstants.java
git commit -m "feat(ws-cluster): pom 引入 Redisson，新增 WsClusterConstants，ConversationCacheKeys 追加 WS presence key 前缀"
```

---

### Task 2: PodIdentity

**Files:**
- Create: `infrastructure/websocket/cluster/PodIdentity.java`
- Test: `test/.../infrastructure/websocket/cluster/PodIdentityTest.java`

**Interfaces:**
- Produces: `PodIdentity.get()` → `String`（AnonymousQueue 名称，如 `spring.gen-abc123`）；`PodIdentity.isLocal(String)` → `boolean`

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/conversation/infrastructure/websocket/cluster/PodIdentityTest.java
package com.aria.conversation.infrastructure.websocket.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PodIdentity")
class PodIdentityTest {

    @Mock RabbitAdmin rabbitAdmin;

    @Test
    @DisplayName("afterPropertiesSet 后 get() 返回队列名")
    void get_returns_queue_name() throws Exception {
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenReturn("spring.gen-test123");
        PodIdentity podIdentity = new PodIdentity(rabbitAdmin);
        podIdentity.afterPropertiesSet();
        assertThat(podIdentity.get()).isEqualTo("spring.gen-test123");
    }

    @Test
    @DisplayName("isLocal 对自身 podId 返回 true，对其他返回 false")
    void isLocal_returns_correct_result() throws Exception {
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenReturn("spring.gen-test123");
        PodIdentity podIdentity = new PodIdentity(rabbitAdmin);
        podIdentity.afterPropertiesSet();
        assertThat(podIdentity.isLocal("spring.gen-test123")).isTrue();
        assertThat(podIdentity.isLocal("spring.gen-other")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=PodIdentityTest -q 2>&1 | tail -5
```
期望：编译失败，PodIdentity 类不存在

- [ ] **Step 3: 实现 PodIdentity**

```java
// infrastructure/websocket/cluster/PodIdentity.java
package com.aria.conversation.infrastructure.websocket.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 当前 Pod 的唯一身份标识。
 *
 * <p>在 {@link #afterPropertiesSet()} 中通过 {@link RabbitAdmin} 声明一个
 * {@code exclusive + autoDelete} 的匿名队列，其队列名作为 podId。
 * Pod 停止时队列随连接断开自动删除，保证 podId 在 Pod 存活期间唯一有效。
 *
 * <p>初始化顺序保证：Spring AMQP 的 {@code RabbitListenerAnnotationBeanPostProcessor}
 * 在 {@code SmartInitializingSingleton.afterSingletonsInstantiated()} 阶段才求值 SpEL，
 * 该阶段晚于所有 {@code afterPropertiesSet()} 调用，因此 {@link WsDeliveryConsumer}
 * 中 {@code key = "#{@podIdentity.get()}"} 求值时 podId 已经就绪，不会为 null。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodIdentity implements InitializingBean {

    private final RabbitAdmin rabbitAdmin;

    private volatile String podId;

    @Override
    public void afterPropertiesSet() {
        // 声明 exclusive + autoDelete 匿名队列，取其 broker 分配的队列名作为 podId
        Queue queue = new AnonymousQueue();
        String name = rabbitAdmin.declareQueue(queue);
        // declareQueue 理论上不应返回 null，但做防御处理
        this.podId = (name != null) ? name : queue.getName();
        log.info("[Cluster] Pod 身份初始化完成 podId={}", podId);
    }

    /**
     * 当前 Pod 的唯一标识（RabbitMQ AnonymousQueue 名称，格式如 spring.gen-abc123）。
     *
     * @return podId 字符串，不为 null
     */
    public String get() {
        return podId;
    }

    /**
     * 判断给定 podId 是否属于本 Pod。
     *
     * @param targetPodId 目标 podId
     * @return true 表示目标在本 Pod
     */
    public boolean isLocal(String targetPodId) {
        return podId.equals(targetPodId);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=PodIdentityTest -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，2 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/PodIdentity.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/cluster/PodIdentityTest.java
git commit -m "feat(ws-cluster): 新增 PodIdentity，使用 RabbitMQ AnonymousQueue 名作为 podId"
```

---

### Task 3: WsPresenceRegistry

**Files:**
- Create: `infrastructure/websocket/cluster/WsPresenceRegistry.java`
- Test: `test/.../infrastructure/websocket/cluster/WsPresenceRegistryTest.java`

**Interfaces:**
- Consumes: `RedisCacheHelper`（String/expire/delete）；`StringRedisTemplate`（Set 操作）；`ConversationCacheKeys.WS_VISITOR_POD_PREFIX` / `WS_AGENT_PODS_PREFIX`
- Produces: `registerVisitor/unregisterVisitor/getVisitorPod/refreshVisitor`；`registerAgent/unregisterAgent/getAgentPods/refreshAgent`

- [ ] **Step 1: 写失败测试**

```java
// test/.../infrastructure/websocket/cluster/WsPresenceRegistryTest.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.common.web.redis.RedisCacheHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WsPresenceRegistry")
class WsPresenceRegistryTest {

    @Mock RedisCacheHelper cache;
    @Mock StringRedisTemplate redis;
    @Mock SetOperations<String, String> setOps;

    private WsPresenceRegistry registry;

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(setOps);
        registry = new WsPresenceRegistry(cache, redis);
    }

    @Test
    @DisplayName("registerVisitor 调用 cache.set 并携带 TTL")
    void registerVisitor_calls_cache_set() {
        registry.registerVisitor("sess-1", "pod-A");
        verify(cache).set(eq("ws:visitor:pod:sess-1"), eq("pod-A"), any(Duration.class));
    }

    @Test
    @DisplayName("unregisterVisitor 调用 cache.delete")
    void unregisterVisitor_calls_cache_delete() {
        registry.unregisterVisitor("sess-1");
        verify(cache).delete("ws:visitor:pod:sess-1");
    }

    @Test
    @DisplayName("getVisitorPod 调用 cache.get")
    void getVisitorPod_calls_cache_get() {
        when(cache.get("ws:visitor:pod:sess-1")).thenReturn("pod-A");
        assertThat(registry.getVisitorPod("sess-1")).isEqualTo("pod-A");
    }

    @Test
    @DisplayName("registerAgent 调用 SADD 和 cache.expire")
    void registerAgent_calls_sadd_and_expire() {
        registry.registerAgent("agent-1", "pod-A");
        verify(setOps).add("ws:agent:pods:agent-1", "pod-A");
        verify(cache).expire(eq("ws:agent:pods:agent-1"), any(Duration.class));
    }

    @Test
    @DisplayName("getAgentPods 返回集合，key 不存在时返回空集合")
    void getAgentPods_returns_empty_on_null() {
        when(setOps.members("ws:agent:pods:agent-1")).thenReturn(null);
        assertThat(registry.getAgentPods("agent-1")).isEmpty();

        when(setOps.members("ws:agent:pods:agent-2")).thenReturn(Set.of("pod-A", "pod-B"));
        assertThat(registry.getAgentPods("agent-2")).containsExactlyInAnyOrder("pod-A", "pod-B");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=WsPresenceRegistryTest -q 2>&1 | tail -5
```

- [ ] **Step 3: 实现 WsPresenceRegistry**

```java
// infrastructure/websocket/cluster/WsPresenceRegistry.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.cache.ConversationCacheKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * WS 集群 presence 注册表。
 *
 * <p>记录访客/座席 WebSocket 连接所在的 Pod（podId = RabbitMQ AnonymousQueue 名称）：
 * <ul>
 *   <li>访客：{@code ws:visitor:pod:{sessionId}} → podId（String，单值）</li>
 *   <li>座席：{@code ws:agent:pods:{agentId}} → Set&lt;podId&gt;（支持多端 BROADCAST 模式）</li>
 * </ul>
 *
 * <p>访客操作使用 {@link RedisCacheHelper}（String 类型），单命令原子写入，强制 TTL。
 * 座席操作使用 {@link StringRedisTemplate}（Set 类型），{@link RedisCacheHelper} 未封装 Set。
 *
 * <p>⚠️ {@link #registerAgent} 的 SADD + EXPIRE 两步非原子：极端情况下 Pod 崩溃于两步之间
 * 会导致 key 无 TTL 永久驻留；建议未来用 Lua 脚本合并为原子操作。
 * 心跳每 30s 刷新 TTL（TTL=90s），可兜底大多数场景。
 */
@Component
@RequiredArgsConstructor
public class WsPresenceRegistry {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final RedisCacheHelper      cache;
    private final StringRedisTemplate   redis;

    // ---- 访客 presence ----

    /** 访客连接建立：记录 sessionId → podId（单命令原子，强制 TTL） */
    public void registerVisitor(String sessionId, String podId) {
        cache.set(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, podId, TTL);
    }

    /** 访客连接断开：删除 presence */
    public void unregisterVisitor(String sessionId) {
        cache.delete(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /** 查询访客所在 podId；不在线返回 null */
    public String getVisitorPod(String sessionId) {
        return cache.get(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /** 刷新访客 presence TTL（心跳调用） */
    public void refreshVisitor(String sessionId) {
        cache.expire(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, TTL);
    }

    // ---- 座席 presence ----

    /** 座席连接建立：将 podId 加入 agentId 的 podId 集合并刷新 TTL */
    public void registerAgent(String agentId, String podId) {
        redis.opsForSet().add(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }

    /** 座席连接断开：从集合中移除 podId */
    public void unregisterAgent(String agentId, String podId) {
        redis.opsForSet().remove(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
    }

    /** 查询座席所在的所有 podId；不在线返回空集合 */
    public Set<String> getAgentPods(String agentId) {
        Set<String> members = redis.opsForSet().members(
                ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId);
        return members != null ? members : Collections.emptySet();
    }

    /** 刷新座席 presence TTL（心跳调用） */
    public void refreshAgent(String agentId) {
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=WsPresenceRegistryTest -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，5 tests passed

- [ ] **Step 5: Commit**

```bash
git add \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsPresenceRegistry.java \
    ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/cluster/WsPresenceRegistryTest.java
git commit -m "feat(ws-cluster): 新增 WsPresenceRegistry，使用 RedisCacheHelper + StringRedisTemplate 管理 presence"
```

### Task 4: AgentConnectionRegistry 集成 presence + 心跳

**Files:**
- Modify: `infrastructure/websocket/AgentConnectionRegistry.java`
- Test: `test/.../infrastructure/websocket/AgentConnectionRegistryPresenceTest.java`

**Interfaces:**
- Consumes: `WsPresenceRegistry`（Task 3）；`PodIdentity`（Task 2）
- Produces: `register/unregister` 同步维护 Redis presence + 启停心跳；`getAgentLock` 移除（KICK 锁移到 Task 8）

- [ ] **Step 1: 写失败测试**

```java
// test/.../infrastructure/websocket/AgentConnectionRegistryPresenceTest.java
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
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentConnectionRegistry presence 集成")
class AgentConnectionRegistryPresenceTest {

    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private AgentConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        when(podIdentity.get()).thenReturn("pod-A");
        registry = new AgentConnectionRegistry(new ObjectMapper(), presenceRegistry, podIdentity);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    @Test
    @DisplayName("register 时调用 presenceRegistry.registerAgent")
    void register_calls_presence_register() {
        WebSocketSession session = mockSession("ws-1");
        registry.register("agent-1", session);
        verify(presenceRegistry).registerAgent("agent-1", "pod-A");
    }

    @Test
    @DisplayName("unregister 后无其他连接时调用 presenceRegistry.unregisterAgent")
    void unregister_calls_presence_unregister_when_no_more_sessions() {
        WebSocketSession session = mockSession("ws-1");
        registry.register("agent-1", session);
        registry.unregister(session);
        verify(presenceRegistry).unregisterAgent("agent-1", "pod-A");
    }

    @Test
    @DisplayName("同一 agentId 有多个连接时 unregister 不移除 presence")
    void unregister_keeps_presence_when_other_sessions_remain() {
        WebSocketSession s1 = mockSession("ws-1");
        WebSocketSession s2 = mockSession("ws-2");
        registry.register("agent-1", s1);
        registry.register("agent-1", s2);
        registry.unregister(s1);
        verify(presenceRegistry, never()).unregisterAgent(any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentConnectionRegistryPresenceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: 改造 AgentConnectionRegistry**

在现有文件基础上做如下修改：

**3a. 新增字段（在 objectMapper 字段后）：**

```java
private final WsPresenceRegistry presenceRegistry;
private final PodIdentity         podIdentity;
// 心跳调度器：内部初始化，不作为 @Bean 注入
private final ScheduledExecutorService heartbeatScheduler =
        Executors.newScheduledThreadPool(2);
private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats =
        new ConcurrentHashMap<>();
```

**3b. 修改 register 方法，添加 presence 注册和心跳启动：**

```java
public void register(String agentId, WebSocketSession session) {
    agentToSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
    sessionIdToAgentId.put(session.getId(), agentId);
    // 注册 presence（已内部处理，KICK 模式下无需外部再次调用）
    presenceRegistry.registerAgent(agentId, podIdentity.get());
    // 启动 presence 心跳刷新（30s 间隔，防止 TTL 90s 到期）
    ScheduledFuture<?> hb = heartbeatScheduler.scheduleAtFixedRate(
        () -> presenceRegistry.refreshAgent(agentId),
        30, 30, TimeUnit.SECONDS);
    heartbeats.put(session.getId(), hb);
    log.debug("[AgentRegistry] 注册连接 agentId={} wsId={}", agentId, session.getId());
}
```

**3c. 修改 unregister 方法，取消心跳并按需移除 presence：**

```java
public void unregister(WebSocketSession session) {
    String agentId = sessionIdToAgentId.remove(session.getId());
    sendLocks.remove(session.getId());
    // 取消心跳
    ScheduledFuture<?> hb = heartbeats.remove(session.getId());
    if (hb != null) hb.cancel(false);
    if (agentId == null) return;
    // 原子删除：空 Set 时同步移除 key，避免 TOCTOU 竞态；同步清理 agentLocks 防内存泄漏
    boolean[] isEmpty = {false};
    agentToSessions.computeIfPresent(agentId, (k, set) -> {
        set.remove(session);
        if (set.isEmpty()) {
            agentLocks.remove(k);
            isEmpty[0] = true;
            return null;
        }
        return set;
    });
    // 本 Pod 上该 agentId 已无连接，从 Redis presence Set 移除本 Pod
    if (isEmpty[0]) {
        presenceRegistry.unregisterAgent(agentId, podIdentity.get());
    }
    log.debug("[AgentRegistry] 注销连接 agentId={} wsId={}", agentId, session.getId());
}
```

**3d. 新增 import（顶部）：**

```java
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="AgentConnectionRegistryPresenceTest,AgentConnectionRegistryTest" -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistry.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistryPresenceTest.java
git commit -m "feat(ws-cluster): AgentConnectionRegistry 集成 presence 注册和心跳刷新"
```

---

### Task 5: ChatWebSocketHandler 访客 presence + 心跳

**Files:**
- Modify: `infrastructure/websocket/ChatWebSocketHandler.java`
- Test: `test/.../infrastructure/websocket/ChatWebSocketHandlerPresenceTest.java`

**Interfaces:**
- Consumes: `WsPresenceRegistry`（Task 3）；`PodIdentity`（Task 2）
- Produces: 访客连接建立时写 presence + 启心跳；断开时删 presence + 取消心跳

- [ ] **Step 1: 写失败测试**

```java
// test/.../infrastructure/websocket/ChatWebSocketHandlerPresenceTest.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketHandler presence 集成")
class ChatWebSocketHandlerPresenceTest {

    @Mock SessionQueueService sessionQueueService;
    @Mock AgentConnectionRegistry agentConnectionRegistry;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;

    private ChatWebSocketHandler buildHandler() {
        when(podIdentity.get()).thenReturn("pod-A");
        return new ChatWebSocketHandler(
                new ObjectMapper(), historyRepository, sessionQueueService,
                agentConnectionRegistry, presenceRegistry, podIdentity);
    }

    private WebSocketSession visitorSession(String sessionId) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn("ws-" + sessionId);
        when(s.getUri()).thenReturn(new URI("/ws/chat/" + sessionId));
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    @Test
    @DisplayName("访客连接建立时调用 presenceRegistry.registerVisitor")
    void connection_established_registers_visitor_presence() throws Exception {
        ChatWebSocketHandler handler = buildHandler();
        WebSocketSession session = visitorSession("sess-001");
        handler.afterConnectionEstablished(session);
        verify(presenceRegistry).registerVisitor("sess-001", "pod-A");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=ChatWebSocketHandlerPresenceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: 改造 ChatWebSocketHandler**

**3a. 新增字段（在 agentConnectionRegistry 字段后）：**

```java
private final WsPresenceRegistry presenceRegistry;
private final PodIdentity         podIdentity;
private final ScheduledExecutorService visitorHeartbeatScheduler =
        Executors.newScheduledThreadPool(2);
private final ConcurrentHashMap<String, ScheduledFuture<?>> visitorHeartbeats =
        new ConcurrentHashMap<>();
```

**3b. afterConnectionEstablished 访客路径中新增 presence + 心跳（在 `closeStaleSession(oldVisitor)` 之后）：**

```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    WebSocketSession oldVisitor = visitorSessions.put(sessionId, session);
    closeStaleSession(oldVisitor);
    // presence 注册
    presenceRegistry.registerVisitor(sessionId, podIdentity.get());
    // 启动心跳（30s 刷新 presence TTL，防止 90s 超时导致跨 Pod 路由失效）
    ScheduledFuture<?> hb = visitorHeartbeatScheduler.scheduleAtFixedRate(
        () -> presenceRegistry.refreshVisitor(sessionId), 30, 30, TimeUnit.SECONDS);
    visitorHeartbeats.put(session.getId(), hb);
    log.info("[WS] visitor connected sessionId={}", sessionId);
    sendJson(session, WsConnectedMessage.forVisitor(sessionId));
}
```

**3c. afterConnectionClosed 访客路径新增清理：**

```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessions.remove(sessionId);
    presenceRegistry.unregisterVisitor(sessionId);
    ScheduledFuture<?> hb = visitorHeartbeats.remove(session.getId());
    if (hb != null) hb.cancel(false);
    log.info("[WS] visitor disconnected sessionId={}", sessionId);
}
```

**3d. handleTransportError 访客路径同样清理：**

```java
if (PATH_SEGMENT_CHAT.equals(role)) {
    visitorSessions.remove(sessionId);
    presenceRegistry.unregisterVisitor(sessionId);
    ScheduledFuture<?> hb = visitorHeartbeats.remove(session.getId());
    if (hb != null) hb.cancel(false);
}
```

**3e. 新增 import：**

```java
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="ChatWebSocketHandlerPresenceTest,ChatWebSocketHandlerNotifyAgentTest,ChatWebSocketHandlerSessionIdTest" -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandlerPresenceTest.java
git commit -m "feat(ws-cluster): ChatWebSocketHandler 访客连接维护 presence 和心跳"
```

### Task 6: WsDeliveryCommand

**Files:**
- Create: `infrastructure/websocket/cluster/WsDeliveryCommand.java`

**Interfaces:**
- Consumes: `WsMessageType`（已有）；`WsChatMessage`、`WsTypingMessage`、`WsConnectedMessage`、`WsErrorMessage`（已有）
- Produces: `WsDeliveryCommand` record，工厂方法 `toAgent/toVisitor/kickAgent`

- [ ] **Step 1: 创建 WsDeliveryCommand**

无需写测试（纯数据 record，工厂方法逻辑由 WsMessageRouterTest 覆盖）。直接实现：

```java
// infrastructure/websocket/cluster/WsDeliveryCommand.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsErrorMessage;
import com.aria.conversation.infrastructure.websocket.message.WsMessageType;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跨 Pod WS 投递命令，序列化为 JSON 后通过 RabbitMQ {@code ws.delivery} Direct Exchange 传递。
 *
 * <p>payload 序列化策略：使用 {@code wsMessageType + payloadJson} 两字段，
 * 避免 Jackson 反序列化 {@code Object} 类型擦除为 {@link java.util.LinkedHashMap}。
 * 发送端预序列化，消费端按 {@link WsMessageType} 枚举 switch 还原为具体 record 类型。
 *
 * <p>KICK_AGENT 命令无 payload，消费端收到后直接本地构造
 * {@link com.aria.conversation.infrastructure.websocket.message.WsKickedOutMessage#INSTANCE} 广播。
 *
 * @param targetType         投递目标类型：AGENT / VISITOR / KICK_AGENT
 * @param targetId           投递目标 ID（agentId 或 sessionId）
 * @param wsMessageType      消息类型（KICK_AGENT 时为 null）
 * @param payloadJson        payload JSON 字符串（KICK_AGENT 时为 null）
 * @param excludeWsSessionId KICK_AGENT 时记录新连接 wsSessionId，仅供日志，可为 null
 */
public record WsDeliveryCommand(
        TargetType targetType,
        String     targetId,
        WsMessageType wsMessageType,
        String     payloadJson,
        String     excludeWsSessionId
) {
    /** 投递目标类型 */
    public enum TargetType { AGENT, VISITOR, KICK_AGENT }

    /**
     * 向座席投递消息。序列化失败时抛 {@link IllegalStateException}（编程错误）。
     */
    public static WsDeliveryCommand toAgent(String agentId, Object payload, ObjectMapper om) {
        return build(TargetType.AGENT, agentId, payload, om);
    }

    /**
     * 向访客投递消息。
     */
    public static WsDeliveryCommand toVisitor(String sessionId, Object payload, ObjectMapper om) {
        return build(TargetType.VISITOR, sessionId, payload, om);
    }

    /**
     * KICK 命令：通知目标 Pod 关闭该 agentId 的所有旧连接。
     * {@code excludeWsSessionId} 为新连接 wsSessionId，仅供日志追踪。
     */
    public static WsDeliveryCommand kickAgent(String agentId, String excludeWsSessionId) {
        return new WsDeliveryCommand(TargetType.KICK_AGENT, agentId, null, null, excludeWsSessionId);
    }

    private static WsDeliveryCommand build(TargetType type, String targetId,
                                           Object payload, ObjectMapper om) {
        WsMessageType msgType = extractMessageType(payload);
        try {
            return new WsDeliveryCommand(type, targetId, msgType,
                    om.writeValueAsString(payload), null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "WS payload 序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }

    /**
     * 从 payload 对象提取 {@link WsMessageType} 枚举。
     *
     * <p>注意：{@link WsMessageType#KICKED_OUT} 不在此 switch，这是故意的。
     * KICK 走 {@link TargetType#KICK_AGENT} 路径，消费端直接构造 WsKickedOutMessage.INSTANCE。
     */
    private static WsMessageType extractMessageType(Object payload) {
        return switch (payload) {
            case WsChatMessage      ignored -> WsMessageType.MESSAGE;
            case WsTypingMessage    ignored -> WsMessageType.TYPING;
            case WsConnectedMessage ignored -> WsMessageType.CONNECTED;
            case WsErrorMessage     ignored -> WsMessageType.ERROR;
            default -> throw new IllegalArgumentException(
                    "不支持跨 Pod 投递的消息类型: " + payload.getClass().getSimpleName());
        };
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```

- [ ] **Step 3: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsDeliveryCommand.java
git commit -m "feat(ws-cluster): 新增 WsDeliveryCommand，使用 WsMessageType 枚举替代 payloadClass 字符串"
```

---

### Task 7: WsMessageRouter + WsDeliveryConsumer

**Files:**
- Create: `infrastructure/websocket/cluster/WsMessageRouter.java`
- Create: `infrastructure/websocket/cluster/WsDeliveryConsumer.java`
- Test: `test/.../infrastructure/websocket/cluster/WsMessageRouterTest.java`

**Interfaces:**
- Consumes: `PodIdentity`；`WsPresenceRegistry`；`AgentConnectionRegistry`；`VisitorNotifier`；`RabbitTemplate`；`ObjectMapper`；`WsDeliveryCommand`（Task 6）；`WsClusterConstants`（Task 1）
- Produces: `WsMessageRouter.sendToAgent(agentId, payload)`；`WsMessageRouter.sendToVisitor(sessionId, payload)`；`WsMessageRouter.sendKick(targetPod, agentId, newWsSessionId)`

- [ ] **Step 1: 写失败测试**

```java
// test/.../infrastructure/websocket/cluster/WsMessageRouterTest.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsMessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WsMessageRouter")
class WsMessageRouterTest {

    @Mock PodIdentity podIdentity;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock AgentConnectionRegistry agentRegistry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock RabbitTemplate rabbitTemplate;

    private WsMessageRouter router;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(podIdentity.get()).thenReturn("pod-A");
        router = new WsMessageRouter(podIdentity, presenceRegistry, agentRegistry,
                visitorNotifier, rabbitTemplate, objectMapper);
    }

    private WsChatMessage chatMsg() {
        return WsChatMessage.fromVisitor("sess-1", "hello", 1L, 1000L);
    }

    @Test
    @DisplayName("座席在本 Pod：直接 broadcast")
    void sendToAgent_local_broadcasts_directly() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of("pod-A"));
        when(podIdentity.isLocal("pod-A")).thenReturn(true);
        router.sendToAgent("agent-1", chatMsg());
        verify(agentRegistry).broadcast("agent-1", chatMsg());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("座席在远端 Pod：发 MQ")
    void sendToAgent_remote_sends_mq() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of("pod-B"));
        when(podIdentity.isLocal("pod-B")).thenReturn(false);
        router.sendToAgent("agent-1", chatMsg());
        verify(rabbitTemplate).convertAndSend(eq("ws.delivery"), eq("pod-B"), any(WsDeliveryCommand.class));
        verifyNoInteractions(agentRegistry);
    }

    @Test
    @DisplayName("访客在本 Pod：直接 notifyVisitor")
    void sendToVisitor_local_notifies_directly() {
        when(presenceRegistry.getVisitorPod("sess-1")).thenReturn("pod-A");
        when(podIdentity.isLocal("pod-A")).thenReturn(true);
        router.sendToVisitor("sess-1", chatMsg());
        verify(visitorNotifier).notifyVisitor("sess-1", chatMsg());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("访客在远端 Pod：发 MQ")
    void sendToVisitor_remote_sends_mq() {
        when(presenceRegistry.getVisitorPod("sess-1")).thenReturn("pod-B");
        when(podIdentity.isLocal("pod-B")).thenReturn(false);
        router.sendToVisitor("sess-1", chatMsg());
        verify(rabbitTemplate).convertAndSend(eq("ws.delivery"), eq("pod-B"), any(WsDeliveryCommand.class));
    }

    @Test
    @DisplayName("座席不在线：不调用任何推送")
    void sendToAgent_offline_skips() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of());
        router.sendToAgent("agent-1", chatMsg());
        verifyNoInteractions(agentRegistry, rabbitTemplate);
    }

    @Test
    @DisplayName("sendKick 发 KICK_AGENT 命令到目标 Pod")
    void sendKick_sends_kick_command() {
        router.sendKick("pod-B", "agent-1", "ws-new-123");
        ArgumentCaptor<WsDeliveryCommand> captor = ArgumentCaptor.forClass(WsDeliveryCommand.class);
        verify(rabbitTemplate).convertAndSend(eq("ws.delivery"), eq("pod-B"), captor.capture());
        WsDeliveryCommand cmd = captor.getValue();
        assertThat(cmd.targetType()).isEqualTo(WsDeliveryCommand.TargetType.KICK_AGENT);
        assertThat(cmd.targetId()).isEqualTo("agent-1");
        assertThat(cmd.excludeWsSessionId()).isEqualTo("ws-new-123");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=WsMessageRouterTest -q 2>&1 | tail -5
```

- [ ] **Step 3: 实现 WsMessageRouter**

```java
// infrastructure/websocket/cluster/WsMessageRouter.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WS 跨 Pod 消息路由器。
 *
 * <p>根据 {@link WsPresenceRegistry} 中的 presence 信息决定本地直推还是通过
 * {@code ws.delivery} RabbitMQ Direct Exchange 跨 Pod 投递。
 *
 * <p>本地路径调用 {@link VisitorNotifier#notifyVisitor}（ChatWebSocketHandler 的本地 socket 写入），
 * 不经过路由层，不存在 notifyVisitor → sendToVisitor 无限递归问题。
 *
 * <p>注入 {@link VisitorNotifier} 接口（而非 ChatWebSocketHandler 实现类），
 * 避免与 ChatWebSocketHandler 注入 WsMessageRouter 形成 Spring 构造器循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsMessageRouter {

    private final PodIdentity            podIdentity;
    private final WsPresenceRegistry     presenceRegistry;
    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier        visitorNotifier;
    private final RabbitTemplate         rabbitTemplate;
    private final ObjectMapper           objectMapper;

    /**
     * 向座席推送消息。本 Pod 直接 broadcast；跨 Pod 发 MQ。
     * BROADCAST 模式下 pods 可包含多个元素（座席多端跨 Pod），逐一处理。
     */
    public void sendToAgent(String agentId, Object payload) {
        Set<String> pods = presenceRegistry.getAgentPods(agentId);
        if (pods.isEmpty()) {
            log.warn("[WsRouter] 座席不在线 agentId={}", agentId);
            return;
        }
        for (String pod : pods) {
            if (podIdentity.isLocal(pod)) {
                agentRegistry.broadcast(agentId, payload);
            } else {
                deliver(pod, WsDeliveryCommand.toAgent(agentId, payload, objectMapper));
            }
        }
    }

    /**
     * 向访客推送消息。本 Pod 直接推；跨 Pod 发 MQ。
     * ⚠️ 本地路径调用 visitorNotifier.notifyVisitor()（本地 socket 写入），
     * 不是 router.sendToVisitor()，防止无限递归。
     */
    public void sendToVisitor(String sessionId, Object payload) {
        String pod = presenceRegistry.getVisitorPod(sessionId);
        if (pod == null) {
            log.warn("[WsRouter] 访客不在线 sessionId={}", sessionId);
            return;
        }
        if (podIdentity.isLocal(pod)) {
            visitorNotifier.notifyVisitor(sessionId, payload);
        } else {
            deliver(pod, WsDeliveryCommand.toVisitor(sessionId, payload, objectMapper));
        }
    }

    /**
     * 向目标 Pod 发送 KICK 命令，通知其关闭该 agentId 的所有旧连接。
     *
     * @param targetPod      目标 Pod 的 podId
     * @param agentId        被踢座席 ID
     * @param newWsSessionId 新连接的 wsSessionId，仅供目标 Pod 日志追踪
     */
    public void sendKick(String targetPod, String agentId, String newWsSessionId) {
        deliver(targetPod, WsDeliveryCommand.kickAgent(agentId, newWsSessionId));
    }

    private void deliver(String targetPod, WsDeliveryCommand cmd) {
        try {
            rabbitTemplate.convertAndSend(WsClusterConstants.WS_DELIVERY_EXCHANGE, targetPod, cmd);
            log.debug("[WsRouter] 跨 Pod 投递 targetPod={} type={} id={}",
                    targetPod, cmd.targetType(), cmd.targetId());
            // TODO: 可配置 mandatory+ReturnsCallback 即时感知 Pod 下线，见设计文档 §4.5
        } catch (Exception e) {
            log.warn("[WsRouter] MQ 投递失败 targetPod={} msg={}", targetPod, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 实现 WsDeliveryConsumer**

```java
// infrastructure/websocket/cluster/WsDeliveryConsumer.java
package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsErrorMessage;
import com.aria.conversation.infrastructure.websocket.message.WsKickedOutMessage;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * WS 跨 Pod 投递消费者。
 *
 * <p>监听本 Pod 专属的匿名队列（exclusive + autoDelete），routing key = podId（AnonymousQueue 名称）。
 * {@link com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter} 根据 Redis presence
 * 精确路由投递命令到目标 Pod 队列。
 *
 * <p>队列声明说明：{@code name = "#{@podIdentity.get()}"} 使 @RabbitListener 监听
 * {@link PodIdentity} 已声明的同一个队列，不额外创建新队列。
 * Exchange 由 @Exchange 注解自动声明（durable=true），无需在 RabbitMQConfig 中额外 @Bean。
 *
 * <p>payload 还原：按 {@link com.aria.conversation.infrastructure.websocket.message.WsMessageType}
 * 枚举 switch 反序列化，避免 Jackson 类型擦除问题（不使用 Class.forName）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsDeliveryConsumer {

    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier         visitorNotifier;
    private final ObjectMapper            objectMapper;

    /**
     * 接收跨 Pod 投递的 WS 消息，执行本地推送。
     * routing key = #{@podIdentity.get()}（SpEL，运行时求值为本 Pod AnonymousQueue 名称）。
     */
    @RabbitListener(bindings = @QueueBinding(
            value    = @Queue(name = "#{@podIdentity.get()}", exclusive = "true", autoDelete = "true"),
            exchange = @Exchange(value = "ws.delivery", type = "direct", durable = "true"),
            key      = "#{@podIdentity.get()}"
    ))
    public void onDelivery(WsDeliveryCommand cmd) {
        try {
            switch (cmd.targetType()) {
                case AGENT -> {
                    Object payload = restorePayload(cmd);
                    agentRegistry.broadcast(cmd.targetId(), payload);
                }
                case VISITOR -> {
                    Object payload = restorePayload(cmd);
                    visitorNotifier.notifyVisitor(cmd.targetId(), payload);
                }
                case KICK_AGENT -> {
                    // 远端 Pod 上该 agentId 的所有连接均为旧连接，全部推 KICKED_OUT 后关闭
                    log.info("[WsDelivery] 收到 KICK 命令 agentId={} srcWsId={}",
                            cmd.targetId(), cmd.excludeWsSessionId());
                    agentRegistry.broadcast(cmd.targetId(), WsKickedOutMessage.INSTANCE);
                    agentRegistry.closeAll(cmd.targetId());
                }
                default -> log.warn("[WsDelivery] 未知 targetType={}", cmd.targetType());
            }
        } catch (Exception e) {
            log.error("[WsDelivery] 消息处理异常 type={} id={}",
                    cmd.targetType(), cmd.targetId(), e);
        }
        log.debug("[WsDelivery] 本地推送完成 type={} id={}", cmd.targetType(), cmd.targetId());
    }

    private Object restorePayload(WsDeliveryCommand cmd) throws JsonProcessingException {
        return switch (cmd.wsMessageType()) {
            case MESSAGE   -> objectMapper.readValue(cmd.payloadJson(), WsChatMessage.class);
            case TYPING    -> objectMapper.readValue(cmd.payloadJson(), WsTypingMessage.class);
            case CONNECTED -> objectMapper.readValue(cmd.payloadJson(), WsConnectedMessage.class);
            case ERROR     -> objectMapper.readValue(cmd.payloadJson(), WsErrorMessage.class);
            // KICKED_OUT 不走此路径，由 KICK_AGENT targetType 直接处理
            default -> throw new IllegalArgumentException(
                    "不支持跨 Pod 投递的消息类型: " + cmd.wsMessageType());
        };
    }
}
```

**注意**：`AgentConnectionRegistry.closeAll()` 方法在 Task 4 改造时需要同时新增：

```java
/** 关闭本 Pod 上该 agentId 的所有连接（KICK_AGENT 命令处理，无需 exclude）。
 * Spring WebSocket 会自动触发 afterConnectionClosed → unregister()，无需手动清理 map。
 */
public void closeAll(String agentId) {
    Set<WebSocketSession> sessions = agentToSessions.get(agentId);
    if (sessions == null) return;
    for (WebSocketSession session : sessions) {
        if (session.isOpen()) {
            try { session.close(CloseStatus.GOING_AWAY); } catch (IOException ignored) {}
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=WsMessageRouterTest -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，7 tests passed

- [ ] **Step 6: 修改 ChatWebSocketHandler.notifyAgent 走路由层**

在 `ChatWebSocketHandler.notifyAgent` 中，将：

```java
agentConnectionRegistry.broadcast(agentId, payload);
```

替换为：

```java
router.sendToAgent(agentId, payload);
```

同时在 ChatWebSocketHandler 中新增字段：

```java
private final WsMessageRouter router;
```

- [ ] **Step 7: 编译验证**

```bash
mvn -f ai-conversation/conversation-service/pom.xml compile -q
```

- [ ] **Step 8: Commit**

```bash
git add \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsMessageRouter.java \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/WsDeliveryConsumer.java \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/AgentConnectionRegistry.java \
    ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/ChatWebSocketHandler.java \
    ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/cluster/WsMessageRouterTest.java
git commit -m "feat(ws-cluster): 新增 WsMessageRouter 和 WsDeliveryConsumer，ChatWebSocketHandler.notifyAgent 走路由层"
```

### Task 8: AgentChannelWsHandler KICK 分布式锁 + 跨 Pod 路由

**Files:**
- Modify: `infrastructure/websocket/AgentChannelWsHandler.java`
- Test: `test/.../infrastructure/websocket/AgentChannelWsHandlerKickDistributedTest.java`

**Interfaces:**
- Consumes: `RedissonClient`；`WsPresenceRegistry`（Task 3）；`PodIdentity`（Task 2）；`WsMessageRouter`（Task 7）
- Produces: KICK 模式使用 Redisson RLock 全局锁；座席回复/TYPING 走 `router.sendToVisitor()`

- [ ] **Step 1: 写失败测试（KICK 分布式路径）**

```java
// test/.../infrastructure/websocket/AgentChannelWsHandlerKickDistributedTest.java
package com.aria.conversation.infrastructure.websocket;

import com.aria.conversation.domain.MultiLoginMode;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChannelWsHandler KICK 分布式锁")
class AgentChannelWsHandlerKickDistributedTest {

    @Mock AgentConnectionRegistry registry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock PodIdentity podIdentity;
    @Mock WsMessageRouter router;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    private AgentChannelWsHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        when(podIdentity.get()).thenReturn("pod-A");
        handler = new AgentChannelWsHandler(registry, visitorNotifier, historyRepository,
                new ObjectMapper(), presenceRegistry, podIdentity, router, redissonClient);
        Field f = AgentChannelWsHandler.class.getDeclaredField("multiLoginMode");
        f.setAccessible(true);
        f.set(handler, MultiLoginMode.KICK);
    }

    private WebSocketSession kickSession(String agentId) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn("ws-new");
        when(s.isOpen()).thenReturn(true);
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put("agentId", agentId);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    @DisplayName("KICK 模式：获取 Redisson 锁，注册并向旧 Pod 发 KICK 命令")
    void kick_mode_acquires_redisson_lock_and_sends_kick() throws Exception {
        when(redissonClient.getLock(startsWith("ws:kick:agent:"))).thenReturn(rLock);
        when(rLock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(java.util.Set.of("pod-B"));
        when(podIdentity.isLocal("pod-B")).thenReturn(false);

        WebSocketSession session = kickSession("agent-1");
        handler.afterConnectionEstablished(session);

        verify(registry).register("agent-1", session);
        verify(router).sendKick("pod-B", "agent-1", "ws-new");
        verify(registry).broadcastExcept(eq("agent-1"), eq(session), any());
        verify(registry).closeAllExcept("agent-1", session);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("KICK 锁获取失败：关闭连接并返回")
    void kick_lock_failed_closes_session() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(false);

        WebSocketSession session = kickSession("agent-2");
        handler.afterConnectionEstablished(session);

        verify(session).close(any());
        verifyNoInteractions(registry);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest=AgentChannelWsHandlerKickDistributedTest -q 2>&1 | tail -5
```

- [ ] **Step 3: 改造 AgentChannelWsHandler**

**3a. 新增字段（在 multiLoginMode 之前）：**

```java
private final WsPresenceRegistry presenceRegistry;
private final PodIdentity         podIdentity;
private final WsMessageRouter     router;
private final RedissonClient      redissonClient;
```

**3b. afterConnectionEstablished KICK 模式替换：**

```java
if (MultiLoginMode.KICK == multiLoginMode) {
    RLock lock = redissonClient.getLock(WsClusterConstants.KICK_LOCK_KEY_PREFIX + agentId);
    boolean acquired;
    try {
        acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("[AgentWS] KICK 锁等待被中断 agentId={}", agentId);
        session.close(CloseStatus.SERVICE_OVERLOAD);
        return;
    }
    if (!acquired) {
        log.warn("[AgentWS] KICK 锁获取超时，拒绝连接 agentId={}", agentId);
        session.close(CloseStatus.SERVICE_OVERLOAD);
        return;
    }
    try {
        // registry.register() 内部已调用 presenceRegistry.registerAgent()，无需再次显式调用
        registry.register(agentId, session);
        // 本 Pod：向旧连接推 KICKED_OUT，关闭旧连接
        registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
        registry.closeAllExcept(agentId, session);
        // 跨 Pod：向其他 Pod 发 KICK 命令
        Set<String> allPods = presenceRegistry.getAgentPods(agentId);
        for (String pod : allPods) {
            if (!podIdentity.isLocal(pod)) {
                router.sendKick(pod, agentId, session.getId());
            }
        }
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
} else {
    // BROADCAST 模式：直接注册，无锁
    registry.register(agentId, session);
}
```

**3c. handleTextMessage 改为走路由层：**

将 `visitorNotifier.notifyVisitor(sessionId, WsTypingMessage.of(...))` 改为：

```java
router.sendToVisitor(sessionId, WsTypingMessage.of(sessionId, ts));
```

将 `visitorNotifier.notifyVisitor(sessionId, WsChatMessage.fromAgent(...))` 改为：

```java
router.sendToVisitor(sessionId, WsChatMessage.fromAgent(sessionId, content, seq, ts));
```

**3d. 新增 import：**

```java
import com.aria.conversation.infrastructure.websocket.cluster.PodIdentity;
import com.aria.conversation.infrastructure.websocket.cluster.WsClusterConstants;
import com.aria.conversation.infrastructure.websocket.cluster.WsMessageRouter;
import com.aria.conversation.infrastructure.websocket.cluster.WsPresenceRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.Set;
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test \
    -Dtest="AgentChannelWsHandlerKickDistributedTest,AgentChannelWsHandlerTest" -q 2>&1 | tail -5
```
期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/AgentChannelWsHandler.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/websocket/AgentChannelWsHandlerKickDistributedTest.java
git commit -m "feat(ws-cluster): AgentChannelWsHandler KICK 换用 Redisson RLock，座席回复走 WsMessageRouter"
```

---

### Task 9: 全量测试验证

**Files:**
- No new files

- [ ] **Step 1: 运行全量测试**

```bash
mvn -f ai-conversation/conversation-service/pom.xml test -q 2>&1 | tail -20
```
期望：`BUILD SUCCESS`，所有测试通过，无 FAILED/ERROR

- [ ] **Step 2: 验证 presence cluster 包完整**

```bash
ls ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/websocket/cluster/
```
期望：包含以下文件：
```
PodIdentity.java
WsClusterConstants.java
WsDeliveryCommand.java
WsDeliveryConsumer.java
WsMessageRouter.java
WsPresenceRegistry.java
```

- [ ] **Step 3: 验证旧 agentLocks 相关代码已清理（由 Task 8 Redisson 锁替代）**

```bash
grep -rn "getAgentLock\b" \
    ai-conversation/conversation-service/src/main/java/ 2>/dev/null
```
期望：无输出（KICK 锁已由 Redisson 接管，本地 agentLocks map 不再被外部调用）

- [ ] **Step 4: 最终 Commit**

```bash
git add -A
git commit -m "test(ws-cluster): 全量测试验证通过，阶段 2-4 多 Pod WS 路由改造完成"
```
