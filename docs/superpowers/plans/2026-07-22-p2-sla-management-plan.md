# P2 SLA 管理 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为客服系统添加 SLA 策略管理，支持按访客标签/转人工标签分层策略、WARNING/BREACH 两阶段预警、多 Pod 分片扫描、SSE 实时告警和自动升级。

**Architecture:** 新增 `cs_sla_policy`/`cs_sla_breach` 两表；domain 层定义 `BreachCandidate` 值对象、`BreachType`/`BreachStage` 枚举、`SlaPolicyMatcher` 领域服务；infrastructure 层 `SlaBreachEvaluator`（通过 `IBusinessHoursCalculator` 接口计算业务时间）、`SlaBreachRecorder`（三维幂等写入）、`SlaBreachNotifier`（SSE + 领域事件）、`SlaBreachScanScheduler`（CRC32 分片 + Redisson tryLock，now 在分片入口统一捕获）。

**Tech Stack:** Spring Boot, MyBatis-Plus, Redisson, RabbitMQ fanout, Sa-Token, Lombok, JUnit 5 + Mockito

## Global Constraints

- Domain 层放 `com.aria.conversation.domain`（model/ 和 service/），不依赖 infrastructure
- `BreachCandidate` 和 `SlaEscalationRequestedEvent` 放 `com.aria.conversation.domain.model`（含 `event/` 子包）
- `IBusinessHoursCalculator` 接口已在 P0 中创建于 `com.aria.conversation.domain.service`，本计划直接使用
- Infrastructure scheduler 组件放 `com.aria.conversation.infrastructure.scheduler`
- Application Service 放 `com.aria.conversation.application.service`，`@Slf4j @Service @RequiredArgsConstructor`
- 写方法加 `@Transactional(rollbackFor = Exception.class)`
- 业务异常抛 `BusinessException`，error code 常量 `private static final int`
- 所有新表用 `create_time`/`update_time`（阿里规范）
- Schema 变更追加到 `docs/sql/conversation-service-schema.sql`
- 测试用 `@ExtendWith(MockitoExtension.class)` + AssertJ
- `SessionEventType` 枚举在 `com.aria.conversation.domain.SessionEventType`（本计划追加 `SLA_BREACH`）

---

## Task 1: DB Schema — SLA 表

**Files:**
- Modify: `docs/sql/conversation-service-schema.sql`

**Interfaces:**
- Produces:
  - `cs_sla_policy(id, name, is_enabled, priority, match_visitor_tags, match_transfer_tags, time_mode, wait_time_target_sec, frt_target_sec, handle_time_target_sec, warning_threshold_pct, actions, create_time, update_time)`
  - `cs_sla_breach(id, session_id, policy_id, breach_type, stage, target_sec, warn_at_sec, actual_sec, breach_at, alerted_at, escalated_at, create_time)`

- [ ] **Step 1: 追加 DDL**

打开 `docs/sql/conversation-service-schema.sql`，在文件末尾追加：

```sql
-- SLA 策略
CREATE TABLE IF NOT EXISTS `cs_sla_policy` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name`                   VARCHAR(50)  NOT NULL                    COMMENT '策略名称',
    `is_enabled`             TINYINT(1)   NOT NULL DEFAULT 1          COMMENT '是否启用',
    `priority`               INT          NOT NULL DEFAULT 0          COMMENT '优先级，越大越优先；同优先级按 id ASC',
    `match_visitor_tags`     JSON                                     COMMENT '访客标签白名单，空=不限',
    `match_transfer_tags`    JSON                                     COMMENT '转人工原因标签，空=不限',
    `time_mode`              VARCHAR(15)  NOT NULL DEFAULT 'CALENDAR' COMMENT 'CALENDAR | BUSINESS_HOURS',
    `wait_time_target_sec`   INT          NOT NULL DEFAULT 120        COMMENT '排队等待超时（秒）',
    `frt_target_sec`         INT          NOT NULL DEFAULT 60         COMMENT '首次响应超时（秒）',
    `handle_time_target_sec` INT          NOT NULL DEFAULT 1800       COMMENT '处理总时长超时（秒）',
    `warning_threshold_pct`  TINYINT      NOT NULL DEFAULT 80         COMMENT '预警百分比阈值（建议 targetSec <= 86400）',
    `actions`                JSON         NOT NULL                    COMMENT '违规行为配置',
    `create_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_priority` (`is_enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 策略';

-- SLA 违规记录
CREATE TABLE IF NOT EXISTS `cs_sla_breach` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id`   VARCHAR(64)  NOT NULL COMMENT '关联 sessionId',
    `policy_id`    BIGINT       NOT NULL COMMENT '触发策略 ID（快照）',
    `breach_type`  VARCHAR(10)  NOT NULL COMMENT 'WAIT | FRT | HANDLE',
    `stage`        VARCHAR(10)  NOT NULL DEFAULT 'BREACH' COMMENT 'WARNING | BREACH',
    `target_sec`   INT          NOT NULL COMMENT '阈值快照（秒）',
    `warn_at_sec`  INT          NOT NULL COMMENT '预警阈值快照（秒）',
    `actual_sec`   INT          NOT NULL COMMENT '检测时实际耗时（秒）',
    `breach_at`    DATETIME     NOT NULL COMMENT '记录时间',
    `alerted_at`   DATETIME              COMMENT 'SSE 告警时间',
    `escalated_at` DATETIME              COMMENT '自动升级时间（WARNING 阶段不填）',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_breach_at`  (`breach_at`),
    UNIQUE KEY `uk_session_type_stage` (`session_id`, `breach_type`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 违规记录';
```

- [ ] **Step 2: 追加默认策略数据**

打开 `docs/sql/conversation-service-data.sql`，追加：

```sql
-- 默认 SLA 兜底策略（priority=0，匹配所有会话）
INSERT IGNORE INTO `cs_sla_policy`
    (`name`, `is_enabled`, `priority`, `match_visitor_tags`, `match_transfer_tags`,
     `time_mode`, `wait_time_target_sec`, `frt_target_sec`, `handle_time_target_sec`,
     `warning_threshold_pct`, `actions`)
VALUES ('默认 SLA', 1, 0, '[]', '[]', 'CALENDAR', 120, 60, 1800, 80,
    '{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false,"escalateToUserId":null}');
```

- [ ] **Step 3: 在本地数据库执行并验证**

```bash
# 执行 DDL
mysql -u root -p cs_conversation -e "SHOW TABLES LIKE 'cs_sla%';"
# 期望：cs_sla_breach, cs_sla_policy

# 执行数据
mysql -u root -p cs_conversation -e "SELECT * FROM cs_sla_policy;"
# 期望：1 条默认策略
```

- [ ] **Step 4: Commit**

```bash
git add docs/sql/
git commit -m "feat(sla): add cs_sla_policy and cs_sla_breach tables"
```

---

## Task 2: Domain 层 — 枚举 + 值对象 + SlaPolicyMatcher

**Files:**
- Create: `...domain/model/BreachType.java`
- Create: `...domain/model/BreachStage.java`
- Create: `...domain/model/BreachCandidate.java`
- Create: `...domain/model/SlaBreachActions.java`
- Create: `...domain/model/event/SlaEscalationRequestedEvent.java`
- Create: `...domain/service/SlaPolicyMatcher.java`
- Create: `...domain/service/SlaPolicyRepository.java`（接口）
- Create: `...infrastructure/persistence/entity/SlaPolicyEntity.java`
- Create: `...infrastructure/persistence/entity/SlaBreachEntity.java`
- Create: `...infrastructure/persistence/mapper/SlaPolicyMapper.java`
- Create: `...infrastructure/persistence/mapper/SlaBreachMapper.java`
- Create: `...infrastructure/cache/SlaPolicyCache.java`
- Create: `...infrastructure/repository/SlaPolicyRepositoryImpl.java`

**Interfaces:**
- Produces:
  - `BreachType` enum: `WAIT, FRT, HANDLE`
  - `BreachStage` enum: `WARNING, BREACH`
  - `BreachCandidate(sessionId, type, stage, targetSec, warnAtSec, actualSec, detectedAt)`
  - `SlaBreachActions(recordBreachOnly, sseAlert, autoEscalate, escalateToUserId)`
  - `SlaEscalationRequestedEvent(sessionId, targetAgentId, breachIds)`
  - `SlaPolicyMatcher.findPolicy(ConversationEntity session): SlaPolicyEntity`
  - `SlaPolicyCache.getAllEnabled(): List<SlaPolicyEntity>`
  - `SlaPolicyCache.evict(): void`

- [ ] **Step 1: 创建 domain 枚举**

```java
// BreachType.java
package com.aria.conversation.domain.model;
public enum BreachType { WAIT, FRT, HANDLE }

// BreachStage.java
package com.aria.conversation.domain.model;
public enum BreachStage { WARNING, BREACH }
```

- [ ] **Step 2: 创建 BreachCandidate 值对象**

```java
package com.aria.conversation.domain.model;

import java.time.OffsetDateTime;

/**
 * SLA 违规候选值对象（domain 层）。
 * 由 SlaBreachEvaluator 计算产生，通过 SlaBreachRecorder 持久化，
 * 不包含任何 I/O 操作。
 */
public record BreachCandidate(
        String sessionId,
        BreachType type,
        BreachStage stage,
        int targetSec,
        int warnAtSec,    // = targetSec × warningThresholdPct / 100
        int actualSec,
        OffsetDateTime detectedAt
) {}
```

- [ ] **Step 3: 创建 SlaBreachActions**

```java
package com.aria.conversation.domain.model;

import lombok.Data;

/**
 * SLA 违规行为配置（对应 cs_sla_policy.actions JSON 字段）。
 * 使用 JacksonTypeHandler 反序列化。
 */
@Data
public class SlaBreachActions {
    private boolean recordBreachOnly = true;
    private boolean sseAlert         = true;
    private boolean autoEscalate     = false;
    private String  escalateToUserId;
}
```

- [ ] **Step 4: 创建领域事件**

```java
package com.aria.conversation.domain.model.event;

import java.util.List;

/**
 * SLA 自动升级请求事件（domain 层）。
 * 由 SlaBreachNotifier（infrastructure）发布，SlaEscalationHandler（application）订阅。
 * 存放在 domain 层，供两层无层次违规地引用。
 */
public record SlaEscalationRequestedEvent(
        String sessionId,
        String targetAgentId,
        List<Long> breachIds   // 对应 cs_sla_breach.id，升级成功后写 escalated_at
) {}
```

- [ ] **Step 5: 创建 Entity**

```java
// SlaPolicyEntity.java
package com.aria.conversation.infrastructure.persistence.entity;

import com.aria.conversation.domain.model.SlaBreachActions;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(schema = "cs_conversation", value = "cs_sla_policy", autoResultMap = true)
public class SlaPolicyEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String  name;
    private Boolean isEnabled;
    private Integer priority;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> matchVisitorTags;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> matchTransferTags;

    private String  timeMode;
    private Integer waitTimeTargetSec;
    private Integer frtTargetSec;
    private Integer handleTimeTargetSec;
    private Integer warningThresholdPct;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private SlaBreachActions actions;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

```java
// SlaBreachEntity.java
@Data
@Builder
@TableName(schema = "cs_conversation", value = "cs_sla_breach")
public class SlaBreachEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String   sessionId;
    private Long     policyId;
    private String   breachType;   // WAIT | FRT | HANDLE
    private String   stage;        // WARNING | BREACH
    private Integer  targetSec;
    private Integer  warnAtSec;
    private Integer  actualSec;
    private java.time.OffsetDateTime breachAt;
    private java.time.OffsetDateTime alertedAt;
    private java.time.OffsetDateTime escalatedAt;
    private java.time.LocalDateTime  createTime;
}
```

- [ ] **Step 6: 创建 Mapper**

```java
// SlaPolicyMapper.java
@Mapper
public interface SlaPolicyMapper extends BaseMapper<SlaPolicyEntity> {
    default List<SlaPolicyEntity> selectAllEnabled() {
        return selectList(Wrappers.<SlaPolicyEntity>lambdaQuery()
                .eq(SlaPolicyEntity::getIsEnabled, true)
                .orderByDesc(SlaPolicyEntity::getPriority)
                .orderByAsc(SlaPolicyEntity::getId));
    }
}

// SlaBreachMapper.java
@Mapper
public interface SlaBreachMapper extends BaseMapper<SlaBreachEntity> {
    default boolean existsBySessionTypeAndStage(String sessionId,
                                                 BreachType type,
                                                 BreachStage stage) {
        return exists(Wrappers.<SlaBreachEntity>lambdaQuery()
                .eq(SlaBreachEntity::getSessionId, sessionId)
                .eq(SlaBreachEntity::getBreachType, type.name())
                .eq(SlaBreachEntity::getStage, stage.name()));
    }

    @Update("UPDATE cs_conversation.cs_sla_breach SET alerted_at = #{at} WHERE id = #{id}")
    void updateAlertedAt(@Param("id") Long id, @Param("at") java.time.OffsetDateTime at);

    @Update("UPDATE cs_conversation.cs_sla_breach SET escalated_at = #{at} WHERE id = #{id}")
    void updateEscalatedAt(@Param("id") Long id, @Param("at") java.time.OffsetDateTime at);
}
```

- [ ] **Step 7: 创建 SlaPolicyCache + SlaPolicyMatcher**

```java
// SlaPolicyCache.java（infrastructure/cache）
@Component
@RequiredArgsConstructor
public class SlaPolicyCache {
    private static final String KEY = "sla:policies:enabled";
    // 使用 Caffeine 或 Redis 缓存，这里用 Spring Cache 简化
    private final SlaPolicyMapper slaPolicyMapper;

    @org.springframework.cache.annotation.Cacheable(value = "sla_policies_enabled", unless = "#result.isEmpty()")
    public List<SlaPolicyEntity> getAllEnabled() {
        return slaPolicyMapper.selectAllEnabled();
    }

    @org.springframework.cache.annotation.CacheEvict(value = "sla_policies_enabled", allEntries = true)
    public void evict() {}
}
```

```java
// SlaPolicyMatcher.java（domain/service）
@Component
@RequiredArgsConstructor
public class SlaPolicyMatcher {

    private final SlaPolicyCache slaPolicyCache;

    /**
     * 按优先级匹配会话对应的 SLA 策略。
     * 两个匹配条件同时满足才命中：
     *   ① matchVisitorTags 为空 OR 访客至少有一个标签在列表中
     *   ② matchTransferTags 为空 OR session.tag 在列表中
     *
     * @return 第一个命中的策略，null 表示不受 SLA 监控
     */
    public SlaPolicyEntity findPolicy(ConversationEntity session,
                                       List<String> visitorTagNames) {
        List<SlaPolicyEntity> policies = slaPolicyCache.getAllEnabled();
        for (SlaPolicyEntity policy : policies) {
            if (matchesTags(policy, session, visitorTagNames)) {
                return policy;
            }
        }
        return null;
    }

    private boolean matchesTags(SlaPolicyEntity policy,
                                  ConversationEntity session,
                                  List<String> visitorTagNames) {
        List<String> mvt = policy.getMatchVisitorTags();
        List<String> mtt = policy.getMatchTransferTags();

        boolean visitorMatch = mvt == null || mvt.isEmpty()
                || visitorTagNames.stream().anyMatch(mvt::contains);
        boolean transferMatch = mtt == null || mtt.isEmpty()
                || (session.getTag() != null && mtt.contains(session.getTag()));
        return visitorMatch && transferMatch;
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add domain model (BreachCandidate, SlaPolicyMatcher, SlaBreachActions)"
```

---

## Task 3: SlaBreachEvaluator

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/scheduler/SlaBreachEvaluator.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/scheduler/SlaBreachEvaluatorTest.java`

**Interfaces:**
- Consumes: `IBusinessHoursCalculator`（P0 中已创建）
- Produces:
  - `SlaBreachEvaluator.evaluate(ConversationEntity session, SlaPolicyEntity policy, OffsetDateTime now): List<BreachCandidate>`

- [ ] **Step 1: 编写失败测试**

创建 `SlaBreachEvaluatorTest.java`：

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.domain.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaBreachEvaluatorTest {

    @Mock IBusinessHoursCalculator businessHoursCalculator;

    SlaBreachEvaluator evaluator;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        evaluator = new SlaBreachEvaluator(businessHoursCalculator);
    }

    private SlaPolicyEntity policy(int waitSec, int frtSec, int handleSec, int pct) {
        SlaPolicyEntity p = new SlaPolicyEntity();
        p.setId(1L);
        p.setWaitTimeTargetSec(waitSec);
        p.setFrtTargetSec(frtSec);
        p.setHandleTimeTargetSec(handleSec);
        p.setWarningThresholdPct(pct);
        p.setTimeMode("CALENDAR");
        p.setActions(new SlaBreachActions());
        return p;
    }

    @Test
    @DisplayName("WAITING 且等待时间超过阈值 -> WAIT BREACH")
    void evaluate_waitBreach() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s1");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(130));  // 130s 前开始，阈值 120s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(BreachType.WAIT);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.BREACH);
        assertThat(result.get(0).actualSec()).isEqualTo(130);
    }

    @Test
    @DisplayName("WAITING 且等待时间在预警区间 -> WAIT WARNING")
    void evaluate_waitWarning() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s2");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(100));  // 100s, 阈值 120s, 80% = 96s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.WARNING);
    }

    @Test
    @DisplayName("WAITING 但时间未到预警线 -> 空列表")
    void evaluate_withinThreshold_empty() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s3");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(30));  // 30s, 预警 96s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 且 firstReplyAt 为 null 且超过 FRT 阈值 -> FRT BREACH")
    void evaluate_frtBreach() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s4");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(NOW.minusSeconds(70));   // 70s 前接受，FRT 阈值 60s
        session.setFirstReplyAt(null);

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(BreachType.FRT);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.BREACH);
    }

    @Test
    @DisplayName("ACTIVE 且 acceptedAt 为 null -> FRT/HANDLE 均跳过，返回空列表（无异常）")
    void evaluate_acceptedAtNull_skipQuietly() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s5");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(null);  // 数据异常

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 且 BUSINESS_HOURS 模式 -> 调用 businessHoursCalculator")
    void evaluate_businessHoursMode_callsCalculator() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s6");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(NOW.minusSeconds(200));
        session.setFirstReplyAt(null);

        SlaPolicyEntity p = policy(120, 60, 1800, 80);
        p.setTimeMode("BUSINESS_HOURS");

        when(businessHoursCalculator.calcBusinessSeconds(any(), any())).thenReturn(70L);

        List<BreachCandidate> result = evaluator.evaluate(session, p, NOW);

        // 70 > 60 (FRT), 70 > 48 (FRT warn), 70 < 1800 (HANDLE target), 70 > 1440 (HANDLE warn? no)
        assertThat(result).extracting(BreachCandidate::type).contains(BreachType.FRT);
    }
}
```

- [ ] **Step 2: 运行测试（验证失败）**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=SlaBreachEvaluatorTest -q
```

期望：编译失败（SlaBreachEvaluator 不存在）。

- [ ] **Step 3: 实现 SlaBreachEvaluator**

创建 `SlaBreachEvaluator.java`：

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.*;
import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SLA 违规评估器。无状态计算组件，不包含任何 I/O。
 * 通过 {@link IBusinessHoursCalculator} 接口获取业务时间计算能力（依赖倒置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachEvaluator {

    private static final int PCT_DIVISOR = 100;

    private final IBusinessHoursCalculator businessHoursCalculator;

    /**
     * @param session 待检测会话
     * @param policy  命中的 SLA 策略（调用方保证非 null）
     * @param now     检测基准时间（由调度器统一捕获）
     * @return 本次产生的违规候选，无违规返回空列表（不返回 null）
     */
    public List<BreachCandidate> evaluate(ConversationEntity session,
                                           SlaPolicyEntity policy,
                                           OffsetDateTime now) {
        List<BreachCandidate> results = new ArrayList<>();
        evaluateWait(session, policy, now).ifPresent(results::add);
        evaluateFrt(session, policy, now).ifPresent(results::add);
        evaluateHandle(session, policy, now).ifPresent(results::add);
        return results;
    }

    // ── 三个指标的独立检测方法 ─────────────────────────────────────────────────

    private Optional<BreachCandidate> evaluateWait(ConversationEntity session,
                                                     SlaPolicyEntity policy,
                                                     OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.WAITING) return Optional.empty();
        long elapsed = calcElapsed(session.getStartedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getWaitTimeTargetSec(),
                            policy.getWarningThresholdPct(), BreachType.WAIT, session, now);
    }

    private Optional<BreachCandidate> evaluateFrt(ConversationEntity session,
                                                    SlaPolicyEntity policy,
                                                    OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE) return Optional.empty();
        if (session.getAcceptedAt() == null) {
            log.warn("[SLA] ACTIVE session missing acceptedAt, skipping FRT. session={}",
                     session.getSessionId());
            return Optional.empty();
        }
        if (session.getFirstReplyAt() != null) return Optional.empty();
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getFrtTargetSec(),
                            policy.getWarningThresholdPct(), BreachType.FRT, session, now);
    }

    private Optional<BreachCandidate> evaluateHandle(ConversationEntity session,
                                                       SlaPolicyEntity policy,
                                                       OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE) return Optional.empty();
        if (session.getAcceptedAt() == null) {
            log.warn("[SLA] ACTIVE session missing acceptedAt, skipping HANDLE. session={}",
                     session.getSessionId());
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getHandleTimeTargetSec(),
                            policy.getWarningThresholdPct(), BreachType.HANDLE, session, now);
    }

    // ── 通用辅助方法 ──────────────────────────────────────────────────────────

    /**
     * 判断违规阶段。
     * M-R3-2修复：使用 (long) 强转防止乘法溢出。
     */
    private Optional<BreachCandidate> resolveStage(long elapsed, int targetSec,
                                                     int warningPct, BreachType type,
                                                     ConversationEntity session,
                                                     OffsetDateTime now) {
        int warnAtSec = (int) ((long) targetSec * warningPct / PCT_DIVISOR);
        if (elapsed >= targetSec) {
            return Optional.of(build(session, type, BreachStage.BREACH,
                                     targetSec, warnAtSec, elapsed, now));
        }
        if (elapsed >= warnAtSec) {
            return Optional.of(build(session, type, BreachStage.WARNING,
                                     targetSec, warnAtSec, elapsed, now));
        }
        return Optional.empty();
    }

    private long calcElapsed(OffsetDateTime start, OffsetDateTime now, String timeMode) {
        return "BUSINESS_HOURS".equals(timeMode)
                ? businessHoursCalculator.calcBusinessSeconds(start, now)
                : ChronoUnit.SECONDS.between(start, now);
    }

    private BreachCandidate build(ConversationEntity session, BreachType type, BreachStage stage,
                                   int targetSec, int warnAtSec, long actualSec,
                                   OffsetDateTime detectedAt) {
        return new BreachCandidate(session.getSessionId(), type, stage,
                                   targetSec, warnAtSec, (int) actualSec, detectedAt);
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=SlaBreachEvaluatorTest -q
```

期望：6 个测试全 PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add SlaBreachEvaluator with WARNING/BREACH two-stage detection"
```

---

## Task 4: SlaBreachRecorder + SlaBreachNotifier + SlaEscalationHandler

**Files:**
- Create: `...infrastructure/scheduler/SlaBreachRecorder.java`
- Create: `...infrastructure/scheduler/SlaBreachNotifier.java`
- Create: `...application/service/SlaEscalationHandler.java`
- Create: `...infrastructure/scheduler/SlaBreachRecorderTest.java`

**Interfaces:**
- Consumes: `SlaBreachMapper`, `RabbitTemplate eventsRabbitTemplate`, `ApplicationEventPublisher`, `SessionQueueService`
- Produces:
  - `SlaBreachRecorder.record(BreachCandidate candidate, Long policyId): Optional<SlaBreachEntity>`
  - `SlaBreachRecorder.markAlerted(Long breachId, OffsetDateTime at): void`
  - `SlaBreachRecorder.markEscalated(Long breachId, OffsetDateTime at): void`
  - `SlaBreachNotifier.notifyBatch(List<SlaBreachEntity> breaches, SlaPolicyEntity policy, ConversationEntity session): void`

- [ ] **Step 1: 编写 SlaBreachRecorder 测试**

创建 `SlaBreachRecorderTest.java`：

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaBreachRecorderTest {

    @Mock SlaBreachMapper slaBreachMapper;

    SlaBreachRecorder recorder;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        recorder = new SlaBreachRecorder(slaBreachMapper);
    }

    private BreachCandidate candidate(BreachType type, BreachStage stage) {
        return new BreachCandidate("sess-001", type, stage, 120, 96, 130, NOW);
    }

    @Test
    @DisplayName("新违规 -> 写入 DB 并返回实体")
    void record_newBreach_insertsAndReturns() {
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.BREACH))
                .thenReturn(false);
        doAnswer(inv -> { ((SlaBreachEntity) inv.getArgument(0)).setId(1L); return 1; })
                .when(slaBreachMapper).insert(any());

        Optional<SlaBreachEntity> result =
                recorder.record(candidate(BreachType.WAIT, BreachStage.BREACH), 1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        verify(slaBreachMapper).insert(any());
    }

    @Test
    @DisplayName("WARNING 已存在 -> 不阻断 BREACH（三维幂等）")
    void record_warningExistsButBreachIsNew_inserts() {
        // WARNING 存在
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.WARNING))
                .thenReturn(true);
        // BREACH 不存在
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.BREACH))
                .thenReturn(false);

        doAnswer(inv -> { ((SlaBreachEntity) inv.getArgument(0)).setId(2L); return 1; })
                .when(slaBreachMapper).insert(any());

        // 记录 WARNING（幂等跳过）
        Optional<SlaBreachEntity> warningResult =
                recorder.record(candidate(BreachType.WAIT, BreachStage.WARNING), 1L);
        assertThat(warningResult).isEmpty();

        // 记录 BREACH（应写入）
        Optional<SlaBreachEntity> breachResult =
                recorder.record(candidate(BreachType.WAIT, BreachStage.BREACH), 1L);
        assertThat(breachResult).isPresent();
    }

    @Test
    @DisplayName("已存在相同 (sessionId, type, stage) -> 幂等跳过")
    void record_duplicate_returnsEmpty() {
        when(slaBreachMapper.existsBySessionTypeAndStage(any(), any(), any())).thenReturn(true);

        Optional<SlaBreachEntity> result =
                recorder.record(candidate(BreachType.FRT, BreachStage.BREACH), 1L);

        assertThat(result).isEmpty();
        verify(slaBreachMapper, never()).insert(any());
    }
}
```

- [ ] **Step 2: 运行测试（验证失败）**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=SlaBreachRecorderTest -q
```

期望：编译失败。

- [ ] **Step 3: 实现 SlaBreachRecorder**

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * SLA 违规持久化组件。
 * 幂等写入：按 (session_id, breach_type, stage) 三维检查，
 * WARNING 入库不阻断后续 BREACH 写入（两者 stage 不同）。
 */
@Component
@RequiredArgsConstructor
public class SlaBreachRecorder {

    private final SlaBreachMapper slaBreachMapper;

    public Optional<SlaBreachEntity> record(BreachCandidate candidate, Long policyId) {
        if (slaBreachMapper.existsBySessionTypeAndStage(
                candidate.sessionId(), candidate.type(), candidate.stage())) {
            return Optional.empty();
        }
        SlaBreachEntity entity = SlaBreachEntity.builder()
                .sessionId(candidate.sessionId())
                .policyId(policyId)
                .breachType(candidate.type().name())
                .stage(candidate.stage().name())
                .targetSec(candidate.targetSec())
                .warnAtSec(candidate.warnAtSec())
                .actualSec(candidate.actualSec())
                .breachAt(candidate.detectedAt())
                .build();
        slaBreachMapper.insert(entity);
        return Optional.of(entity);
    }

    public void markAlerted(Long breachId, OffsetDateTime at) {
        slaBreachMapper.updateAlertedAt(breachId, at);
    }

    public void markEscalated(Long breachId, OffsetDateTime at) {
        slaBreachMapper.updateEscalatedAt(breachId, at);
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=SlaBreachRecorderTest -q
```

期望：3 个测试全 PASS。

- [ ] **Step 5: 实现 SlaBreachNotifier**

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.event.SlaEscalationRequestedEvent;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SLA 违规通知组件。
 * SSE 告警：将聚合事件发送到 fanout exchange，由 SessionEventSubscriber 广播给所有坐席。
 * 自动升级：发布 SlaEscalationRequestedEvent 领域事件（domain 层），
 *           由 application 层的 SlaEscalationHandler 订阅处理，避免跨聚合直接调用。
 */
@Slf4j
@Component
public class SlaBreachNotifier {

    private final String                   eventsExchange;
    private final RabbitTemplate           eventsRabbitTemplate;
    private final ApplicationEventPublisher springEventPublisher;
    private final SlaBreachRecorder         recorder;

    public SlaBreachNotifier(
            @Value("${conversation.events.exchange}") String eventsExchange,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate eventsRabbitTemplate,
            ApplicationEventPublisher springEventPublisher,
            SlaBreachRecorder recorder) {
        this.eventsExchange        = eventsExchange;
        this.eventsRabbitTemplate  = eventsRabbitTemplate;
        this.springEventPublisher  = springEventPublisher;
        this.recorder              = recorder;
    }

    /**
     * 批量通知：同一会话本轮所有新违规聚合为一条 SSE，避免告警疲劳。
     * autoEscalate 仅在有 BREACH 阶段记录时触发，WARNING 不触发升级。
     */
    public void notifyBatch(List<SlaBreachEntity> newBreaches,
                             SlaPolicyEntity policy,
                             ConversationEntity session) {
        var actions = policy.getActions();
        OffsetDateTime now = OffsetDateTime.now();

        // SSE 聚合推送
        if (actions.isSseAlert()) {
            try {
                Map<String, Object> event = Map.of(
                        "type",        SessionEventType.SLA_BREACH.name(),
                        "sessionId",   session.getSessionId(),
                        "visitorName", session.getVisitorName() != null ? session.getVisitorName() : "",
                        "agentId",     session.getAgentId() != null ? session.getAgentId() : "",
                        "policyName",  policy.getName(),
                        "breaches",    newBreaches.stream().map(b -> Map.of(
                                "breachType", b.getBreachType(),
                                "stage",      b.getStage(),
                                "targetSec",  b.getTargetSec(),
                                "actualSec",  b.getActualSec()
                        )).toList()
                );
                eventsRabbitTemplate.convertAndSend(eventsExchange, "", event);
                newBreaches.forEach(b -> recorder.markAlerted(b.getId(), now));
            } catch (Exception e) {
                log.error("[SLA] SSE publish failed session={}", session.getSessionId(), e);
            }
        }

        // 自动升级：仅 BREACH 阶段触发
        boolean hasActualBreach = newBreaches.stream()
                .anyMatch(b -> BreachStage.BREACH.name().equals(b.getStage()));
        if (hasActualBreach && actions.isAutoEscalate()
                && actions.getEscalateToUserId() != null) {
            List<Long> breachIds = newBreaches.stream()
                    .filter(b -> BreachStage.BREACH.name().equals(b.getStage()))
                    .map(SlaBreachEntity::getId).toList();
            springEventPublisher.publishEvent(
                    new SlaEscalationRequestedEvent(session.getSessionId(),
                                                    actions.getEscalateToUserId(),
                                                    breachIds));
        }
    }
}
```

- [ ] **Step 6: 实现 SlaEscalationHandler（application 层）**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.event.SlaEscalationRequestedEvent;
import com.aria.conversation.infrastructure.scheduler.SlaBreachRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * SLA 自动升级事件处理器（application 层）。
 * 响应 SlaEscalationRequestedEvent，调用 SessionQueueService.transfer()。
 * 与 SlaBreachNotifier（infrastructure）通过领域事件解耦，避免跨聚合直接调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationHandler {

    private final SessionQueueService sessionQueueService;
    private final SlaBreachRecorder   recorder;

    @EventListener
    public void onEscalationRequested(SlaEscalationRequestedEvent event) {
        try {
            sessionQueueService.transfer(event.sessionId(), event.targetAgentId());
            OffsetDateTime now = OffsetDateTime.now();
            event.breachIds().forEach(id -> recorder.markEscalated(id, now));
        } catch (Exception e) {
            log.warn("[SLA] autoEscalate failed session={} target={}",
                    event.sessionId(), event.targetAgentId(), e);
        }
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add SlaBreachRecorder, SlaBreachNotifier, SlaEscalationHandler"
```

---

## Task 5: SlaBreachDetector + SlaBreachScanScheduler

**Files:**
- Create: `...infrastructure/scheduler/SlaBreachDetector.java`
- Create: `...infrastructure/scheduler/SlaBreachScanScheduler.java`
- Create: `...infrastructure/persistence/mapper/ConversationMapper.java`（追加分片查询方法）
- Create: `...SlaBreachDetectorTest.java`

**Interfaces:**
- Consumes: `SlaPolicyMatcher`, `SlaBreachEvaluator`, `SlaBreachRecorder`, `SlaBreachNotifier`, `VisitorTagMapper`, `ConversationMapper`
- Produces:
  - `SlaBreachDetector.check(ConversationEntity session, OffsetDateTime now): void`
  - `SlaBreachScanScheduler.scan(): void`

- [ ] **Step 1: 在 ConversationMapper 追加分片查询**

打开 `ConversationMapper.java`，追加：

```java
/** 按 CRC32 分片查询活跃会话（WAITING + ACTIVE） */
@Select("SELECT * FROM cs_conversation.cs_conversation " +
        "WHERE status IN ('WAITING','ACTIVE') " +
        "AND MOD(ABS(CRC32(session_id)), #{shardCount}) = #{shardIndex}")
List<ConversationEntity> selectActiveByShardIndex(
        @Param("shardIndex") int shardIndex,
        @Param("shardCount") int shardCount);
```

- [ ] **Step 2: 实现 SlaBreachDetector**

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.infrastructure.cache.SlaPolicyCache;
import com.aria.conversation.infrastructure.persistence.entity.*;
import com.aria.conversation.infrastructure.persistence.mapper.VisitorTagMapper;
import com.aria.conversation.domain.service.SlaPolicyMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SLA 违规检测器（薄编排层），串联 Evaluator → Recorder → Notifier。
 * 自身不含任何业务判断逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachDetector {

    private final SlaPolicyMatcher   slaPolicyMatcher;
    private final SlaBreachEvaluator evaluator;
    private final SlaBreachRecorder  recorder;
    private final SlaBreachNotifier  notifier;
    private final VisitorTagMapper   visitorTagMapper;

    /**
     * @param session 待检测会话
     * @param now     检测基准时间（由 SlaBreachScanScheduler 统一捕获传入）
     */
    public void check(ConversationEntity session, OffsetDateTime now) {
        // 获取访客标签名列表（用于策略匹配）
        List<String> visitorTagNames = visitorTagMapper
                .selectTagsByVisitorId(session.getVisitorId() != null ? session.getVisitorId() : "")
                .stream().map(TagEntity::getName).toList();

        SlaPolicyEntity policy = slaPolicyMatcher.findPolicy(session, visitorTagNames);
        if (policy == null) return;  // 无匹配策略，不监控

        List<BreachCandidate> candidates = evaluator.evaluate(session, policy, now);

        List<SlaBreachEntity> newBreaches = candidates.stream()
                .map(c -> recorder.record(c, policy.getId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(java.util.stream.Collectors.toList());

        if (!newBreaches.isEmpty()) {
            notifier.notifyBatch(newBreaches, policy, session);
        }
    }
}
```

- [ ] **Step 3: 创建 SlaProperties**

```java
package com.aria.conversation.infrastructure.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sla")
public class SlaProperties {
    /** 分片总数，建议 ≥ Pod 数 */
    private int  shardCount      = 4;
    /** 扫描间隔（毫秒）*/
    private long scanIntervalMs  = 30_000;
    /** 启动延迟（毫秒）*/
    private long initialDelayMs  = 10_000;
}
```

在 `application.yml` 中追加：

```yaml
sla:
  shard-count: 4
  scan-interval-ms: 30000
  initial-delay-ms: 10000
```

- [ ] **Step 4: 实现 SlaBreachScanScheduler**

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SLA 分片扫描调度器。
 * 分片策略：CRC32(session_id) % shardCount，每个分片独立 Redisson 锁。
 * now 在分片入口统一捕获一次，确保同批次所有会话使用相同基准时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachScanScheduler {

    private static final String LOCK_KEY_PREFIX = "lock:scheduler:sla-scan:shard:";
    private static final long   LEASE_SECONDS   = 25L;

    private final RedissonClient     redissonClient;
    private final ConversationMapper conversationMapper;
    private final SlaBreachDetector  slaBreachDetector;
    private final SlaProperties      slaProperties;

    @Scheduled(fixedDelayString   = "${sla.scan-interval-ms:30000}",
               initialDelayString = "${sla.initial-delay-ms:10000}")
    public void scan() {
        int shardCount = slaProperties.getShardCount();
        // now 在分片入口统一捕获，保证同批次时间一致
        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < shardCount; i++) {
            final int shardIndex = i;
            RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + shardIndex);
            boolean acquired;
            try {
                acquired = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!acquired) continue;

            try {
                List<com.aria.conversation.infrastructure.persistence.entity.ConversationEntity> sessions =
                        conversationMapper.selectActiveByShardIndex(shardIndex, shardCount);
                log.debug("[SLA-scan] shard={}/{} sessions={} now={}", shardIndex, shardCount,
                          sessions.size(), now);
                sessions.forEach(session -> {
                    try {
                        slaBreachDetector.check(session, now);
                    } catch (Exception e) {
                        log.error("[SLA-scan] check failed session={}",
                                  session.getSessionId(), e);
                    }
                });
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add SlaBreachDetector and SlaBreachScanScheduler with sharding"
```

---

## Task 6: SLA REST Controllers

**Files:**
- Create: `...interfaces/rest/SlaController.java`

**Interfaces:**
- Consumes: `SlaPolicyMapper`, `SlaBreachMapper`, `SlaPolicyCache`
- Produces:
  - `GET/POST/PUT/DELETE /api/v1/admin/sla/policies`
  - `GET /api/v1/admin/sla/breaches`

- [ ] **Step 1: 创建 SlaController**

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.web.response.R;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.infrastructure.cache.SlaPolicyCache;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.SlaPolicyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/sla")
@RequiredArgsConstructor
public class SlaController {

    private static final int NOT_FOUND = 40400;

    private final SlaPolicyMapper slaPolicyMapper;
    private final SlaBreachMapper  slaBreachMapper;
    private final SlaPolicyCache   slaPolicyCache;

    // ── 策略 CRUD ────────────────────────────────────────────────────────────

    @GetMapping("/policies")
    @SaCheckPermission("system:sla:manage")
    public R<List<SlaPolicyEntity>> listPolicies() {
        return R.ok(slaPolicyMapper.selectList(
                Wrappers.<SlaPolicyEntity>lambdaQuery()
                        .orderByDesc(SlaPolicyEntity::getPriority)
                        .orderByAsc(SlaPolicyEntity::getId)));
    }

    @PostMapping("/policies")
    @SaCheckPermission("system:sla:manage")
    public R<SlaPolicyEntity> createPolicy(@RequestBody @Valid PolicyReq req) {
        SlaPolicyEntity entity = buildEntity(null, req);
        slaPolicyMapper.insert(entity);
        slaPolicyCache.evict();
        return R.ok(entity);
    }

    @PutMapping("/policies/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> updatePolicy(@PathVariable Long id,
                                 @RequestBody @Valid PolicyReq req) {
        if (slaPolicyMapper.selectById(id) == null) {
            throw new BusinessException(NOT_FOUND, "SLA 策略不存在: " + id);
        }
        SlaPolicyEntity entity = buildEntity(id, req);
        slaPolicyMapper.updateById(entity);
        slaPolicyCache.evict();
        return R.ok();
    }

    @DeleteMapping("/policies/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> deletePolicy(@PathVariable Long id) {
        slaPolicyMapper.deleteById(id);
        slaPolicyCache.evict();
        return R.ok();
    }

    // ── 违规记录查询 ──────────────────────────────────────────────────────────

    @GetMapping("/breaches")
    @SaCheckPermission("system:sla:view")
    public R<List<SlaBreachEntity>> listBreaches(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String breachType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        if (pageSize > 100) pageSize = 100;
        var wrapper = Wrappers.<SlaBreachEntity>lambdaQuery();
        if (sessionId  != null) wrapper.eq(SlaBreachEntity::getSessionId,  sessionId);
        if (breachType != null) wrapper.eq(SlaBreachEntity::getBreachType, breachType);
        if (startDate  != null) wrapper.ge(SlaBreachEntity::getBreachAt, startDate.atStartOfDay());
        if (endDate    != null) wrapper.le(SlaBreachEntity::getBreachAt,  endDate.plusDays(1).atStartOfDay());
        wrapper.orderByDesc(SlaBreachEntity::getBreachAt);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SlaBreachEntity> pageObj =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize);
        slaBreachMapper.selectPage(pageObj, wrapper);
        return R.ok(pageObj.getRecords());
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class PolicyReq {
        @NotBlank private String  name;
        @NotNull  private Boolean isEnabled;
        @NotNull  private Integer priority;
        private List<String> matchVisitorTags;
        private List<String> matchTransferTags;
        private String  timeMode           = "CALENDAR";
        @NotNull private Integer waitTimeTargetSec;
        @NotNull private Integer frtTargetSec;
        @NotNull private Integer handleTimeTargetSec;
        private Integer warningThresholdPct = 80;
        @NotNull @Valid private SlaBreachActions actions;
    }

    private SlaPolicyEntity buildEntity(Long id, PolicyReq req) {
        SlaPolicyEntity e = new SlaPolicyEntity();
        e.setId(id);
        e.setName(req.getName());
        e.setIsEnabled(req.getIsEnabled());
        e.setPriority(req.getPriority());
        e.setMatchVisitorTags(req.getMatchVisitorTags());
        e.setMatchTransferTags(req.getMatchTransferTags());
        e.setTimeMode(req.getTimeMode());
        e.setWaitTimeTargetSec(req.getWaitTimeTargetSec());
        e.setFrtTargetSec(req.getFrtTargetSec());
        e.setHandleTimeTargetSec(req.getHandleTimeTargetSec());
        e.setWarningThresholdPct(req.getWarningThresholdPct());
        e.setActions(req.getActions());
        return e;
    }
}
```

- [ ] **Step 2: 在 SessionEventType 追加 SLA_BREACH**

打开 `SessionEventType.java`，追加：

```java
public enum SessionEventType {
    ENQUEUE,
    ACCEPTED,
    CLOSED,
    TRANSFER,
    TAG_UPDATED,  // P1 中已添加
    SLA_BREACH    // 新增：SLA 违规告警（含 WARNING 和 BREACH 两阶段）
}
```

- [ ] **Step 3: 启动服务验证接口**

```bash
# 创建默认策略
curl -X POST http://localhost:8080/api/v1/admin/sla/policies \
  -H "Content-Type: application/json" \
  -d '{
    "name":"默认SLA","isEnabled":true,"priority":0,
    "timeMode":"CALENDAR","waitTimeTargetSec":120,
    "frtTargetSec":60,"handleTimeTargetSec":1800,
    "warningThresholdPct":80,
    "actions":{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false}
  }'
# 期望：{"code":200,"data":{"id":1,...}}

# 查询违规记录
curl http://localhost:8080/api/v1/admin/sla/breaches
# 期望：{"code":200,"data":[]}
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add SlaController with policy CRUD and breach query"
```

---

## Task 7: Dashboard 扩展 + 全量集成验证

**Files:**
- Modify: `...application/service/DashboardAppService.java`（追加 SLA 统计字段）

**Interfaces:**
- Produces: `DashboardAppService.getOverview()` 响应追加 `slaBreachCount`、`slaBreachRate`

- [ ] **Step 1: 在 DashboardAppService 追加 SLA 统计**

打开 `DashboardAppService.java`，在 `getOverview()` 方法的 VO 构建处追加：

```java
// 今日 SLA 统计
LocalDateTime todayStart = LocalDate.now().atStartOfDay();
int slaBreachCount = (int) slaBreachMapper.selectCount(
        Wrappers.<SlaBreachEntity>lambdaQuery()
                .eq(SlaBreachEntity::getStage, "BREACH")
                .ge(SlaBreachEntity::getBreachAt, todayStart));

// 今日有违规的会话数（去重 sessionId）
long distinctBreachedSessions = slaBreachMapper.countDistinctBreachedSessionsToday(todayStart);
// 今日总人工会话数（status 经历过 ACTIVE 的会话）
long totalAgentSessions = conversationMapper.selectCount(
        Wrappers.<ConversationEntity>lambdaQuery()
                .ge(ConversationEntity::getStartedAt, todayStart)
                .isNotNull(ConversationEntity::getAcceptedAt));

double slaBreachRate = totalAgentSessions > 0
        ? (double) distinctBreachedSessions / totalAgentSessions : 0.0;

// 追加到 overview VO（参考现有 VO 构建方式）
overview.setSlaBreachCount(slaBreachCount);
overview.setSlaBreachRate(Math.round(slaBreachRate * 100.0) / 100.0);
```

在 `SlaBreachMapper` 追加方法：

```java
@Select("SELECT COUNT(DISTINCT session_id) FROM cs_conversation.cs_sla_breach " +
        "WHERE stage = 'BREACH' AND breach_at >= #{todayStart}")
long countDistinctBreachedSessionsToday(@Param("todayStart") LocalDateTime todayStart);
```

- [ ] **Step 2: 运行全量测试**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -q
```

期望：所有测试 PASS，无编译错误。

若有测试因新注入依赖失败，在对应测试的 `@BeforeEach` 中补充 `@Mock` 和构造参数。

- [ ] **Step 3: 最终集成验证**

```bash
# 启动服务，检查关键日志
# 1. 调度器启动日志
grep "SLA-scan" logs/app.log
# 期望：每 30s 出现 [SLA-scan] shard=0/4 sessions=N now=...

# 2. 创建一个测试会话并等待 2 分钟（超过 WAIT 阈值 120s）
# 3. 检查 cs_sla_breach 表
SELECT * FROM cs_conversation.cs_sla_breach ORDER BY breach_at DESC LIMIT 5;
# 期望：出现 breach_type=WAIT, stage=WARNING（96s 时）和 BREACH（120s 时）两条记录
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(sla): add SLA breach stats to dashboard overview"
```

---
