# 访客会话创建统一化改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `POST /api/v1/chat/session/init` 统一会话创建入口，引入访客唯一标识 `visitor_id`，清理现有两条隐式创建路径，使每个状态转换有且仅有一条代码路径。

**Architecture:** chat-widget 打开时调用 init 接口，`VisitorSessionService.getOrCreate()` 用 Redisson 分布式锁保护"查活跃会话 → 无则创建"的原子操作，返回 sessionId 和状态。转人工 MQ 消费端从 insert-or-upgrade 简化为纯 UPDATE。

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus 3, Redisson（已引入），PostgreSQL, RabbitMQ, JUnit 5 + Mockito + AssertJ

## Global Constraints

- 所有 Mapper SQL 用 `Wrappers.lambdaUpdate()` / `Wrappers.lambdaQuery()`，禁止 `@Update/@Select` 魔法字符串
- 测试风格：`@ExtendWith(MockitoExtension.class)`，AssertJ 断言，方法名 `method_scenario_expectation`
- `X-Anonymous-Id` 校验正则：`^[a-zA-Z0-9_\-]{8,64}$`
- sessionId 格式正则（现有）：`^[a-zA-Z0-9_\-]{1,64}$`
- 新会话 sessionId 生成规则：`"guest-" + UUID.randomUUID().toString().replace("-", "")`
- 分布式锁 key：`"visitor:init:" + anonymousId`，TTL 3s
- 所有新方法需要与现有代码同等密度的 Javadoc 注释

---

## File Map

### 新建文件
- `interfaces/rest/ChatSessionController.java` — POST /api/v1/chat/session/init 入口
- `interfaces/rest/vo/InitSessionVO.java` — 响应 VO（sessionId, status, isNew）
- `application/service/VisitorSessionService.java` — getOrCreate 核心逻辑
- `application/dto/InitSessionResult.java` — 服务层返回 record
- `application/dto/InitSessionRequest.java` — 请求 DTO（visitorName 可选）
- `test/.../application/service/VisitorSessionServiceTest.java`
- `test/.../interfaces/rest/ChatSessionControllerTest.java`

### 修改文件
- `infrastructure/persistence/entity/ConversationEntity.java` — 新增 visitorId/visitorIp/visitorDevice
- `infrastructure/persistence/mapper/ConversationMapper.java` — 新增 selectByVisitorId
- `infrastructure/persistence/ConversationPersistRepository.java` — 新增 findActiveByVisitorId/createAiChatSession/upgradeToWaiting/existsBySessionId；删除 initAiChatSession/startConversation
- `infrastructure/mq/ConversationMessageConsumer.java` — handleSessionStart 改调 upgradeToWaiting
- `application/service/FaqChatAppService.java` — 删除 initAiChatSession 调用，加 existsBySessionId 校验
- `application/service/SessionQueueService.java` — 删除 initAiChatSession 方法
- `application/service/VisitorHistoryService.java` — 入参改为 visitorId，调 getVisitorHistoryByVisitorId
- `interfaces/rest/SessionQueueController.java` — getVisitorHistory 参数从 visitorName 改为 visitorId
- `interfaces/rest/ChatController.java` — resolveSessionId 去掉自动生成
- `docs/sql/conversation-service-schema.sql` — 同步新增三列 DDL

---
### Task 1: DB 变更 — ConversationEntity 新增三列

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/ConversationEntity.java`
- Modify: `docs/sql/conversation-service-schema.sql`

**Interfaces:**
- Produces: `ConversationEntity.visitorId`, `ConversationEntity.visitorIp`, `ConversationEntity.visitorDevice` — 后续 Task 2/3/4 均依赖这三个字段

- [ ] **Step 1: 在 ConversationEntity 末尾追加三个字段**

打开 `ConversationEntity.java`，在最后一个字段（`updatedAt`）之后追加：

```java
/** 访客唯一标识，前端 localStorage 生成的 anonymousId，历史数据为 null */
@TableField("visitor_id")
private String visitorId;

/** 访客 IP，取 X-Forwarded-For 首个地址或直连 RemoteAddr，支持 IPv4/IPv6，历史数据为 null */
@TableField("visitor_ip")
private String visitorIp;

/** 访客设备信息，原始 User-Agent 字符串，历史数据为 null */
@TableField("visitor_device")
private String visitorDevice;
```

- [ ] **Step 2: 编译验证**

```bash
cd ai-conversation/conversation-service
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 同步更新 SQL 文档**

在 `docs/sql/conversation-service-schema.sql` 找到 `cs_conversation` 表的 DDL（`CREATE TABLE cs_conversation.cs_conversation`），在 `closed_by` 列之后追加三列定义，并在表定义结束后追加注释和索引 DDL：

```sql
    visitor_id      character varying(64)  DEFAULT NULL,
    visitor_ip      character varying(45)  DEFAULT NULL,
    visitor_device  character varying(500) DEFAULT NULL
```

在文件适当位置追加（`ALTER TABLE` 部分或单独 migration 注释块）：

```sql
-- 2026-07-17: 访客会话创建统一化改造
-- 1. 新增访客标识列
ALTER TABLE cs_conversation.cs_conversation
    ADD COLUMN IF NOT EXISTS visitor_id     VARCHAR(64)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS visitor_ip     VARCHAR(45)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS visitor_device VARCHAR(500) DEFAULT NULL;

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_id
    IS '访客唯一标识，前端 localStorage 生成的 anonymousId';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_ip
    IS '访客 IP，取 X-Forwarded-For 首个地址或直连 RemoteAddr，支持 IPv4/IPv6';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_device
    IS '访客设备信息，原始 User-Agent 字符串';

-- 2. 新增索引（生产执行时用 CONCURRENTLY）
CREATE INDEX IF NOT EXISTS idx_cs_conv_visitor_id
    ON cs_conversation.cs_conversation (visitor_id, status)
    WHERE visitor_id IS NOT NULL;
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/ConversationEntity.java
git add docs/sql/conversation-service-schema.sql
git commit -m "feat(visitor-session): ConversationEntity 新增 visitorId/visitorIp/visitorDevice 三列"
```

---
### Task 2: ConversationMapper + ConversationPersistRepository — 新增查询与创建方法

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/ConversationMapper.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepositoryTest.java`（新建）

**Interfaces:**
- Consumes: `ConversationEntity.visitorId` (Task 1)
- Produces:
  - `ConversationMapper.selectByVisitorId(visitorId, excludedStatus)` → `List<ConversationEntity>`
  - `ConversationPersistRepository.findActiveByVisitorId(String visitorId)` → `Optional<ConversationEntity>`
  - `ConversationPersistRepository.createAiChatSession(String sessionId, String visitorId, String visitorName, String visitorIp, String visitorDevice, OffsetDateTime now)` → `void`
  - `ConversationPersistRepository.upgradeToWaiting(String sessionId, String visitorName, String transferReason, String tag, OffsetDateTime now)` → `int`
  - `ConversationPersistRepository.existsBySessionId(String sessionId)` → `boolean`

- [ ] **Step 1: 在 ConversationMapper 末尾追加 selectByVisitorId 方法**

```java
/**
 * 按 visitor_id 查询非 CLOSED 的会话，按 started_at 倒序，最多返回 1 条。
 * 利用 idx_cs_conv_visitor_id 部分索引，避免全表扫描。
 *
 * @param visitorId 访客唯一标识
 * @return 最近一条活跃会话，visitor_id 为 null 或不存在时返回空列表
 */
default List<ConversationEntity> selectActiveByVisitorId(@Param("visitorId") String visitorId) {
    return selectList(
        Wrappers.lambdaQuery(ConversationEntity.class)
            .eq(ConversationEntity::getVisitorId, visitorId)
            .ne(ConversationEntity::getStatus, SessionStatus.CLOSED.getValue())
            .orderByDesc(ConversationEntity::getStartedAt)
            .last("LIMIT 1")
    );
}
```

同时追加 `selectByVisitorId` 用于历史查询（Task 7 使用）：

```java
/**
 * 按 visitor_id 查询历史会话，排除指定 sessionId，按 started_at 倒序。
 *
 * @param visitorId        访客唯一标识
 * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
 * @param limit            最大返回条数
 * @return 历史会话列表
 */
default List<ConversationEntity> selectByVisitorId(@Param("visitorId") String visitorId,
                                                    @Param("excludeSessionId") String excludeSessionId,
                                                    @Param("limit") int limit) {
    return selectList(
        Wrappers.lambdaQuery(ConversationEntity.class)
            .eq(ConversationEntity::getVisitorId, visitorId)
            .ne(excludeSessionId != null, ConversationEntity::getSessionId, excludeSessionId)
            .orderByDesc(ConversationEntity::getStartedAt)
            .last("LIMIT " + limit)
    );
}
```

- [ ] **Step 2: 在 ConversationPersistRepository 新增四个方法**

在 `initAiChatSession` 方法之前插入以下四个方法（保留 `initAiChatSession` 和 `startConversation`，Task 5/6 再删除，避免一次改动太大）：

```java
/**
 * 按 visitor_id 查询最近一条非 CLOSED 会话。
 *
 * @param visitorId 访客唯一标识（X-Anonymous-Id）
 * @return 最近一条活跃会话；visitor_id 为 null 或无活跃会话时返回 empty
 */
public Optional<ConversationEntity> findActiveByVisitorId(String visitorId) {
    List<ConversationEntity> list = conversationMapper.selectActiveByVisitorId(visitorId);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
}

/**
 * 创建 AI_CHAT 状态的新会话，包含访客唯一标识和设备信息。
 * 调用方应通过分布式锁保证同一 visitorId 不会并发创建。
 *
 * @param sessionId    会话唯一标识
 * @param visitorId    访客唯一标识（前端 localStorage UUID）
 * @param visitorName  访客展示名称
 * @param visitorIp    访客 IP（X-Forwarded-For 或 RemoteAddr）
 * @param visitorDevice 访客设备信息（User-Agent）
 * @param now          会话开始时间
 */
public void createAiChatSession(String sessionId, String visitorId,
                                 String visitorName, String visitorIp,
                                 String visitorDevice, OffsetDateTime now) {
    ConversationEntity entity = new ConversationEntity();
    entity.setSessionId(sessionId);
    entity.setVisitorId(visitorId);
    entity.setVisitorName(visitorName);
    entity.setVisitorIp(visitorIp);
    entity.setVisitorDevice(visitorDevice);
    entity.setTransferReason("");
    entity.setTag("AI 对话");
    entity.setStatus(SessionStatus.AI_CHAT);
    entity.setStartedAt(now);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    conversationMapper.insert(entity);
    log.debug("[Persist] 创建 AI_CHAT 会话 sessionId={} visitorId={}", sessionId, visitorId);
}

/**
 * 转人工：将 AI_CHAT 状态的会话升级为 WAITING（纯 UPDATE，无 insert 兜底）。
 * 新设计下转人工时会话一定已存在，insert 兜底已无必要。
 *
 * @return 受影响行数；0 表示会话不存在或状态已超过 WAITING（记 warn 日志）
 */
public int upgradeToWaiting(String sessionId, String visitorName,
                             String transferReason, String tag,
                             OffsetDateTime now) {
    int rows = conversationMapper.update(
        Wrappers.lambdaUpdate(ConversationEntity.class)
            .set(ConversationEntity::getStatus,         SessionStatus.WAITING.getValue())
            .set(ConversationEntity::getVisitorName,    visitorName)
            .set(ConversationEntity::getTransferReason, transferReason)
            .set(ConversationEntity::getTag,            tag != null && !tag.isBlank() ? tag : "咨询")
            .set(ConversationEntity::getStartedAt,      now)
            .eq(ConversationEntity::getSessionId,       sessionId)
            .eq(ConversationEntity::getStatus,          SessionStatus.AI_CHAT.getValue())
    );
    if (rows == 0) {
        log.warn("[Persist] upgradeToWaiting 影响 0 行，sessionId={} 可能不存在或已超过 WAITING 状态",
                 sessionId);
    }
    return rows;
}

/**
 * 检查指定 sessionId 是否存在且未关闭（用于消息发送前的会话存在性校验）。
 *
 * @param sessionId 会话唯一标识
 * @return true 表示会话存在且非 CLOSED
 */
public boolean existsBySessionId(String sessionId) {
    return conversationMapper.exists(
        Wrappers.lambdaQuery(ConversationEntity.class)
            .eq(ConversationEntity::getSessionId, sessionId)
            .ne(ConversationEntity::getStatus,    SessionStatus.CLOSED.getValue())
    );
}
```

- [ ] **Step 3: 新建测试文件，写失败测试**

新建 `src/test/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepositoryTest.java`：

```java
package com.aria.conversation.infrastructure.persistence;

import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationPersistRepositoryTest {

    @Mock ConversationMapper conversationMapper;
    @Mock ConversationMessageMapper conversationMessageMapper;
    @InjectMocks ConversationPersistRepository repository;

    @Test
    void findActiveByVisitorId_found_returnsEntity() {
        ConversationEntity entity = new ConversationEntity();
        entity.setSessionId("sess_1");
        entity.setStatus(SessionStatus.AI_CHAT);
        when(conversationMapper.selectActiveByVisitorId("v_abc")).thenReturn(List.of(entity));

        Optional<ConversationEntity> result = repository.findActiveByVisitorId("v_abc");

        assertThat(result).isPresent();
        assertThat(result.get().getSessionId()).isEqualTo("sess_1");
    }

    @Test
    void findActiveByVisitorId_notFound_returnsEmpty() {
        when(conversationMapper.selectActiveByVisitorId("v_new")).thenReturn(List.of());

        assertThat(repository.findActiveByVisitorId("v_new")).isEmpty();
    }

    @Test
    void createAiChatSession_insertsCorrectEntity() {
        OffsetDateTime now = OffsetDateTime.now();

        repository.createAiChatSession("sess_1", "v_abc", "张三", "1.2.3.4",
                "Mozilla/5.0", now);

        ArgumentCaptor<ConversationEntity> cap = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).insert(cap.capture());
        ConversationEntity saved = cap.getValue();
        assertThat(saved.getSessionId()).isEqualTo("sess_1");
        assertThat(saved.getVisitorId()).isEqualTo("v_abc");
        assertThat(saved.getVisitorIp()).isEqualTo("1.2.3.4");
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.AI_CHAT);
    }

    @Test
    void upgradeToWaiting_rowsUpdated_returnsCount() {
        when(conversationMapper.update(any())).thenReturn(1);

        int rows = repository.upgradeToWaiting("sess_1", "访客", "需要帮助", "咨询",
                OffsetDateTime.now());

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void upgradeToWaiting_noRowsUpdated_logsWarnAndReturnsZero() {
        when(conversationMapper.update(any())).thenReturn(0);

        int rows = repository.upgradeToWaiting("sess_missing", "访客", "", "咨询",
                OffsetDateTime.now());

        assertThat(rows).isZero();
        // warn 日志由 Slf4j 打印，此处只验证返回值不抛异常
    }

    @Test
    void existsBySessionId_exists_returnsTrue() {
        when(conversationMapper.exists(any())).thenReturn(true);

        assertThat(repository.existsBySessionId("sess_1")).isTrue();
    }
}
```

- [ ] **Step 4: 运行测试（预期失败）**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=ConversationPersistRepositoryTest -q 2>&1 | tail -20
```

Expected: 编译失败或测试失败（方法尚未实现）

- [ ] **Step 5: 实现 Step 2 中的四个方法后再次运行**

```bash
mvn test -pl . -Dtest=ConversationPersistRepositoryTest -q 2>&1 | tail -10
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 6: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepositoryTest.java
git commit -m "feat(visitor-session): 新增 findActiveByVisitorId/createAiChatSession/upgradeToWaiting/existsBySessionId"
```

---
### Task 3: 新增 VisitorSessionService + InitSessionResult + InitSessionRequest

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/dto/InitSessionResult.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/dto/InitSessionRequest.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorSessionService.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorSessionServiceTest.java`

**Interfaces:**
- Consumes:
  - `ConversationPersistRepository.findActiveByVisitorId(String)` → `Optional<ConversationEntity>` (Task 2)
  - `ConversationPersistRepository.createAiChatSession(...)` (Task 2)
  - `RedissonClient.getLock(String)` → `RLock`
- Produces:
  - `VisitorSessionService.getOrCreate(String anonymousId, String visitorName, String visitorIp, String visitorDevice)` → `InitSessionResult`
  - `record InitSessionResult(String sessionId, SessionStatus status, boolean isNew)`

- [ ] **Step 1: 新建 InitSessionResult**

```java
package com.aria.conversation.application.dto;

import com.aria.conversation.domain.SessionStatus;

/**
 * 会话初始化结果，由 {@link com.aria.conversation.application.service.VisitorSessionService} 返回。
 *
 * @param sessionId 会话唯一标识，前端后续所有请求均需携带
 * @param status    当前会话状态（AI_CHAT / WAITING / ACTIVE）
 * @param isNew     true 表示本次新建，false 表示恢复已有会话
 */
public record InitSessionResult(
        String sessionId,
        SessionStatus status,
        boolean isNew
) {}
```

- [ ] **Step 2: 新建 InitSessionRequest**

```java
package com.aria.conversation.application.dto;

/**
 * 会话初始化请求体，由 chat-widget 打开时传入。
 */
public class InitSessionRequest {

    /** 访客展示名称，可选，默认 "访客" */
    private String visitorName;

    public String getVisitorName() { return visitorName; }
    public void setVisitorName(String visitorName) { this.visitorName = visitorName; }
}
```

- [ ] **Step 3: 新建测试文件（失败态）**

新建 `src/test/java/com/aria/conversation/application/service/VisitorSessionServiceTest.java`：

```java
package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorSessionServiceTest {

    @Mock ConversationPersistRepository persistRepository;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    VisitorSessionService service;

    @BeforeEach
    void setUp() {
        service = new VisitorSessionService(persistRepository, redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        doNothing().when(rLock).lock(anyLong(), any(TimeUnit.class));
        doNothing().when(rLock).unlock();
    }

    @Test
    void getOrCreate_existingActiveSession_returnsExistingSession() {
        ConversationEntity existing = new ConversationEntity();
        existing.setSessionId("guest-existingsess");
        existing.setStatus(SessionStatus.AI_CHAT);
        when(persistRepository.findActiveByVisitorId("v_abc")).thenReturn(Optional.of(existing));

        InitSessionResult result = service.getOrCreate("v_abc", "张三", "1.2.3.4", "Mozilla/5.0");

        assertThat(result.sessionId()).isEqualTo("guest-existingsess");
        assertThat(result.status()).isEqualTo(SessionStatus.AI_CHAT);
        assertThat(result.isNew()).isFalse();
        verify(persistRepository, never()).createAiChatSession(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getOrCreate_noActiveSession_createsNewSession() {
        when(persistRepository.findActiveByVisitorId("v_new")).thenReturn(Optional.empty());

        InitSessionResult result = service.getOrCreate("v_new", null, "1.2.3.4", "Mozilla/5.0");

        assertThat(result.sessionId()).startsWith("guest-");
        assertThat(result.status()).isEqualTo(SessionStatus.AI_CHAT);
        assertThat(result.isNew()).isTrue();
        verify(persistRepository).createAiChatSession(
                eq(result.sessionId()), eq("v_new"), eq("访客"),
                eq("1.2.3.4"), eq("Mozilla/5.0"), any());
    }

    @Test
    void getOrCreate_invalidAnonymousId_tooShort_throwsBusinessException() {
        assertThatThrownBy(() -> service.getOrCreate("short", "访客", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getOrCreate_invalidAnonymousId_illegalChars_throwsBusinessException() {
        assertThatThrownBy(() -> service.getOrCreate("invalid id!", "访客", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getOrCreate_visitorNameNull_defaultsToGuestName() {
        when(persistRepository.findActiveByVisitorId("v_noname")).thenReturn(Optional.empty());

        InitSessionResult result = service.getOrCreate("v_noname", null, null, null);

        verify(persistRepository).createAiChatSession(
                any(), eq("v_noname"), eq("访客"), isNull(), isNull(), any());
    }
}
```

- [ ] **Step 4: 运行测试（预期编译失败）**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=VisitorSessionServiceTest -q 2>&1 | tail -10
```

Expected: 编译失败，`VisitorSessionService` 类不存在

- [ ] **Step 5: 新建 VisitorSessionService 实现**

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 访客会话初始化服务。
 *
 * <p>提供统一的"查询或创建"入口：同一 anonymousId 若有非 CLOSED 会话则直接返回，
 * 否则创建新的 AI_CHAT 会话。通过 Redisson 分布式锁防止并发重复创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorSessionService {

    /** anonymousId 校验正则：8-64 位字母数字下划线连字符 */
    private static final Pattern ANONYMOUS_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\-]{8,64}$");

    private static final String GUEST_SESSION_PREFIX = "guest-";
    private static final String LOCK_KEY_PREFIX      = "visitor:init:";
    private static final long   LOCK_TTL_SECONDS     = 3L;
    private static final String DEFAULT_VISITOR_NAME = "访客";

    private final ConversationPersistRepository persistRepository;
    private final RedissonClient                redissonClient;

    /**
     * 获取或创建访客会话（幂等，分布式锁保护）。
     *
     * <p>流程：校验 anonymousId → 加锁 → 查活跃会话 → 有则返回，无则新建。
     *
     * @param anonymousId  前端 localStorage UUID，格式 {@code ^[a-zA-Z0-9_\-]{8,64}$}
     * @param visitorName  访客展示名，null 时默认 "访客"
     * @param visitorIp    客户端 IP（X-Forwarded-For 首个或 RemoteAddr），可为 null
     * @param visitorDevice 原始 User-Agent 字符串，可为 null
     * @return 会话初始化结果，包含 sessionId、当前状态和是否新建标志
     * @throws BusinessException anonymousId 格式非法时抛出
     */
    public InitSessionResult getOrCreate(String anonymousId,
                                          String visitorName,
                                          String visitorIp,
                                          String visitorDevice) {
        validateAnonymousId(anonymousId);

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + anonymousId);
        lock.lock(LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        try {
            Optional<ConversationEntity> active = persistRepository.findActiveByVisitorId(anonymousId);
            if (active.isPresent()) {
                ConversationEntity e = active.get();
                log.debug("[VisitorSession] 恢复已有会话 anonymousId={} sessionId={} status={}",
                        anonymousId, e.getSessionId(), e.getStatus());
                return new InitSessionResult(e.getSessionId(), e.getStatus(), false);
            }

            String sessionId = GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
            String name = (visitorName != null && !visitorName.isBlank())
                    ? visitorName : DEFAULT_VISITOR_NAME;
            persistRepository.createAiChatSession(
                    sessionId, anonymousId, name, visitorIp, visitorDevice, OffsetDateTime.now());
            log.info("[VisitorSession] 新建会话 anonymousId={} sessionId={}", anonymousId, sessionId);
            return new InitSessionResult(sessionId, SessionStatus.AI_CHAT, true);
        } finally {
            lock.unlock();
        }
    }

    private void validateAnonymousId(String anonymousId) {
        if (anonymousId == null || !ANONYMOUS_ID_PATTERN.matcher(anonymousId).matches()) {
            throw new BusinessException("X-Anonymous-Id 格式非法，需满足 ^[a-zA-Z0-9_\\-]{8,64}$");
        }
    }
}
```

- [ ] **Step 6: 运行测试（预期通过）**

```bash
mvn test -pl . -Dtest=VisitorSessionServiceTest -q 2>&1 | tail -10
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/dto/InitSessionResult.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/dto/InitSessionRequest.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorSessionService.java
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorSessionServiceTest.java
git commit -m "feat(visitor-session): 新增 VisitorSessionService getOrCreate 逻辑"
```

---
### Task 4: 新增 ChatSessionController + InitSessionVO

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/vo/InitSessionVO.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/ChatSessionController.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/interfaces/rest/ChatSessionControllerTest.java`

**Interfaces:**
- Consumes:
  - `VisitorSessionService.getOrCreate(String, String, String, String)` → `InitSessionResult` (Task 3)
- Produces:
  - `POST /api/v1/chat/session/init` — 对外接口

- [ ] **Step 1: 新建 InitSessionVO**

```java
package com.aria.conversation.interfaces.rest.vo;

/**
 * 会话初始化响应 VO。
 *
 * @param sessionId 会话唯一标识，前端后续所有请求均需携带
 * @param status    当前会话状态字符串（AI_CHAT / WAITING / ACTIVE）
 * @param isNew     true 表示本次新建，false 表示恢复已有会话
 */
public record InitSessionVO(
        String sessionId,
        String status,
        boolean isNew
) {}
```

- [ ] **Step 2: 新建 ChatSessionController**

```java
package com.aria.conversation.interfaces.rest;

import com.aria.common.core.R;
import com.aria.conversation.application.dto.InitSessionRequest;
import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.application.service.VisitorSessionService;
import com.aria.conversation.interfaces.rest.vo.InitSessionVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 访客会话管理接口。
 *
 * <p>提供 chat-widget 打开时的会话初始化入口，是唯一的会话创建/恢复入口。
 * 允许跨域访问，因为 chat-widget 会嵌入第三方页面。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat/session")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatSessionController {

    /** X-Anonymous-Id Header 名称，前端 localStorage 生成的持久 UUID */
    private static final String HEADER_ANONYMOUS_ID = "X-Anonymous-Id";

    private final VisitorSessionService visitorSessionService;

    /**
     * 初始化访客会话：有活跃会话就恢复，没有就新建。
     *
     * <p>chat-widget 展开时调用，返回 sessionId 供后续所有接口使用。
     * 前端须在 localStorage 持久化 anonymousId，每次请求通过 {@code X-Anonymous-Id} Header 传入。
     *
     * @param anonymousId   X-Anonymous-Id Header（必传，格式 {@code ^[a-zA-Z0-9_\-]{8,64}$}）
     * @param request       HTTP 请求（用于提取 IP 和 User-Agent）
     * @param body          请求体（可选 visitorName）
     * @return 会话初始化结果（sessionId、status、isNew）
     */
    @PostMapping("/init")
    public R<InitSessionVO> init(
            @RequestHeader(HEADER_ANONYMOUS_ID) String anonymousId,
            HttpServletRequest request,
            @RequestBody(required = false) InitSessionRequest body) {

        String visitorIp     = resolveClientIp(request);
        String visitorDevice = request.getHeader("User-Agent");
        String visitorName   = body != null ? body.getVisitorName() : null;

        InitSessionResult result = visitorSessionService.getOrCreate(
                anonymousId, visitorName, visitorIp, visitorDevice);

        return R.ok(new InitSessionVO(result.sessionId(), result.status().name(), result.isNew()));
    }

    /**
     * 提取客户端真实 IP：优先取 X-Forwarded-For 首个地址，未经代理时取 RemoteAddr。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 3: 新建 Controller 测试**

```java
package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.application.service.VisitorSessionService;
import com.aria.conversation.domain.SessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatSessionController.class)
class ChatSessionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  VisitorSessionService visitorSessionService;

    @Test
    void init_validAnonymousId_returnsSessionId() throws Exception {
        when(visitorSessionService.getOrCreate(eq("test-anon-id-1234"), any(), any(), any()))
                .thenReturn(new InitSessionResult("guest-abc123", SessionStatus.AI_CHAT, true));

        mockMvc.perform(post("/api/v1/chat/session/init")
                        .header("X-Anonymous-Id", "test-anon-id-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("guest-abc123"))
                .andExpect(jsonPath("$.data.status").value("AI_CHAT"))
                .andExpect(jsonPath("$.data.isNew").value(true));
    }

    @Test
    void init_missingAnonymousIdHeader_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/chat/session/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void init_noRequestBody_still_works() throws Exception {
        when(visitorSessionService.getOrCreate(eq("test-anon-id-5678"), any(), any(), any()))
                .thenReturn(new InitSessionResult("guest-xyz789", SessionStatus.AI_CHAT, true));

        mockMvc.perform(post("/api/v1/chat/session/init")
                        .header("X-Anonymous-Id", "test-anon-id-5678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("guest-xyz789"));
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=ChatSessionControllerTest -q 2>&1 | tail -10
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/vo/InitSessionVO.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/ChatSessionController.java
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/interfaces/rest/ChatSessionControllerTest.java
git commit -m "feat(visitor-session): 新增 POST /api/v1/chat/session/init 接口"
```

---
### Task 5: MQ 消费端简化 — SESSION_START 改为 upgradeToWaiting

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/mq/ConversationMessageConsumer.java`

**Interfaces:**
- Consumes: `ConversationPersistRepository.upgradeToWaiting(...)` → `int` (Task 2)
- Consumes: `ConversationPersistRepository.startConversation(...)` — 待删除的旧方法

- [ ] **Step 1: 定位 handleSessionStart 方法**

打开 `ConversationMessageConsumer.java`，找到第 95-102 行的 `handleSessionStart` 方法：

```java
// 改造前
private void handleSessionStart(Map<String, Object> payload, String sessionId) {
    persistRepository.startConversation(
            sessionId,
            str(payload, ConversationStreamEvent.FIELD_VISITOR_NAME),
            str(payload, ConversationStreamEvent.FIELD_TRANSFER_REASON),
            str(payload, ConversationStreamEvent.FIELD_TAG),
            toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
}
```

- [ ] **Step 2: 将 handleSessionStart 改为调用 upgradeToWaiting**

```java
/**
 * 处理转人工事件：将 AI_CHAT 会话升级为 WAITING 状态。
 *
 * <p>新设计下转人工时会话一定已通过 /session/init 接口创建，
 * 因此直接 UPDATE，无需 insert 兜底。upgradeToWaiting 返回 0 时会打印 warn 日志。
 */
private void handleSessionStart(Map<String, Object> payload, String sessionId) {
    persistRepository.upgradeToWaiting(
            sessionId,
            str(payload, ConversationStreamEvent.FIELD_VISITOR_NAME),
            str(payload, ConversationStreamEvent.FIELD_TRANSFER_REASON),
            str(payload, ConversationStreamEvent.FIELD_TAG),
            toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
}
```

- [ ] **Step 3: 编译验证**

```bash
cd ai-conversation/conversation-service
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 删除 ConversationPersistRepository.startConversation 方法**

找到 `ConversationPersistRepository.java` 中的 `startConversation` 方法（第 51-85 行），整个方法删除。

再次编译确认无残留引用：

```bash
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS（如果有编译错误，说明还有其他地方调用了 startConversation，逐一修复）

- [ ] **Step 5: 运行全量测试**

```bash
mvn test -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS，无新增失败用例

- [ ] **Step 6: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/mq/ConversationMessageConsumer.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java
git commit -m "refactor(visitor-session): SESSION_START 消费端改为 upgradeToWaiting，删除 startConversation"
```

---
### Task 6: 移除 FaqChatAppService 和 SessionQueueService 中的隐式创建路径

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/FaqChatAppService.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java`（删除 initAiChatSession）
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/FaqChatAppServiceSessionTest.java`（新建）

**Interfaces:**
- Consumes: `ConversationPersistRepository.existsBySessionId(String)` → `boolean` (Task 2)

- [ ] **Step 1: 修改 FaqChatAppService.stream() — 删除 initAiChatSession，加存在性校验**

找到 `FaqChatAppService.java` 第 84-96 行的 `stream` 方法，将：

```java
public Flux<ChatEvent> stream(String sessionId, String message) {
    return Mono.fromCallable(() -> {
                sessionQueueService.initAiChatSession(sessionId);
                historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
                ...
```

改为（删除 `initAiChatSession` 调用，改成 `persistRepository` 存在性校验）：

```java
public Flux<ChatEvent> stream(String sessionId, String message) {
    return Mono.fromCallable(() -> {
                // 会话必须已通过 /session/init 接口创建，此处做防御性校验
                if (!persistRepository.existsBySessionId(sessionId)) {
                    log.warn("[FAQ] 会话不存在 sessionId={}，前端可能未调用 /session/init", sessionId);
                    throw new com.aria.common.core.exception.BusinessException("会话不存在，请刷新页面重试");
                }
                historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
                ...
```

同时在 `FaqChatAppService` 类的依赖注入字段中，将 `SessionQueueService sessionQueueService` 替换为 `ConversationPersistRepository persistRepository`（如果 `sessionQueueService` 只剩下 `enqueue` 调用则保留 sessionQueueService，仅去掉 initAiChatSession 的依赖）。

**注意**：检查 `FaqChatAppService` 中 `sessionQueueService` 是否还有其他用途（如 `enqueue` 调用），如果有则保留 `sessionQueueService`，只追加 `persistRepository` 注入即可。

- [ ] **Step 2: 新建 FaqChatAppService 会话校验测试**

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaqChatAppServiceSessionTest {

    @Mock ConversationPersistRepository persistRepository;
    @Mock ConversationHistoryRepository historyRepository;
    @Mock SessionQueueService sessionQueueService;
    // 其他依赖按需补 @Mock
    @InjectMocks FaqChatAppService service;

    @Test
    void stream_sessionNotExists_emitsErrorEvent() {
        when(persistRepository.existsBySessionId("sess_gone")).thenReturn(false);

        StepVerifier.create(service.stream("sess_gone", "你好"))
                .expectNextMatches(event -> "error".equals(event.eventType()))
                .verifyComplete();
    }

    @Test
    void stream_sessionExists_doesNotThrow() {
        when(persistRepository.existsBySessionId("sess_ok")).thenReturn(true);
        // 其余依赖 mock 按实际情况补充，此处只验证不抛异常
        // 完整流程测试在集成测试中覆盖
    }
}
```

- [ ] **Step 3: 删除 SessionQueueService.initAiChatSession 方法**

找到 `SessionQueueService.java` 第 196-198 行：

```java
public void initAiChatSession(String sessionId) {
    persistRepository.initAiChatSession(sessionId, OffsetDateTime.now());
}
```

整个方法删除。

- [ ] **Step 4: 删除 ConversationPersistRepository.initAiChatSession 方法**

找到 `ConversationPersistRepository.java` 第 128-145 行的 `initAiChatSession` 方法，整个方法删除。

- [ ] **Step 5: 编译 + 运行全量测试**

```bash
cd ai-conversation/conversation-service
mvn compile -q 2>&1 | tail -5
```

如有编译错误（残留 `initAiChatSession` 引用），逐一修复后再运行：

```bash
mvn test -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS，无新增失败用例

- [ ] **Step 6: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/FaqChatAppService.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/FaqChatAppServiceSessionTest.java
git commit -m "refactor(visitor-session): 移除 initAiChatSession 隐式创建路径，改为存在性校验"
```

---
### Task 7: 修复 VisitorHistoryService — 按 visitor_id 查历史

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorHistoryService.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java`（新增 getVisitorHistoryByVisitorId）
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/SessionQueueController.java`（参数 visitorName → visitorId）
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorHistoryServiceTest.java`（新建）

**Interfaces:**
- Consumes: `ConversationMapper.selectByVisitorId(String, String, int)` → `List<ConversationEntity>` (Task 2)
- Produces:
  - `VisitorHistoryService.getVisitorHistory(String visitorId, String excludeSessionId)` — 入参由 visitorName 改为 visitorId

- [ ] **Step 1: ConversationPersistRepository 新增 getVisitorHistoryByVisitorId**

在 `ConversationPersistRepository.java` 中，现有 `getVisitorHistory(visitorName, excludeSessionId, limit)` 方法**保留不动**（兼容存量调用），新增一个按 visitorId 查询的方法：

```java
/**
 * 按 visitor_id 查询历史会话列表（不含当前会话）。
 *
 * @param visitorId        访客唯一标识（X-Anonymous-Id）
 * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
 * @param limit            最大返回条数
 * @return 历史会话列表，按 started_at 倒序
 */
public List<ConversationEntity> getVisitorHistoryByVisitorId(String visitorId,
                                                              String excludeSessionId,
                                                              int limit) {
    return conversationMapper.selectByVisitorId(visitorId, excludeSessionId, limit);
}
```

- [ ] **Step 2: 修改 VisitorHistoryService.getVisitorHistory — 入参改为 visitorId**

将方法签名从 `getVisitorHistory(String visitorName, ...)` 改为 `getVisitorHistory(String visitorId, ...)`，内部调用 `getVisitorHistoryByVisitorId`：

```java
/**
 * 查询指定访客的历史会话列表（不含当前会话）。
 *
 * @param visitorId        访客唯一标识（X-Anonymous-Id），不能为空
 * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
 * @return 历史会话 DTO 列表，按 startedAt 倒序，最多 {@value #VISITOR_HISTORY_LIMIT} 条
 */
public List<VisitorHistoryDTO> getVisitorHistory(String visitorId, String excludeSessionId) {
    List<ConversationEntity> entities =
            persistRepository.getVisitorHistoryByVisitorId(visitorId, excludeSessionId, VISITOR_HISTORY_LIMIT);
    if (entities.isEmpty()) {
        return Collections.emptyList();
    }
    // 其余批量查消息计数 + Redis 摘要逻辑保持不变
    ...
}
```

（将方法体内 visitorName 变量名替换为 visitorId，逻辑不变）

- [ ] **Step 3: 修改 SessionQueueController.getVisitorHistory — 参数改为 visitorId**

找到 `SessionQueueController.java` 第 218-228 行：

```java
// 改造前
public R<List<VisitorHistoryVO>> getVisitorHistory(
        @RequestParam @NotBlank(message = "visitorName 不能为空") @Size(max = 128) String visitorName,
        @RequestParam(required = false) String excludeSessionId) {
    List<VisitorHistoryDTO> dtos = visitorHistoryService.getVisitorHistory(visitorName, excludeSessionId);
```

改为：

```java
// 改造后
public R<List<VisitorHistoryVO>> getVisitorHistory(
        @RequestHeader("X-Anonymous-Id") @NotBlank(message = "X-Anonymous-Id 不能为空")
        @Size(max = 64) String visitorId,
        @RequestParam(required = false) String excludeSessionId) {
    List<VisitorHistoryDTO> dtos = visitorHistoryService.getVisitorHistory(visitorId, excludeSessionId);
```

**注意**：这是一个破坏性 API 变更，需前端同步从 query param `visitorName` 改为 Header `X-Anonymous-Id`。如需兼容旧前端，可临时保留 `visitorName` 参数且当 visitorId 缺失时返回空列表。

- [ ] **Step 4: 新建 VisitorHistoryService 测试**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.VisitorHistoryDTO;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorHistoryServiceTest {

    @Mock ConversationPersistRepository persistRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks VisitorHistoryService service;

    @Test
    void getVisitorHistory_hasHistory_returnsDtoList() {
        ConversationEntity e = new ConversationEntity();
        e.setSessionId("sess_old");
        e.setStatus(SessionStatus.CLOSED);
        e.setTag("咨询");
        e.setStartedAt(OffsetDateTime.now().minusDays(1));
        when(persistRepository.getVisitorHistoryByVisitorId("v_abc", "sess_cur", 20))
                .thenReturn(List.of(e));
        when(persistRepository.batchGetMessageCount(List.of("sess_old")))
                .thenReturn(java.util.Map.of("sess_old", 5L));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyList())).thenReturn(List.of("AI 摘要内容"));

        List<VisitorHistoryDTO> result = service.getVisitorHistory("v_abc", "sess_cur");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sessionId()).isEqualTo("sess_old");
        assertThat(result.get(0).messageCount()).isEqualTo(5);
    }

    @Test
    void getVisitorHistory_noHistory_returnsEmptyList() {
        when(persistRepository.getVisitorHistoryByVisitorId("v_new", null, 20))
                .thenReturn(List.of());

        assertThat(service.getVisitorHistory("v_new", null)).isEmpty();
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=VisitorHistoryServiceTest -q 2>&1 | tail -10
```

Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 6: 运行全量测试**

```bash
mvn test -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorHistoryService.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/ConversationPersistRepository.java
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/SessionQueueController.java
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorHistoryServiceTest.java
git commit -m "fix(visitor-session): VisitorHistoryService 改按 visitor_id 查历史，修复按 visitorName 查询的 bug"
```

---
### Task 8: ChatController — resolveSessionId 去掉自动生成

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/ChatController.java`

**Interfaces:**
- 此 Task 是纯清理，无新增接口

**注意：此改动为破坏性变更，必须在 chat-widget 前端完成接入 /session/init 并全量上线后再执行。滚动上线期间（前端正在灰度）可跳过此 Task。**

- [ ] **Step 1: 修改 resolveSessionId — 去掉自动生成逻辑**

找到 `ChatController.java` 第 205-210 行：

```java
// 改造前
private String resolveSessionId(String sessionId) {
    if (sessionId == null) {
        return GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
    return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
}
```

改为：

```java
/**
 * 校验 sessionId 格式。
 *
 * <p>sessionId 为必传，前端须通过 {@code POST /api/v1/chat/session/init} 获取后携带。
 * null 或格式非法时返回 null，调用方负责返回错误响应。
 *
 * @param sessionId 前端传入的会话 ID
 * @return 合法的 sessionId，或 null（表示非法）
 */
private String resolveSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
        return null;
    }
    return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
}
```

同时检查 `GUEST_SESSION_PREFIX` 常量是否还有其他地方引用——若无其他引用则一并删除（该常量已移至 `VisitorSessionService`）。

- [ ] **Step 2: 编译 + 运行全量测试**

```bash
cd ai-conversation/conversation-service
mvn compile -q && mvn test -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS，无新增失败用例

- [ ] **Step 3: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/ChatController.java
git commit -m "refactor(visitor-session): resolveSessionId 去掉 sessionId 自动生成，改为必传"
```

---

### Task 9: 全量验证与收尾

**Files:**
- 无新增文件，验证所有已有测试通过

- [ ] **Step 1: 运行全量测试**

```bash
cd ai-conversation/conversation-service
mvn test 2>&1 | tail -20
```

Expected: BUILD SUCCESS，重点关注：
- `ConversationPersistRepositoryTest` — 5 tests
- `VisitorSessionServiceTest` — 5 tests
- `ChatSessionControllerTest` — 3 tests
- `VisitorHistoryServiceTest` — 2 tests
- `FaqChatAppServiceSessionTest` — 1 test
- 原有全部测试无退化

- [ ] **Step 2: 验证新接口可达**

启动服务后（本地或 dev 环境），用 curl 验证：

```bash
# 正常流程：新建会话
curl -s -X POST http://localhost:8080/api/v1/chat/session/init \
  -H "X-Anonymous-Id: test-visitor-uuid-1234" \
  -H "Content-Type: application/json" \
  -d '{"visitorName":"测试访客"}' | jq .

# 预期响应：
# { "code": 0, "data": { "sessionId": "guest-xxx", "status": "AI_CHAT", "isNew": true } }

# 幂等验证：再次调用，应返回相同 sessionId，isNew=false
curl -s -X POST http://localhost:8080/api/v1/chat/session/init \
  -H "X-Anonymous-Id: test-visitor-uuid-1234" \
  -H "Content-Type: application/json" \
  -d '{}' | jq .

# 预期响应：
# { "code": 0, "data": { "sessionId": "guest-xxx", "status": "AI_CHAT", "isNew": false } }

# 异常：缺少 Header
curl -s -X POST http://localhost:8080/api/v1/chat/session/init \
  -H "Content-Type: application/json" \
  -d '{}' | jq .
# 预期：HTTP 400
```

- [ ] **Step 3: 验证转人工 MQ 链路**

在 dev 环境发起一次完整的"AI 对话 → 转人工"流程：

1. 调用 `/session/init` 拿到 sessionId（`isNew=true`）
2. 调用 `/stream` 发消息，确认 AI 正常回复（不再隐式创建会话）
3. 调用 `/transfer` 发起转人工
4. 查询 DB：`SELECT session_id, status, visitor_id, visitor_ip FROM cs_conversation.cs_conversation WHERE session_id = '<sessionId>'`
   - 预期：`status = 'WAITING'`，`visitor_id` 非空，`visitor_ip` 非空
5. 查看服务日志：不应出现 `upgradeToWaiting 影响 0 行` 的 warn

- [ ] **Step 4: 最终 Commit（若有遗漏文件）**

```bash
git status
# 确认无遗留未提交文件
```

---
