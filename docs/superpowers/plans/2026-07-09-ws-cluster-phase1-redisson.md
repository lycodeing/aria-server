# WebSocket 集群改造 阶段 1：Redisson 锁替换 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 knowledge-service 的两处 SETNX 分布式锁替换为 Redisson RLock，修复 PersistHandler 的 TTL 数据安全问题，为后续多 Pod 部署做准备。

**Architecture:** 引入 `redisson-spring-boot-starter` 依赖，Redisson 自动读取现有 `spring.data.redis` 配置，无需额外配置文件。`PersistHandler` 使用 watchdog 模式（不传 leaseTime），Redisson 每 10s 自动续期，彻底消除大文档写入超时导致的锁过期数据损坏风险。`DocExpiryScheduler` 使用固定 leaseTime=10min，与原有 TTL 语义一致。

**Tech Stack:** Java 21、Spring Boot、Redisson 3.27.2（redisson-spring-boot-starter）、JUnit 5 + Mockito

## Global Constraints

- 模块：`ai-knowledge/knowledge-service`
- 基础源码目录：`ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/`
- 基础测试目录：`ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/`
- 编译命令：`mvn -f ai-knowledge/knowledge-service/pom.xml compile -q`
- 测试命令：`mvn -f ai-knowledge/knowledge-service/pom.xml test -Dtest=<ClassName> -q`
- Redisson 版本：`3.27.2`（固定版本，不使用范围）
- 日志格式：`[前缀] 说明 key={}` 占位符风格
- `@RequiredArgsConstructor` + `final` 字段注入，禁用 `@Autowired`
- 不修改 `RedisLockHelper`（其他模块仍在使用）

## 文件改动总览

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `ai-knowledge/knowledge-service/pom.xml` | 新增 Redisson 依赖 |
| 修改 | `infrastructure/scheduler/DocExpiryScheduler.java` | 换用 Redisson RLock，固定 leaseTime=10min |
| 修改 | `infrastructure/mq/handler/PersistHandler.java` | 换用 Redisson RLock watchdog 模式 |
| 新增 | `src/test/.../scheduler/DocExpirySchedulerLockTest.java` | 验证锁获取失败时跳过执行 |
| 新增 | `src/test/.../mq/handler/PersistHandlerLockTest.java` | 验证锁获取失败时抛异常触发重试 |

---

### Task 1: 引入 Redisson 依赖

**Files:**
- Modify: `ai-knowledge/knowledge-service/pom.xml`

**Interfaces:**
- Produces: `RedissonClient` Bean 可注入（由 `redisson-spring-boot-starter` 自动配置）

- [ ] **Step 1: 在 pom.xml 中添加 Redisson 依赖**

在 `<dependencies>` 内找到 `spring-boot-starter-data-redis` 依赖之后插入：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

- [ ] **Step 2: 编译验证**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml compile -q
```

期望：无报错，`RedissonClient` 类可解析

- [ ] **Step 3: Commit**

```bash
git add ai-knowledge/knowledge-service/pom.xml
git commit -m "feat(knowledge): 引入 redisson-spring-boot-starter 3.27.2"
```

---

### Task 2: DocExpiryScheduler 换用 Redisson RLock

**Files:**
- Modify: `ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/scheduler/DocExpiryScheduler.java`
- Test: `ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/scheduler/DocExpirySchedulerLockTest.java`

**Interfaces:**
- Consumes: `RedissonClient`（Task 1 引入）
- Produces: `DocExpiryScheduler` 构造器签名变更：移除 `RedisLockHelper`，新增 `RedissonClient`

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/knowledge/infrastructure/scheduler/DocExpirySchedulerLockTest.java
package com.aria.knowledge.infrastructure.scheduler;

import com.aria.knowledge.application.service.DocExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocExpiryScheduler 分布式锁行为")
class DocExpirySchedulerLockTest {

    @Mock DocExpiryService docExpiryService;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    @Test
    @DisplayName("锁获取失败时跳过执行，不调用 docExpiryService")
    void lock_not_acquired_skips_execution() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:doc-expiry")).thenReturn(rLock);
        when(rLock.tryLock(0, 10, TimeUnit.MINUTES)).thenReturn(false);

        DocExpiryScheduler scheduler = new DocExpiryScheduler(docExpiryService, redissonClient);
        scheduler.deprecateExpiredDocs();

        verifyNoInteractions(docExpiryService);
    }

    @Test
    @DisplayName("锁获取成功时执行清理，完成后释放锁")
    void lock_acquired_executes_and_unlocks() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:doc-expiry")).thenReturn(rLock);
        when(rLock.tryLock(0, 10, TimeUnit.MINUTES)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        DocExpiryScheduler scheduler = new DocExpiryScheduler(docExpiryService, redissonClient);
        scheduler.deprecateExpiredDocs();

        verify(docExpiryService).deprecateExpired(any());
        verify(rLock).unlock();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml test \
    -Dtest=DocExpirySchedulerLockTest -q 2>&1 | tail -10
```

期望：编译失败或 `DocExpiryScheduler` 构造器不匹配

- [ ] **Step 3: 改造 DocExpiryScheduler**

```java
package com.aria.knowledge.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 文档过期清理定时任务。
 * 每天凌晨 02:00 执行，业务逻辑全部委托 {@link DocExpiryService}。
 *
 * <p>多实例保护：使用 Redisson RLock（固定 leaseTime=10min）保证同一时刻只有一个 Pod 执行。
 * 与 watchdog 模式不同，定时任务有明确的预期执行时间上界，使用固定 leaseTime 更合适。
 *
 * <p>可通过配置 {@code knowledge.expiry.scheduler.enabled=false} 在开发/测试环境关闭此任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name        = "knowledge.expiry.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DocExpiryScheduler {

    private static final String LOCK_KEY = "lock:scheduler:doc-expiry";

    private final DocExpiryService docExpiryService;
    private final RedissonClient   redissonClient;

    /**
     * 每天凌晨 02:00 执行过期文档下线清理。
     * 锁获取失败说明其他实例正在执行，直接跳过。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void deprecateExpiredDocs() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired;
        try {
            // waitTime=0：不等待，立即返回；leaseTime=10min：固定持有时长，非 watchdog 模式
            acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DocExpiryScheduler] 锁等待被中断，跳过本次执行");
            return;
        }
        if (!acquired) {
            log.debug("[DocExpiryScheduler] 其他实例正在执行过期清理，跳过本次触发");
            return;
        }
        try {
            log.info("[DocExpiryScheduler] 开始执行文档过期清理 date={}", LocalDate.now());
            docExpiryService.deprecateExpired(LocalDate.now());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml test \
    -Dtest=DocExpirySchedulerLockTest -q 2>&1 | tail -10
```

期望：`BUILD SUCCESS`，2 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/scheduler/DocExpiryScheduler.java \
        ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/scheduler/DocExpirySchedulerLockTest.java
git commit -m "feat(knowledge): DocExpiryScheduler 换用 Redisson RLock，固定 leaseTime=10min"
```

---

### Task 3: PersistHandler 换用 Redisson RLock watchdog 模式

**Files:**
- Modify: `ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandler.java`
- Test: `ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandlerLockTest.java`

**Interfaces:**
- Consumes: `RedissonClient`（Task 1 引入）
- Produces: `PersistHandler` 构造器签名变更：移除 `RedisLockHelper`，新增 `RedissonClient`

- [ ] **Step 1: 写失败测试**

```java
// test/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandlerLockTest.java
package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.DocIngestEvent;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersistHandler 分布式锁行为")
class PersistHandlerLockTest {

    @Mock KnowledgeChunkRepository chunkRepository;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    private IngestContext buildCtx(String docId) {
        DocIngestEvent event = mock(DocIngestEvent.class);
        when(event.getDocId()).thenReturn(docId);
        IngestContext ctx = mock(IngestContext.class);
        when(ctx.getEvent()).thenReturn(event);
        when(ctx.getChunks()).thenReturn(List.of());
        return ctx;
    }

    @Test
    @DisplayName("锁获取失败时抛 IllegalStateException，触发 MQ nack 重试")
    void lock_not_acquired_throws_for_nack() throws InterruptedException {
        when(redissonClient.getLock(startsWith("lock:ingest:persist:"))).thenReturn(rLock);
        // watchdog 模式：只传 waitTime，不传 leaseTime
        when(rLock.tryLock(10, TimeUnit.SECONDS)).thenReturn(false);

        PersistHandler handler = new PersistHandler(chunkRepository, redissonClient);
        assertThatThrownBy(() -> handler.handle(buildCtx("doc-001")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doc-001");

        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("锁获取成功时执行 delete+insert，完成后释放锁")
    void lock_acquired_persists_and_unlocks() throws InterruptedException {
        when(redissonClient.getLock(startsWith("lock:ingest:persist:"))).thenReturn(rLock);
        when(rLock.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        PersistHandler handler = new PersistHandler(chunkRepository, redissonClient);
        handler.handle(buildCtx("doc-002"));

        verify(chunkRepository).deleteByDocId("doc-002");
        verify(chunkRepository).saveAll(any());
        verify(rLock).unlock();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml test \
    -Dtest=PersistHandlerLockTest -q 2>&1 | tail -10
```

期望：编译失败或构造器不匹配

- [ ] **Step 3: 改造 PersistHandler**

```java
package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 责任链 Step 7：幂等写入向量库。
 * 先删除该文档的旧 chunk，再批量插入新 chunk，保证 MQ 重试场景下数据一致性。
 *
 * <p>并发保护：使用 Redisson RLock（watchdog 模式）防止同一文档多个消费者并发 delete+insert 互相覆盖。
 * watchdog 模式（不传 leaseTime）下 Redisson 每 10s 自动续期，彻底消除大文档写入超过 TTL 导致锁提前
 * 释放、另一消费者并发写入造成数据损坏的风险（旧实现 SETNX TTL=5min 存在此问题）。
 *
 * <p>锁竞争失败时抛出异常，Spring AMQP 自动 nack 并重试，让持有锁的消费者完成后再处理。
 */
@Slf4j
@Order(8)
@Component
@RequiredArgsConstructor
public class PersistHandler implements IngestHandler {

    private static final String LOCK_PREFIX   = "lock:ingest:persist:";
    private static final int    LOCK_WAIT_SEC = 10;   // 最多等待 10s 获取锁

    private final KnowledgeChunkRepository chunkRepository;
    private final RedissonClient           redissonClient;

    @Override
    public void handle(IngestContext ctx) {
        String docId   = ctx.getEvent().getDocId();
        String lockKey = LOCK_PREFIX + docId;

        // watchdog 模式：不传 leaseTime，Redisson 每 lockWatchdogTimeout/3（默认 10s）自动续期，
        // 持有锁直到主动 unlock()，彻底消除 TTL 过期竞态
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文档持久化锁等待被中断: " + docId, e);
        }

        if (!acquired) {
            log.warn("[持久化] 获取分布式锁失败，等待重试 docId={}", docId);
            throw new IllegalStateException("文档持久化锁竞争失败，等待重试: " + docId);
        }

        try {
            // 幂等：先删旧 chunk，再写新 chunk，避免 MQ 重试导致重复数据
            chunkRepository.deleteByDocId(docId);
            chunkRepository.saveAll(ctx.getChunks());
            log.info("[持久化] docId={} 写入 chunk 数={}", docId, ctx.getChunks().size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml test \
    -Dtest=PersistHandlerLockTest -q 2>&1 | tail -10
```

期望：`BUILD SUCCESS`，2 tests passed

- [ ] **Step 5: Commit**

```bash
git add ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandler.java \
        ai-knowledge/knowledge-service/src/test/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandlerLockTest.java
git commit -m "feat(knowledge): PersistHandler 换用 Redisson RLock watchdog 模式，修复 TTL 数据安全问题"
```

---

### Task 4: 全量测试验证

**Files:**
- No new files

- [ ] **Step 1: 运行全模块测试**

```bash
mvn -f ai-knowledge/knowledge-service/pom.xml test -q 2>&1 | tail -15
```

期望：`BUILD SUCCESS`，所有测试通过

- [ ] **Step 2: 验证旧锁依赖已清除**

```bash
grep -rn "RedisLockHelper" \
    ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/scheduler/ \
    ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/infrastructure/mq/handler/PersistHandler.java \
    2>/dev/null
```

期望：无输出（两个文件已不依赖 RedisLockHelper）

- [ ] **Step 3: 最终 Commit**

```bash
git add -A
git commit -m "test(knowledge): 全量测试验证通过，阶段1 Redisson 锁替换完成"
```
