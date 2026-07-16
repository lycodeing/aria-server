# 人工分配改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在访客转人工时，若有在线客服席位未满则自动分配，全满则进入排队队列；客服关闭会话或上线时自动消化排队。

**Architecture:** 在 `SessionQueueService.enqueue()` 入口调用 `tryAutoDispatch()`，用 Redisson `tryLock(wait=0)` + 二次校验实现并发安全的自动分配，绕过 WAITING 状态直接写 ACTIVE；`close()` 和 `registerAgent()` 末尾异步触发 `tryDispatchFromQueue()` 消化排队。SSE 通知通过现有 Fanout MQ 广播，新增 `AUTO_ASSIGNED` 事件类型，前端按 `agentId` 过滤。

**Tech Stack:** Java 21, Spring Boot 3, Redisson 3.x (`redisson-spring-boot-starter`), Caffeine, AuthClient (internal HTTP), JUnit 5 + Mockito

## Global Constraints

- 不修改 `accept()` 方法，不限制客服主动超额接入
- `SessionQueueItem` record 字段顺序不可改变（`@JsonPropertyOrder` CAS 依赖）
- 新增依赖注入到 `SessionQueueService` 构造器末尾，避免破坏已有测试的 null 传参模式
- 所有 MQ 发布走 `publishSafely()` 包装，不抛异常到主流程
- 测试使用 `@ExtendWith(MockitoExtension.class)` + 手动构造 Service，匹配项目现有模式
- Redisson lock key 格式：`lock:assign:agent:{agentId}`，与 `CsatExpiryScheduler` 模式一致

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|---|---|
| `conversation-service/.../infrastructure/config/CsAgentConfig.java` | 配置 POJO，`maxSessionsPerAgent` 字段，默认值 5 |
| `conversation-service/.../infrastructure/config/CsAgentConfigProvider.java` | Caffeine 5min + AuthClient，读取 `cs.agent.config` |
| `conversation-service/src/test/.../CsAgentConfigProviderTest.java` | 缓存命中、降级、序列化 4 个用例 |
| `conversation-service/src/test/.../SessionQueueServiceAutoDispatchTest.java` | tryAutoDispatch 相关 6 个用例 |
| `conversation-service/src/test/.../SessionQueueServiceQueueDrainTest.java` | tryDispatchFromQueue 相关 4 个用例 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `SessionStatus.java` | `AI_CHAT.transitionTo()` 新增允许 → `ACTIVE` |
| `SessionEventType.java` | 新增 `AUTO_ASSIGNED` 枚举值 |
| `CustomerServiceCacheConstant.java` | 新增 `CS_AGENT_CONFIG = "cs.agent.config"` 常量 |
| `SessionQueueService.java` | 注入 2 个新依赖；改造 `enqueue()`/`close()`/`registerAgent()`；新增 9 个私有方法 |
| `auth-service` Flyway migration | 插入 `cs.agent.config` 记录到 `system_config` 表 |

### Task 1: 枚举扩展 + 缓存常量

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionStatus.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionEventType.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CustomerServiceCacheConstant.java`

**Interfaces:**
- Produces: `SessionStatus.AI_CHAT` 允许转换到 `ACTIVE`；`SessionEventType.AUTO_ASSIGNED`；`CustomerServiceCacheConstant.CS_AGENT_CONFIG = "cs.agent.config"`

- [ ] **Step 1: 修改 SessionStatus — 新增 AI_CHAT → ACTIVE 路径**

  `SessionStatus.java` 中找到 `AI_CHAT` 的 case，在 `transitionTo()` 方法里增加 `ACTIVE` 为合法目标：

  ```java
  case AI_CHAT -> {
      // 转人工：WAITING（排队）或 ACTIVE（自动分配直接跳过 WAITING）；或直接结束
      if (next == WAITING || next == ACTIVE || next == CLOSED) {
          yield next;
      }
      throw new IllegalStateException(
              String.format("非法状态转换: %s → %s", this, next));
  }
  ```

- [ ] **Step 2: 修改 SessionEventType — 新增 AUTO_ASSIGNED**

  ```java
  /**
   * 系统自动将排队/新入队会话分配给座席（区别于座席手动 ACCEPTED）
   */
  AUTO_ASSIGNED
  ```

  加在 `TRANSFER` 之后。

- [ ] **Step 3: 修改 CustomerServiceCacheConstant — 新增配置键**

  在 `ROUTING_CONFIG` 常量之后添加：

  ```java
  /**
   * 客服接待配置缓存键，对应 system_config.config_key = 'cs.agent.config'。
   * 由 {@code CsAgentConfigProvider} 使用，缓存序列化后的 {@code CsAgentConfig} 对象。
   */
  public static final String CS_AGENT_CONFIG = "cs.agent.config";
  ```

- [ ] **Step 4: 写失败测试 — AI_CHAT 可转 ACTIVE**

  文件：`src/test/java/com/aria/conversation/domain/SessionStatusTransitionTest.java`（若不存在则新建）

  ```java
  package com.aria.conversation.domain;

  import org.junit.jupiter.api.Test;
  import static org.assertj.core.api.Assertions.*;

  class SessionStatusTransitionTest {

      @Test
      void aiChat_canTransitionTo_active() {
          assertThat(SessionStatus.AI_CHAT.transitionTo(SessionStatus.ACTIVE))
                  .isEqualTo(SessionStatus.ACTIVE);
      }

      @Test
      void aiChat_canTransitionTo_waiting() {
          assertThat(SessionStatus.AI_CHAT.transitionTo(SessionStatus.WAITING))
                  .isEqualTo(SessionStatus.WAITING);
      }

      @Test
      void aiChat_cannotTransitionTo_closed_directly() {
          // CLOSED 仍合法（原有路径保留）
          assertThat(SessionStatus.AI_CHAT.transitionTo(SessionStatus.CLOSED))
                  .isEqualTo(SessionStatus.CLOSED);
      }
  }
  ```

- [ ] **Step 5: 运行测试确认失败**

  ```bash
  cd ai-conversation/conversation-service
  mvn test -pl . -Dtest=SessionStatusTransitionTest -q 2>&1 | tail -20
  ```
  Expected: `aiChat_canTransitionTo_active` FAIL（方法尚未修改）

- [ ] **Step 6: 应用 Step 1 的改动，运行测试确认全部通过**

  ```bash
  mvn test -pl . -Dtest=SessionStatusTransitionTest -q 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESS, 3 tests passed

- [ ] **Step 7: Commit**

  ```bash
  git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionStatus.java \
          ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionEventType.java \
          ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CustomerServiceCacheConstant.java \
          ai-conversation/conversation-service/src/test/java/com/aria/conversation/domain/SessionStatusTransitionTest.java
  git commit -m "feat(dispatch): Task1 — SessionStatus 新增 AI_CHAT→ACTIVE，AUTO_ASSIGNED 事件类型，CS_AGENT_CONFIG 常量"
  ```

### Task 2: CsAgentConfig + CsAgentConfigProvider

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CsAgentConfig.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CsAgentConfigProvider.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/config/CsAgentConfigProviderTest.java`

**Interfaces:**
- Consumes: `CustomerServiceCacheConstant.CS_AGENT_CONFIG`（Task 1），`AuthClient.getSystemConfigValue(String)`
- Produces: `CsAgentConfigProvider.getMaxSessionsPerAgent(): int`（Task 3、4 使用）

- [ ] **Step 1: 写失败测试**

  ```java
  package com.aria.conversation.infrastructure.config;

  import com.aria.sdk.auth.AuthClient;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.extension.ExtendWith;
  import org.mockito.Mock;
  import org.mockito.junit.jupiter.MockitoExtension;

  import static org.assertj.core.api.Assertions.*;
  import static org.mockito.Mockito.*;

  @ExtendWith(MockitoExtension.class)
  class CsAgentConfigProviderTest {

      @Mock AuthClient authClient;
      ObjectMapper objectMapper = new ObjectMapper();
      CsAgentConfigProvider provider;

      @BeforeEach
      void setUp() {
          provider = new CsAgentConfigProvider(authClient, objectMapper);
      }

      @Test
      void getMaxSessions_returnsRemoteValue() throws Exception {
          when(authClient.getSystemConfigValue("cs.agent.config"))
                  .thenReturn("{\"maxSessionsPerAgent\":3}");

          assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(3);
      }

      @Test
      void getMaxSessions_usesCache_onSecondCall() throws Exception {
          when(authClient.getSystemConfigValue("cs.agent.config"))
                  .thenReturn("{\"maxSessionsPerAgent\":3}");

          provider.getMaxSessionsPerAgent();
          provider.getMaxSessionsPerAgent();

          verify(authClient, times(1)).getSystemConfigValue(any());
      }

      @Test
      void getMaxSessions_returnsDefault_whenAuthClientReturnsNull() {
          when(authClient.getSystemConfigValue("cs.agent.config")).thenReturn(null);

          assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(5);
      }

      @Test
      void getMaxSessions_returnsDefault_whenAuthClientThrows() {
          when(authClient.getSystemConfigValue("cs.agent.config"))
                  .thenThrow(new RuntimeException("network error"));

          assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(5);
      }
  }
  ```

- [ ] **Step 2: 运行测试确认失败**

  ```bash
  cd ai-conversation/conversation-service
  mvn test -pl . -Dtest=CsAgentConfigProviderTest -q 2>&1 | tail -10
  ```
  Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 CsAgentConfig.java**

  ```java
  package com.aria.conversation.infrastructure.config;

  import lombok.Data;
  import lombok.NoArgsConstructor;
  import lombok.AllArgsConstructor;

  /**
   * 客服接待配置（对应 system_config.config_key = 'cs.agent.config'）。
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public class CsAgentConfig {

      /** 每个客服最大同时接待会话数，默认 5 */
      private int maxSessionsPerAgent = 5;

      /** 降级默认值，auth-service 不可用时使用 */
      public static CsAgentConfig defaults() {
          return new CsAgentConfig(5);
      }
  }
  ```

- [ ] **Step 4: 创建 CsAgentConfigProvider.java**

  ```java
  package com.aria.conversation.infrastructure.config;

  import com.aria.sdk.auth.AuthClient;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.github.benmanes.caffeine.cache.Cache;
  import com.github.benmanes.caffeine.cache.Caffeine;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Component;

  import java.util.concurrent.TimeUnit;

  /**
   * 客服接待配置提供者。
   *
   * <p>从 auth-service system_config 表读取 {@code cs.agent.config} JSON，
   * Caffeine 本地缓存 TTL 5 分钟，auth-service 不可用时降级返回默认值（maxSessionsPerAgent=5）。
   * 降级值不写入缓存，下次请求重新尝试拉取。
   */
  @Slf4j
  @Component
  @RequiredArgsConstructor
  public class CsAgentConfigProvider {

      private final AuthClient    authClient;
      private final ObjectMapper  objectMapper;

      private final Cache<String, CsAgentConfig> localCache = Caffeine.newBuilder()
              .expireAfterWrite(5, TimeUnit.MINUTES)
              .maximumSize(1)
              .build();

      public CsAgentConfig getConfig() {
          CsAgentConfig cached = localCache.getIfPresent(CustomerServiceCacheConstant.CS_AGENT_CONFIG);
          if (cached != null) return cached;
          try {
              String json = authClient.getSystemConfigValue(CustomerServiceCacheConstant.CS_AGENT_CONFIG);
              if (json != null && !json.isBlank()) {
                  CsAgentConfig config = objectMapper.readValue(json, CsAgentConfig.class);
                  localCache.put(CustomerServiceCacheConstant.CS_AGENT_CONFIG, config);
                  return config;
              }
          } catch (Exception e) {
              log.warn("[CsAgentConfig] 拉取配置失败，使用默认值 maxSessionsPerAgent=5", e);
          }
          return CsAgentConfig.defaults(); // 不缓存降级值
      }

      /** 便捷方法：直接获取 maxSessionsPerAgent */
      public int getMaxSessionsPerAgent() {
          return getConfig().getMaxSessionsPerAgent();
      }
  }
  ```

- [ ] **Step 5: 运行测试确认全部通过**

  ```bash
  mvn test -pl . -Dtest=CsAgentConfigProviderTest -q 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESS, 4 tests passed

- [ ] **Step 6: Commit**

  ```bash
  git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CsAgentConfig.java \
          ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/config/CsAgentConfigProvider.java \
          ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/config/CsAgentConfigProviderTest.java
  git commit -m "feat(dispatch): Task2 — CsAgentConfig + CsAgentConfigProvider（Caffeine+AuthClient）"
  ```

### Task 3: 核心分配逻辑（tryAutoDispatch + helpers）

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceAutoDispatchTest.java`

**Interfaces:**
- Consumes: `CsAgentConfigProvider.getMaxSessionsPerAgent()`（Task 2），`SessionEventType.AUTO_ASSIGNED`（Task 1），`RedissonClient`（已在 pom）
- Produces: `SessionQueueService.enqueue()` 改造；私有方法 `tryAutoDispatch`、`tryAssignNewSession`、`findAvailableCandidates`、`doAssignNewSession`、`withAgentLock`、`isAgentAvailable`、`countActiveSessions`、`buildActiveItem`

- [ ] **Step 1: 写失败测试**

  `SessionQueueServiceAutoDispatchTest.java`:

  ```java
  package com.aria.conversation.application.service;

  import com.aria.conversation.domain.*;
  import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
  import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
  import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
  import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
  import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
  import com.aria.conversation.infrastructure.csat.CsatService;
  import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
  import org.junit.jupiter.api.*;
  import org.junit.jupiter.api.extension.ExtendWith;
  import org.mockito.*;
  import org.mockito.junit.jupiter.MockitoExtension;
  import org.redisson.api.RLock;
  import org.redisson.api.RedissonClient;
  import org.springframework.amqp.rabbit.core.RabbitTemplate;

  import java.util.List;
  import java.util.concurrent.TimeUnit;

  import static org.mockito.ArgumentMatchers.*;
  import static org.mockito.Mockito.*;

  @ExtendWith(MockitoExtension.class)
  @DisplayName("SessionQueueService — tryAutoDispatch")
  class SessionQueueServiceAutoDispatchTest {

      @Mock SessionQueueRepository    queueRepository;
      @Mock AgentOnlineRegistry       agentRegistry;
      @Mock ConversationMessagePublisher publisher;
      @Mock RabbitTemplate            rabbitTemplate;
      @Mock ConversationPersistRepository persistRepository;
      @Mock CsatService               csatService;
      @Mock VisitorNotifier           visitorNotifier;
      @Mock CsAgentConfigProvider     configProvider;
      @Mock RedissonClient            redissonClient;
      @Mock RLock                     rLock;

      SessionQueueService service;

      @BeforeEach
      void setUp() throws Exception {
          // 构造器参数顺序：queueRepository, agentRegistry, publisher, rabbitTemplate,
          //   eventsExchange, persistRepository, csatService, visitorNotifier,
          //   configProvider, redissonClient
          service = new SessionQueueService(
                  queueRepository, agentRegistry, publisher, rabbitTemplate,
                  "test.exchange", persistRepository, csatService, visitorNotifier,
                  configProvider, redissonClient);
          // Redisson 锁默认行为：加锁成功
          when(redissonClient.getLock(anyString())).thenReturn(rLock);
          when(rLock.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
          when(rLock.isHeldByCurrentThread()).thenReturn(true);
      }

      @Test
      @DisplayName("有空闲客服时 enqueue 直接创建 ACTIVE，不写 WAITING")
      void enqueue_autoDispatch_whenAgentHasCapacity() throws Exception {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
          when(queueRepository.findAll()).thenReturn(List.of()); // 0 active sessions
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);

          service.enqueue("sess-1", "visitor", "reason", "tag");

          // 验证 Redis 写入 ACTIVE 状态（非 WAITING）
          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().status()).isEqualTo(SessionStatus.ACTIVE);
          assertThat(captor.getValue().agentId()).isEqualTo("agent-A");

          // 验证 MQ SESSION_START + SESSION_ACCEPT 均发布
          verify(publisher).publishSessionStart(eq("sess-1"), eq("visitor"), eq("reason"), eq("tag"), anyLong());
          verify(publisher).publishSessionAccept(eq("sess-1"), eq("agent-A"), anyLong());
      }

      @Test
      @DisplayName("所有客服满时 enqueue 创建 WAITING")
      void enqueue_fallbackToWaiting_whenAllAgentsFull() throws Exception {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(1);
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
          // agent-A 已有 1 个 ACTIVE 会话（= maxSessions）
          SessionQueueItem existingActive = new SessionQueueItem(
                  "existing", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
          when(queueRepository.findAll()).thenReturn(List.of(existingActive));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);

          service.enqueue("sess-2", "visitor2", "reason", "tag");

          // Redis 写入 WAITING
          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
      }

      @Test
      @DisplayName("无在线客服时 enqueue 创建 WAITING")
      void enqueue_fallbackToWaiting_whenNoAgentOnline() {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
          when(agentRegistry.findAll()).thenReturn(List.of());

          service.enqueue("sess-3", "visitor3", "reason", "tag");

          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
      }

      @Test
      @DisplayName("多个空闲客服时选 sessions 最少的客服（负载均衡）")
      void enqueue_picksLeastLoadedAgent() throws Exception {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
          // agent-B 有 1 个 session，agent-A 有 0 个，getOnlineAgents() 按升序排列
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L),
                  new AgentOnlineRegistry.AgentInfo("agent-B", "Bob", 0L)));
          SessionQueueItem activeB = new SessionQueueItem(
                  "b-sess", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-B");
          when(queueRepository.findAll()).thenReturn(List.of(activeB));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);

          service.enqueue("sess-4", "v4", "reason", "tag");

          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().agentId()).isEqualTo("agent-A"); // 负载更低
      }

      @Test
      @DisplayName("加锁失败时跳过该客服，尝试下一个")
      void enqueue_skipsLockedAgent_picksNext() throws Exception {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L),
                  new AgentOnlineRegistry.AgentInfo("agent-B", "Bob", 0L)));
          when(queueRepository.findAll()).thenReturn(List.of());

          RLock lockA = mock(RLock.class);
          RLock lockB = mock(RLock.class);
          when(redissonClient.getLock("lock:assign:agent:agent-A")).thenReturn(lockA);
          when(redissonClient.getLock("lock:assign:agent:agent-B")).thenReturn(lockB);
          when(lockA.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(false); // agent-A 锁被占
          when(lockB.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
          when(lockB.isHeldByCurrentThread()).thenReturn(true);
          when(agentRegistry.isOnline("agent-B")).thenReturn(true);

          service.enqueue("sess-5", "v5", "reason", "tag");

          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().agentId()).isEqualTo("agent-B");
      }

      @Test
      @DisplayName("二次校验失败（持锁后客服已满）时降级为 WAITING")
      void enqueue_doubleCheckFails_fallbackToWaiting() throws Exception {
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(1);
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
          // 乐观读时 0 sessions，持锁后已有 1 个（并发新增）
          SessionQueueItem raced = new SessionQueueItem(
                  "race", "v", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
          when(queueRepository.findAll()).thenReturn(List.of(raced));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);

          service.enqueue("sess-6", "v6", "reason", "tag");

          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).save(captor.capture());
          assertThat(captor.getValue().status()).isEqualTo(SessionStatus.WAITING);
      }
  }
  ```

  > 注意：测试依赖 AssertJ (`assertThat`)，需要在文件头加 `import static org.assertj.core.api.Assertions.*;`

- [ ] **Step 2: 运行测试确认失败**

  ```bash
  cd ai-conversation/conversation-service
  mvn test -pl . -Dtest=SessionQueueServiceAutoDispatchTest -q 2>&1 | tail -15
  ```
  Expected: FAIL（构造器参数数量不匹配，类不存在）

- [ ] **Step 3: 修改 SessionQueueService 构造器 — 末尾新增两个依赖**

  在现有 8 个构造器参数末尾追加：

  ```java
  // 新增两个字段（在已有字段之后）
  private final CsAgentConfigProvider     csAgentConfigProvider;
  private final RedissonClient            redissonClient;
  ```

  构造器末尾加入两个参数：
  ```java
  public SessionQueueService(
          SessionQueueRepository queueRepository,
          AgentOnlineRegistry agentRegistry,
          ConversationMessagePublisher publisher,
          @Qualifier("eventsRabbitTemplate") RabbitTemplate rabbitTemplate,
          @Value("${conversation.events.exchange}") String eventsExchange,
          ConversationPersistRepository persistRepository,
          CsatService csatService,
          VisitorNotifier visitorNotifier,
          CsAgentConfigProvider csAgentConfigProvider,   // 新增
          RedissonClient redissonClient) {               // 新增
      // ...已有赋值...
      this.csAgentConfigProvider = csAgentConfigProvider;
      this.redissonClient        = redissonClient;
  }
  ```

  同时在文件头新增 import：
  ```java
  import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
  import org.redisson.api.RLock;
  import org.redisson.api.RedissonClient;
  import java.util.Optional;
  import java.util.function.Supplier;
  ```

- [ ] **Step 4: 修改 enqueue() — 调用 tryAutoDispatch**

  将现有 `enqueue()` 改为：

  ```java
  public SessionQueueItem enqueue(String sessionId, String userName,
                                  String transferReason, String tag) {
      SessionQueueItem item = new SessionQueueItem(
              sessionId, userName, transferReason, tag,
              Instant.now().getEpochSecond(), SessionStatus.WAITING, null
      );
      // 优先尝试推送给有空余名额的在线客服，避免访客进入排队等待
      boolean dispatched = tryAutoDispatch(sessionId, item);
      if (dispatched) {
          return item; // 已自动分配，不需要写 WAITING 或广播队列更新
      }
      // 无空闲客服：持久化 WAITING，广播通知所有在线客服
      try {
          queueRepository.save(item);
      } catch (IllegalStateException e) {
          log.error("[SessionQueue] enqueue 失败 sessionId={}", sessionId, e);
          throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
      }
      publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
      publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
      log.info("[SessionQueue] enqueue WAITING sessionId={} userName={}", sessionId, userName);
      return item;
  }
  ```

- [ ] **Step 5: 新增私有方法（在 publishEvent 之前插入）**

  ```java
  // ---- 自动分配：tryAutoDispatch 及辅助方法 ----

  /**
   * 尝试将会话自动分配给负载最低的空闲客服。
   * 按 sessions 升序遍历候选，第一个通过加锁+二次校验的客服即为目标。
   *
   * @return true=分配成功；false=无空闲客服，调用方应写 WAITING
   */
  private boolean tryAutoDispatch(String sessionId, SessionQueueItem item) {
      int max = csAgentConfigProvider.getMaxSessionsPerAgent();
      for (OnlineAgentVO candidate : findAvailableCandidates(max)) {
          if (tryAssignNewSession(sessionId, item, candidate.id(), max)) {
              return true;
          }
      }
      return false;
  }

  /**
   * 从在线客服中筛选出 sessions < max 的候选列表（已按负载升序）。
   * 此处为乐观读，仅做粗筛，真正容量校验在持锁后进行。
   */
  private List<OnlineAgentVO> findAvailableCandidates(int max) {
      return getOnlineAgents().stream()
              .filter(a -> a.sessions() < max)
              .toList();
  }

  /**
   * 对单个候选客服：加锁 → 二次校验 → 执行分配。
   * 加锁失败或校验不通过均返回 false。
   */
  private boolean tryAssignNewSession(String sessionId, SessionQueueItem item,
                                      String agentId, int max) {
      return withAgentLock(agentId, () -> {
          if (!isAgentAvailable(agentId, max)) return false;
          doAssignNewSession(sessionId, item, agentId);
          return true;
      });
  }

  /**
   * 执行新会话的完整分配动作：Redis → MQ（SESSION_START + SESSION_ACCEPT）→ SSE fanout。
   * DB 由 MQ 消费者（ConversationMessageConsumer）异步写入，与现有 enqueue+accept 路径一致。
   */
  private void doAssignNewSession(String sessionId, SessionQueueItem item, String agentId) {
      SessionQueueItem activeItem = buildActiveItem(item, agentId);
      queueRepository.save(activeItem);
      publishSessionStart(sessionId, item.userName(), item.transferReason(),
              item.tag(), item.waitSince());
      publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
      publishEvent(new SessionEvent(SessionEventType.AUTO_ASSIGNED, activeItem));
      log.info("[AutoDispatch] 会话 {} 自动分配给客服 {}", sessionId, agentId);
  }

  /**
   * Redisson 分布式锁通用包装：tryLock(wait=0, TTL=3s) → action → unlock。
   * 加锁失败立即返回 false，不阻塞，不抛异常。
   */
  private boolean withAgentLock(String agentId, Supplier<Boolean> action) {
      RLock lock = redissonClient.getLock("lock:assign:agent:" + agentId);
      try {
          if (!lock.tryLock(0, 3, TimeUnit.SECONDS)) return false;
          try {
              return action.get();
          } finally {
              if (lock.isHeldByCurrentThread()) lock.unlock();
          }
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("[AgentLock] 加锁中断，agentId={}", agentId);
          return false;
      }
  }

  /**
   * 持锁后二次校验：ACTIVE 会话数 < max 且客服在线。
   */
  private boolean isAgentAvailable(String agentId, int max) {
      return countActiveSessions(agentId) < max && agentRegistry.isOnline(agentId);
  }

  /**
   * 统计指定客服当前 ACTIVE 会话数（全量扫描 Redis Hash）。
   */
  private long countActiveSessions(String agentId) {
      return queueRepository.findAll().stream()
              .filter(i -> agentId.equals(i.agentId()) && i.status() == SessionStatus.ACTIVE)
              .count();
  }

  /** 构造 ACTIVE 状态的队列项（record 不可变，返回新实例）。 */
  private SessionQueueItem buildActiveItem(SessionQueueItem item, String agentId) {
      return new SessionQueueItem(
              item.sessionId(), item.userName(), item.transferReason(),
              item.tag(), item.waitSince(), SessionStatus.ACTIVE, agentId);
  }
  ```

  > `import java.util.function.Supplier;` 和 `import java.util.concurrent.TimeUnit;` 确保已加入文件头。

- [ ] **Step 6: 运行测试确认全部通过**

  ```bash
  mvn test -pl . -Dtest=SessionQueueServiceAutoDispatchTest -q 2>&1 | tail -15
  ```
  Expected: BUILD SUCCESS, 6 tests passed

- [ ] **Step 7: 运行回归测试，确认已有测试不受影响**

  ```bash
  mvn test -pl . -Dtest="SessionQueueServiceGetAgentIdTest,CsatServiceTest" -q 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESS（已有测试新增 null 兼容：构造器末尾追加参数，不影响已有传 null 的用法）

- [ ] **Step 8: Commit**

  ```bash
  git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java \
          ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceAutoDispatchTest.java
  git commit -m "feat(dispatch): Task3 — enqueue 自动分配 + tryAutoDispatch + 分布式锁辅助方法"
  ```

### Task 4: 排队消化（close + registerAgent 触发）

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceQueueDrainTest.java`

**Interfaces:**
- Consumes: `withAgentLock`、`isAgentAvailable`、`countActiveSessions`、`buildActiveItem`（Task 3）
- Produces: `tryDispatchFromQueue(String agentId)`；改造后的 `close()` 和 `registerAgent()`

- [ ] **Step 1: 写失败测试**

  ```java
  package com.aria.conversation.application.service;

  import com.aria.conversation.domain.*;
  import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
  import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
  import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
  import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
  import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
  import com.aria.conversation.infrastructure.csat.CsatService;
  import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
  import org.junit.jupiter.api.*;
  import org.junit.jupiter.api.extension.ExtendWith;
  import org.mockito.*;
  import org.mockito.junit.jupiter.MockitoExtension;
  import org.redisson.api.RLock;
  import org.redisson.api.RedissonClient;
  import org.springframework.amqp.rabbit.core.RabbitTemplate;

  import java.util.List;
  import java.util.concurrent.TimeUnit;

  import static org.assertj.core.api.Assertions.*;
  import static org.mockito.ArgumentMatchers.*;
  import static org.mockito.Mockito.*;

  @ExtendWith(MockitoExtension.class)
  @DisplayName("SessionQueueService — tryDispatchFromQueue")
  class SessionQueueServiceQueueDrainTest {

      @Mock SessionQueueRepository        queueRepository;
      @Mock AgentOnlineRegistry           agentRegistry;
      @Mock ConversationMessagePublisher  publisher;
      @Mock RabbitTemplate                rabbitTemplate;
      @Mock ConversationPersistRepository persistRepository;
      @Mock CsatService                   csatService;
      @Mock VisitorNotifier               visitorNotifier;
      @Mock CsAgentConfigProvider         configProvider;
      @Mock RedissonClient                redissonClient;
      @Mock RLock                         rLock;

      SessionQueueService service;

      @BeforeEach
      void setUp() throws Exception {
          service = new SessionQueueService(
                  queueRepository, agentRegistry, publisher, rabbitTemplate,
                  "test.exchange", persistRepository, csatService, visitorNotifier,
                  configProvider, redissonClient);
          when(redissonClient.getLock(anyString())).thenReturn(rLock);
          when(rLock.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
          when(rLock.isHeldByCurrentThread()).thenReturn(true);
          when(configProvider.getMaxSessionsPerAgent()).thenReturn(5);
      }

      @Test
      @DisplayName("close 后腾出空位，WAITING 会话被自动分配")
      void close_triggersQueueDrain_assignsWaitingSession() throws Exception {
          // 准备：agent-A 有 1 个 ACTIVE（即将关闭），1 个 WAITING 等待
          SessionQueueItem active = new SessionQueueItem(
                  "sess-active", "v1", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
          SessionQueueItem waiting = new SessionQueueItem(
                  "sess-wait", "v2", "", "", 1000L, SessionStatus.WAITING, null);

          when(queueRepository.findById("sess-active")).thenReturn(java.util.Optional.of(active));
          // close 后 findAll 只剩 waiting
          when(queueRepository.findAll()).thenReturn(List.of(waiting));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);
          when(queueRepository.compareAndSetStatus(eq("sess-wait"), any())).thenReturn(true);
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));

          service.close("sess-active", "agent");

          // 等待异步 tryDispatchFromQueue 执行
          Thread.sleep(200);

          // 验证排队会话被分配
          ArgumentCaptor<SessionQueueItem> captor = ArgumentCaptor.forClass(SessionQueueItem.class);
          verify(queueRepository).compareAndSetStatus(eq("sess-wait"), captor.capture());
          assertThat(captor.getValue().status()).isEqualTo(SessionStatus.ACTIVE);
          assertThat(captor.getValue().agentId()).isEqualTo("agent-A");
          verify(publisher).publishSessionAccept(eq("sess-wait"), eq("agent-A"), anyLong());
      }

      @Test
      @DisplayName("close 后队列为空，不触发任何分配")
      void close_noQueueDrain_whenQueueEmpty() throws Exception {
          SessionQueueItem active = new SessionQueueItem(
                  "sess-only", "v1", "", "", 0L, SessionStatus.ACTIVE, "agent-A");
          when(queueRepository.findById("sess-only")).thenReturn(java.util.Optional.of(active));
          when(queueRepository.findAll()).thenReturn(List.of()); // 无 WAITING
          when(agentRegistry.findAll()).thenReturn(List.of(
                  new AgentOnlineRegistry.AgentInfo("agent-A", "Alice", 0L)));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);

          service.close("sess-only", "agent");
          Thread.sleep(200);

          verify(queueRepository, never()).compareAndSetStatus(any(), any());
      }

      @Test
      @DisplayName("registerAgent 后 WAITING 会话被自动分配")
      void registerAgent_triggersQueueDrain() throws Exception {
          SessionQueueItem waiting = new SessionQueueItem(
                  "sess-wait", "v2", "", "", 1000L, SessionStatus.WAITING, null);
          when(queueRepository.findAll()).thenReturn(List.of(waiting));
          when(agentRegistry.isOnline("agent-new")).thenReturn(true);
          when(queueRepository.compareAndSetStatus(eq("sess-wait"), any())).thenReturn(true);

          service.registerAgent("agent-new", "NewAgent");
          Thread.sleep(200);

          verify(publisher).publishSessionAccept(eq("sess-wait"), eq("agent-new"), anyLong());
      }

      @Test
      @DisplayName("CAS 失败（已被手动接入）时静默跳过")
      void drain_casFailure_silentlyIgnored() throws Exception {
          SessionQueueItem waiting = new SessionQueueItem(
                  "sess-race", "v", "", "", 0L, SessionStatus.WAITING, null);
          when(queueRepository.findAll()).thenReturn(List.of(waiting));
          when(agentRegistry.isOnline("agent-A")).thenReturn(true);
          // CAS 失败：模拟并发手动接入
          when(queueRepository.compareAndSetStatus(any(), any())).thenReturn(false);

          service.registerAgent("agent-A", "Alice");
          Thread.sleep(200);

          // SESSION_ACCEPT 不应发布
          verify(publisher, never()).publishSessionAccept(any(), any(), anyLong());
      }
  }
  ```

- [ ] **Step 2: 运行测试确认失败**

  ```bash
  cd ai-conversation/conversation-service
  mvn test -pl . -Dtest=SessionQueueServiceQueueDrainTest -q 2>&1 | tail -15
  ```
  Expected: FAIL

- [ ] **Step 3: 新增 tryDispatchFromQueue 及拆分方法（在 Task 3 新增的方法块之后追加）**

  ```java
  // ---- 排队消化：tryDispatchFromQueue 及辅助方法 ----

  /**
   * 当指定客服腾出空位时，从 WAITING 队列取出等待最久的会话并自动分配。
   * 仅消化一条（一次空位只消化一条，避免瞬间超额）。
   * 在异步线程中执行，不阻塞主请求。
   */
  private void tryDispatchFromQueue(String agentId) {
      int max = csAgentConfigProvider.getMaxSessionsPerAgent();
      boolean acquired = withAgentLock(agentId, () -> {
          tryAssignOldestWaiting(agentId, max);
          return true;
      });
      if (!acquired) {
          log.debug("[QueueDrain] 加锁竞争，跳过本次消化 agentId={}", agentId);
      }
  }

  /**
   * 持锁后：二次校验有空位，取最早的 WAITING 会话执行分配。
   */
  private void tryAssignOldestWaiting(String agentId, int max) {
      if (!isAgentAvailable(agentId, max)) return;
      pickOldestWaiting().ifPresent(entry ->
              doDispatchWaitingSession(entry.getKey(), entry.getValue(), agentId));
  }

  /**
   * 从 Redis 中取出 waitSince 最小（等待最久）的 WAITING 会话。
   */
  private Optional<Map.Entry<String, SessionQueueItem>> pickOldestWaiting() {
      // findAll() 返回 List，需要转为可查找 sessionId 的结构
      return queueRepository.findAll().stream()
              .filter(i -> i.status() == SessionStatus.WAITING)
              .min(Comparator.comparingLong(SessionQueueItem::waitSince))
              .map(i -> Map.entry(i.sessionId(), i));
  }

  /**
   * CAS 激活排队会话 + 发布 SESSION_ACCEPT 事件。
   * CAS 失败表示该会话已被手动接入，静默跳过。
   */
  private void doDispatchWaitingSession(String sessionId, SessionQueueItem waitingItem,
                                        String agentId) {
      boolean cas = queueRepository.compareAndSetStatus(
              sessionId, buildActiveItem(waitingItem, agentId));
      if (!cas) {
          log.debug("[QueueDrain] CAS 失败，会话 {} 已被手动接入", sessionId);
          return;
      }
      publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
      publishEvent(new SessionEvent(SessionEventType.AUTO_ASSIGNED,
              buildActiveItem(waitingItem, agentId)));
      log.info("[QueueDrain] 排队会话 {} 分配给客服 {}", sessionId, agentId);
  }
  ```

  同时在文件头新增 import：
  ```java
  import java.util.Map;
  ```

- [ ] **Step 4: 改造 close() — 末尾追加异步触发**

  在 `triggerCsatAsync(sessionId, agentIdHolder[0]);` 之后追加：

  ```java
  // 腾出空位后异步消化排队队列（不阻塞关闭请求）
  String freedAgent = agentIdHolder[0];
  if (freedAgent != null) {
      CompletableFuture.runAsync(() -> tryDispatchFromQueue(freedAgent));
  }
  ```

  同时确认 `import java.util.concurrent.CompletableFuture;` 已存在（原 close() 已用）。

- [ ] **Step 5: 改造 registerAgent() — 末尾追加异步触发**

  将现有 `registerAgent()` 改为：

  ```java
  public void registerAgent(String agentId, String displayName) {
      agentRegistry.register(agentId, displayName);
      // 新客服上线，sessions=0，优先接待等待最久的访客
      CompletableFuture.runAsync(() -> tryDispatchFromQueue(agentId));
  }
  ```

- [ ] **Step 6: 运行测试确认全部通过**

  ```bash
  mvn test -pl . -Dtest=SessionQueueServiceQueueDrainTest -q 2>&1 | tail -15
  ```
  Expected: BUILD SUCCESS, 4 tests passed

- [ ] **Step 7: 运行全量 conversation-service 测试，确认无回归**

  ```bash
  mvn test -pl . -q 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 8: Commit**

  ```bash
  git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java \
          ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceQueueDrainTest.java
  git commit -m "feat(dispatch): Task4 — tryDispatchFromQueue + close/registerAgent 排队消化"
  ```

### Task 5: SQL Migration + 计划自检

**Files:**
- Create: `ai-auth/auth-service/src/main/resources/db/migration/V{N}__add_cs_agent_config.sql`
  （N = 当前最大版本号 + 1，执行 `ls ai-auth/auth-service/src/main/resources/db/migration/ | sort | tail -3` 确认）

**Interfaces:**
- Produces: `system_config` 表中 `cs.agent.config` 记录，供 `CsAgentConfigProvider` 读取

- [ ] **Step 1: 确认 Flyway migration 目录和当前最大版本号**

  ```bash
  # 找 auth-service migration 目录
  find ai-auth -name "V*.sql" 2>/dev/null | sort | tail -5
  # 若为空，确认目录位置
  find ai-auth -type d -name "migration" 2>/dev/null
  ```

  若目录不存在：
  ```bash
  mkdir -p ai-auth/auth-service/src/main/resources/db/migration
  ```

- [ ] **Step 2: 创建 migration SQL 文件**

  将 `{N}` 替换为实际下一个版本号（如当前最大为 V7，则文件名为 `V8__add_cs_agent_config.sql`）：

  ```sql
  -- 新增客服接待配置：每个客服最大同时接待会话数
  -- 默认值 5，可在管理后台通过 system_config 管理页面修改
  INSERT INTO system_config (config_key, config_value, config_type, description, is_enabled, created_at, updated_at)
  VALUES (
      'cs.agent.config',
      '{"maxSessionsPerAgent": 5}',
      'CUSTOMER_SERVICE',
      '客服接待配置：maxSessionsPerAgent 为每个客服最大同时接待会话数，达到阈值后不再自动分配，客服可主动超额接入',
      1,
      NOW(),
      NOW()
  );
  ```

- [ ] **Step 3: 验证 SQL 语法（本地 psql 或 dry-run）**

  ```bash
  # 若有本地 DB，执行 dry-run 验证语法
  # 或直接检查 system_config 表的列定义是否与 SQL 匹配
  grep -r "system_config\|config_key\|config_value\|config_type\|is_enabled" \
    ai-auth/auth-service/src/main/java/com/aria/auth/infrastructure/persistence/systemconfig/SystemConfigDO.java \
    | head -10
  ```

  确认字段名与 `SystemConfigDO` 中 `@TableField` 注解的列名一致。

- [ ] **Step 4: 运行 auth-service 单元测试确认无回归**

  ```bash
  cd ai-auth/auth-service
  mvn test -pl . -q 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

  ```bash
  git add ai-auth/auth-service/src/main/resources/db/migration/V{N}__add_cs_agent_config.sql
  git commit -m "feat(dispatch): Task5 — system_config 新增 cs.agent.config（maxSessionsPerAgent=5）"
  ```

---

## 计划自检

### Spec 覆盖检查

| 需求 | 对应 Task |
|---|---|
| ① 席位未满自动分配给在线客服 | Task 3（tryAutoDispatch） |
| ② 达到阈值后客服可主动超额接入 | accept() 不修改（Global Constraints） |
| ③ 全部满员进入排队队列 | Task 3（enqueue fallback to WAITING） |
| ④ 阈值通过 system_config 配置 | Task 2（CsAgentConfigProvider）+ Task 5（SQL） |
| ⑤ 自动分配策略：负载最低客服优先 | Task 3（findAvailableCandidates，getOnlineAgents 升序） |
| ⑥ 排队消化：close/registerAgent 触发 | Task 4（tryDispatchFromQueue） |

全部 6 个需求均已覆盖，无遗漏。

### 类型一致性检查

| 方法 | 定义 Task | 使用 Task | 签名 |
|---|---|---|---|
| `withAgentLock(String, Supplier<Boolean>)` | Task 3 | Task 3, 4 | ✓ 一致 |
| `isAgentAvailable(String, int)` | Task 3 | Task 3, 4 | ✓ 一致 |
| `countActiveSessions(String): long` | Task 3 | Task 3, 4 | ✓ 一致 |
| `buildActiveItem(SessionQueueItem, String): SessionQueueItem` | Task 3 | Task 3, 4 | ✓ 一致 |
| `CsAgentConfigProvider.getMaxSessionsPerAgent(): int` | Task 2 | Task 3, 4 | ✓ 一致 |
| `publishSessionAccept(String, String, long)` | 已有私有方法 | Task 3, 4 | ✓ 一致 |
| `publishSessionStart(String, String, String, String, long)` | 已有私有方法 | Task 3 | ✓ 一致 |
| `SessionEventType.AUTO_ASSIGNED` | Task 1 | Task 3, 4 | ✓ 一致 |

### 测试并发注意事项

Task 4 的 `close_triggersQueueDrain_assignsWaitingSession` 测试使用 `Thread.sleep(200)` 等待异步执行。
若 CI 环境较慢，可将等待时间调整为 500ms，或改用 `CompletableFuture` + `get(1, TimeUnit.SECONDS)`。

### 构造器兼容性

新增的 `configProvider` 和 `redissonClient` 追加在构造器末尾，已有测试（如 `SessionQueueServiceGetAgentIdTest`）传 `null`，
Spring 不会自动调用该构造器（通过 `@Autowired` 全参注入），生产环境正常注入。
单元测试的 null 传参不影响运行，因为已有测试只调用 `getAgentId()`，不触发新方法。
