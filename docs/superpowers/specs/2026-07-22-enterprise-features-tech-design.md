# Aria 客服系统企业级功能技术设计文档

**版本：** 1.0
**日期：** 2026-07-22
**作者：** lycodeing
**关联设计稿：**
- `docs/superpowers/specs/2026-07-22-sla-business-hours-tags-design.md`
- `docs/superpowers/specs/2026-07-22-sla-shard-scheduler-design.md`

---

## 一、背景与目标

### 1.1 背景

Aria 客服系统已具备 AI 对话、人工坐席队列、知识库检索、CSAT 满意度等核心能力。随着商业化推进，企业客户采购评估时必问以下能力：

| 功能 | 现状 | 问题 |
|---|---|---|
| SLA 管理 | 仅有原始时间字段 `accepted_at`/`first_reply_at` | 无策略定义、无违规检测、无告警 |
| 业务时间 | AI 7×24 在线，转人工无时间管控 | 坐席下班后访客转人工无人接听 |
| 节假日配置 | 完全缺失 | — |
| 访客标签 | `cs_conversation.tag` 仅为单字符串 | 不支持多标签、跨会话持久标签 |
| 会话备注 | 完全缺失 | 坐席无法写内部备注 |

### 1.2 本期目标

1. **SLA 管理**：管理员定义 SLA 策略（等待/首响/处理时长阈值），违规时按开关配置触发：仅记录 / SSE 实时告警 / 自动升级
2. **业务时间与离线自动回复**：按周排班（每天多时段）+ 节假日例外（含调休补班），非服务时间转人工时拦截并推送离线消息
3. **访客标签与会话备注**：两层标签体系（访客持久标签 + 单次会话标签）+ 会话内部备注（对访客不可见）

### 1.3 非目标

- 不支持多时区（仅 `Asia/Shanghai`）
- SLA 违规通知仅 SSE，不做邮件/短信
- AI 对话保持 7×24，业务时间仅管控**转人工**路径
- 标签打标为纯人工操作，不实现自动化

---

## 二、系统架构总览

### 2.1 模块归属

三个功能均在 `ai-conversation` 模块实现，遵循现有 DDD 四层架构：

```
ai-conversation/conversation-service/
├── domain/
│   ├── model/          ← 新增：SlaPolicy, BreachType, BreachStage, Tag 领域枚举/值对象
│   └── service/        ← 新增：SlaPolicyMatcher（领域服务，按优先级匹配策略）
├── application/
│   └── service/        ← 新增：BusinessHoursService, TagAppService, NoteAppService
├── infrastructure/
│   ├── scheduler/      ← 新增：SlaBreachScanScheduler + 四个子组件
│   ├── persistence/
│   │   ├── entity/     ← 新增：SlaPolicy, SlaBreach, Tag, VisitorTag 等实体
│   │   └── mapper/     ← 新增：对应 MyBatis-Plus Mapper
│   └── cache/          ← 新增：SlaPolicyCache，扩展 BusinessHoursCache
└── interfaces/
    └── rest/           ← 新增：9 个 Controller，修改 2 个现有 Controller
```

> **DDD 分层说明：**
> - `SlaPolicyMatcher` 包含核心业务规则（按访客标签、转人工标签匹配策略），属于 **domain service**，放在 `domain/service/` 下；通过 `SlaPolicyRepository` 接口（domain 层定义，infrastructure 层实现）读取策略列表，保持 domain 层不依赖 infrastructure。
> - `SlaBreachEvaluator` 需要业务时间计算能力，在 domain 层定义 `IBusinessHoursCalculator` 接口，infrastructure 的 `BusinessHoursService` 实现该接口，通过依赖倒置保持 Evaluator 可在 domain 层内测试。

### 2.2 新增表一览

| 表名 | 功能归属 | 说明 |
|---|---|---|
| `cs_sla_policy` | SLA | 策略定义 |
| `cs_sla_breach` | SLA | 违规记录 |
| `cs_business_hours_schedule` | 业务时间 | 每周排班（7条固定记录）|
| `cs_business_hours_holiday` | 业务时间 | 节假日例外 |
| `cs_tag` | 标签 | 标签字典 |
| `cs_visitor_tag` | 标签 | 访客持久标签（多对多）|
| `cs_conversation_tag` | 标签 | 会话级标签（多对多）|
| `cs_conversation_note` | 备注 | 会话内部备注 |

`system_config` 表新增一行：`agent.offlineMessage`（离线回复消息）

### 2.3 Redis Key 新增约定

| Key | TTL | 说明 |
|---|---|---|
| `visitor:tags:{visitorId}` | 24h | 访客标签缓存，标签变更时 invalidate |
| `business_hours:schedule:{yyyy-MM-dd}` | 距当天午夜剩余秒数 | 当天生效的时间段列表（含节假日覆盖），管理员修改排班/节假日时主动 del |
| `sla:policies:enabled` | 5min | 所有启用中的 SLA 策略列表（`List<SlaPolicy>`），策略 CRUD 时 evict |
| `lock:scheduler:sla-scan:shard:{N}` | 25s（leaseTime）| 分片扫描分布式锁 |

> `sla:policies:enabled` 缓存的是完整策略列表，`SlaPolicyMatcher.findPolicy()` 从缓存中按优先级遍历匹配，不再是单条策略。

## 三、SLA 管理

### 3.1 数据模型

#### `cs_sla_policy` — 策略定义

```sql
CREATE TABLE cs_sla_policy (
    id                     BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                   VARCHAR(50) NOT NULL                    COMMENT '策略名称',
    is_enabled             TINYINT(1)  NOT NULL DEFAULT 1          COMMENT '是否启用',
    priority               INT         NOT NULL DEFAULT 0          COMMENT '优先级，越大越优先；同优先级按 id ASC',

    -- 匹配条件（两个条件同时满足才命中；空数组 = 不限制）
    match_visitor_tags     JSON                                    COMMENT '访客标签白名单，如["VIP","高价值"]，空=不限',
    match_transfer_tags    JSON                                    COMMENT '转人工原因标签，如["投诉"]，空=不限',

    -- 时间计算模式
    time_mode              VARCHAR(15) NOT NULL DEFAULT 'CALENDAR'
                           COMMENT 'CALENDAR=日历时间 7×24 | BUSINESS_HOURS=只计业务时间',

    -- 各指标阈值
    wait_time_target_sec   INT         NOT NULL DEFAULT 120        COMMENT '排队等待超时（秒）',
    frt_target_sec         INT         NOT NULL DEFAULT 60         COMMENT '首次响应超时（秒）',
    handle_time_target_sec INT         NOT NULL DEFAULT 1800       COMMENT '处理总时长超时（秒）',

    -- 预警阈值（对三个指标统一生效）
    warning_threshold_pct  TINYINT     NOT NULL DEFAULT 80         COMMENT '预警百分比，达到目标时长的该比例时发 WARNING',

    -- 违规行为
    actions                JSON        NOT NULL                    COMMENT '违规触发行为配置',

    create_time            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,           -- I7修复：阿里规范用 create_time
    update_time            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='SLA 策略';
```

> **M7修复 — JSON 列 TypeHandler：** `actions`、`match_visitor_tags`、`match_transfer_tags` 均为 JSON 列，Entity 注解需显式指定 TypeHandler，否则 MyBatis-Plus 取出的是 `String` 而非对象：
> ```java
> @TableField(typeHandler = JacksonTypeHandler.class)
> private SlaBreachActions actions;
>
> @TableField(typeHandler = JacksonTypeHandler.class)
> private List<String> matchVisitorTags;
>
> @TableField(typeHandler = JacksonTypeHandler.class)
> private List<String> matchTransferTags;
> ```
> `cs_business_hours_schedule.time_ranges`、`cs_business_hours_holiday.time_ranges` 同理，Entity 需注解 `@TableField(typeHandler = JacksonTypeHandler.class)` 并声明泛型类型 `List<TimeRange>`。

```json
{
  "recordBreachOnly": true,       // 始终 true，违规必记录
  "sseAlert": true,               // WARNING 和 BREACH 阶段都推送 SSE
  "autoEscalate": false,          // 自动转给指定坐席（仅 BREACH 阶段触发，WARNING 不触发）
  "escalateToUserId": null        // autoEscalate=true 时的目标坐席 userId
}
```

**策略匹配规则（多策略按优先级排列，取第一个命中）：**

```
SlaPolicyMatcher.findPolicy(session):

  取 is_enabled=1 的全部策略，按 priority DESC, id ASC 排序

  for each policy:
    ① match_visitor_tags 为空 OR 访客至少有一个 visitor_tag 在列表中 → ✓
    ② match_transfer_tags 为空 OR session.tag 在列表中 → ✓
    两个条件同时 ✓ → 命中，返回该策略，停止匹配

  全部不命中 → 返回 null（该会话不受 SLA 监控）
```

配置示例：

| priority | name | match_visitor_tags | match_transfer_tags | wait_time_target_sec |
|---|---|---|---|---|
| 100 | VIP-SLA | `["VIP","高价值"]` | `[]` | 60 |
| 90 | 投诉优先-SLA | `[]` | `["投诉"]` | 90 |
| 0 | 默认-SLA | `[]` | `[]` | 120 |

访客带 VIP 标签且转人工原因为"投诉" → 命中 priority=100 的 VIP-SLA（第一个匹配即止）。

#### `cs_sla_breach` — 违规记录

```sql
CREATE TABLE cs_sla_breach (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL COMMENT '关联 sessionId',
    policy_id    BIGINT      NOT NULL COMMENT '触发策略 ID（快照，防策略变更影响历史）',
    breach_type  VARCHAR(10) NOT NULL COMMENT 'WAIT | FRT | HANDLE',
    stage        VARCHAR(10) NOT NULL DEFAULT 'BREACH' COMMENT 'WARNING（预警）| BREACH（已违规）',
    target_sec   INT         NOT NULL COMMENT '阈值快照（秒）',
    warn_at_sec  INT         NOT NULL COMMENT '预警阈值快照（秒）= target_sec × warning_threshold_pct / 100',
    actual_sec   INT         NOT NULL COMMENT '检测时实际耗时（秒）',
    breach_at    DATETIME    NOT NULL COMMENT '记录时间',
    alerted_at   DATETIME             COMMENT 'SSE 告警时间，null=未推送',
    escalated_at DATETIME             COMMENT '自动升级时间，null=未触发（WARNING 阶段不填）',
    INDEX idx_session_id (session_id),
    INDEX idx_breach_at  (breach_at),
    UNIQUE KEY uk_session_type_stage (session_id, breach_type, stage)
) COMMENT='SLA 违规记录';
```

同一会话同一类型的生命周期：`无 → WARNING（80% 时）→ BREACH（100% 时）`，最多产生两条记录，联合唯一索引保证幂等。

### 3.2 检测逻辑

三种违规类型，每种检测 WARNING 和 BREACH 两个阶段：

| 类型 | 前提条件 | 计算基准 | 时间模式 |
|---|---|---|---|
| `WAIT` | `status = WAITING` | `now − started_at` | 受 `time_mode` 控制 |
| `FRT` | `status = ACTIVE` 且 `first_reply_at IS NULL` | `now − accepted_at` | 受 `time_mode` 控制 |
| `HANDLE` | `status = ACTIVE` | `now − accepted_at` | 受 `time_mode` 控制 |

**`time_mode` 计算说明：**

```
CALENDAR（默认）：直接用 ChronoUnit.SECONDS.between(start, now)
BUSINESS_HOURS ：调用 BusinessHoursService.calcBusinessSeconds(start, now)
                  只累计业务时间内的秒数，非服务时间段跳过
```

例：`wait_time_target_sec=120`，`warning_threshold_pct=80`，则：
- `elapsed ≥ 96s`（120 × 80%）→ 产生 `WARNING` 候选
- `elapsed ≥ 120s` → 产生 `BREACH` 候选

**幂等保护：** 联合唯一索引 `(session_id, breach_type, stage)` 防止重复写入；写入前用 `INSERT IGNORE` 或先查再写均可。

### 3.3 分布式分片调度器

多 Pod 部署下，单一分布式锁只让一台机器跑所有数据，资源浪费且扩容无效。采用**固定 N 分片 + Redisson tryLock**方案：

#### 分片算法

```sql
-- 分片路由：session_id CRC32 取模
WHERE status IN ('WAITING','ACTIVE')
  AND MOD(ABS(CRC32(session_id)), #{shardCount}) = #{shardIndex}
```

选用 `session_id`（UUID风格字符串）而非数值主键，CRC32 散列分布更均匀。

#### 锁结构

```
lock key:   lock:scheduler:sla-scan:shard:{shardIndex}
leaseTime:  25 秒（< 扫描间隔 30 秒，保证下轮可重新竞争）
waitTime:   0（skip-if-busy，与现有 CsatExpiryScheduler 保持一致）
```

#### 多 Pod 分片示意（shardCount=4，3个Pod）

```
Pod-1 → shard-0 ✓  shard-1 ✓  shard-2 ✗  shard-3 ✗   （处理约50%数据）
Pod-2 → shard-0 ✗  shard-1 ✗  shard-2 ✓  shard-3 ✗   （处理约25%数据）
Pod-3 → shard-0 ✗  shard-1 ✗  shard-2 ✗  shard-3 ✓   （处理约25%数据）
```

Pod 宕机后分片锁 25s TTL 自动释放，其他 Pod 下轮接管，**不漏扫、不重复告警**。

#### 分片数配置建议

| Pod 数 | 推荐 shardCount | 说明 |
|---|---|---|
| 1 | 1 | 退化为单锁，与现有调度器一致 |
| 2~4 | 4 | 标准配置 |
| 5~8 | 8 | 保证每 Pod 至少 1 个分片 |
| > 8 | = Pod 数 | 避免部分 Pod 永远空转 |

```yaml
sla:
  shard-count: 4          # 建议 ≥ Pod 数
  scan-interval-ms: 30000 # M6修复：@Scheduled 中使用 ${sla.scan-interval-ms:30000}，与此键保持一致
```

> **M6修复 — 配置键对齐：** `@Scheduled` 注解中写 `fixedDelayString = "${sla.scan-interval-ms:30000}"`（连字符），`SlaProperties` 中字段 `scanIntervalMs` 对应 Spring Boot kebab-case 绑定键 `sla.scan-interval-ms`，两者必须一致，否则 `SlaProperties.scanIntervalMs` 修改对调度器无效。

### 3.4 组件设计（单一职责拆分）

`SlaBreachDetector` 按单一职责拆为四个类，通过 `BreachCandidate` 值对象传递检测结果：

```
SlaBreachScanScheduler   ← 调度入口，分片锁逻辑
└── SlaBreachDetector    ← 薄编排层，串联下面三个
    ├── SlaBreachEvaluator   ← 违规计算，依赖 IBusinessHoursCalculator 接口（单测只需 Mock 该接口）
    ├── SlaBreachRecorder    ← 幂等持久化到 DB（按 session_id + breach_type + stage 三维幂等）
    └── SlaBreachNotifier    ← SSE 告警；autoEscalate 通过发布领域事件解耦 SessionQueueService
```

**`BreachCandidate`**（值对象，位于 `infrastructure/scheduler/` 包，包内共享）：
```java
record BreachCandidate(
    String sessionId,
    BreachType type,
    BreachStage stage,              // WARNING | BREACH
    int targetSec,
    int warnAtSec,                  // = targetSec × warningThresholdPct / PCT_DIVISOR
    int actualSec,
    OffsetDateTime detectedAt
) {}
```

**`IBusinessHoursCalculator`**（domain 层接口，依赖倒置）：
```java
// 位于 domain/service/ 包下
public interface IBusinessHoursCalculator {
    /** 计算 [start, now] 区间内的业务时间秒数，跳过非服务时段 */
    long calcBusinessSeconds(OffsetDateTime start, OffsetDateTime now);
}
// infrastructure 层的 BusinessHoursService 实现此接口
```

**`SlaBreachEvaluator`**（domain service，通过接口隔离 infrastructure 依赖）：

> `SlaBreachEvaluator` 依赖 `IBusinessHoursCalculator` 接口（而非 infrastructure 实现），符合 DDD 依赖倒置原则。单元测试 Mock `IBusinessHoursCalculator` 即可，无需启动 Spring 容器。

```java
/**
 * SLA 违规评估器 — 无状态计算组件，判断单个会话是否触发 SLA 违规（WARNING / BREACH）。
 *
 * <p>职责边界：仅负责"是否违规"的判断，不写 DB、不发通知、不改状态。
 * 通过 {@link IBusinessHoursCalculator} 接口获取业务时间计算能力，符合依赖倒置原则。
 *
 * <p>三个 SLA 指标（WAIT / FRT / HANDLE）各自独立检测，通过
 * {@link #resolveStage} 统一处理 WARNING / BREACH 判定，避免重复逻辑。
 */
@Component
@RequiredArgsConstructor
public class SlaBreachEvaluator {

    /** 百分比计算除数，避免魔法数字 */
    private static final int PCT_DIVISOR = 100;

    private final IBusinessHoursCalculator businessHoursCalculator;

    /**
     * 评估会话是否触发 SLA 违规。
     *
     * <p>同一次调用可能同时返回多个候选（如 WAIT + FRT 同时达阈值），
     * 由调用方统一持久化并聚合推送，本方法不做任何 I/O。
     *
     * @param session 待检测的活跃会话
     * @param policy  命中的 SLA 策略（由 SlaPolicyMatcher 筛选，调用方保证非 null）
     * @param now     检测基准时间，由调用方传入以保证同批次时间一致
     * @return 本次检测产生的违规候选列表，无违规则返回空列表（不返回 null）
     */
    public List<BreachCandidate> evaluate(ConversationEntity session,
                                          SlaPolicy policy,
                                          OffsetDateTime now) {
        List<BreachCandidate> results = new ArrayList<>();
        evaluateWait(session, policy, now).ifPresent(results::add);
        evaluateFrt(session, policy, now).ifPresent(results::add);
        evaluateHandle(session, policy, now).ifPresent(results::add);
        return results;
    }

    // ── 三个指标的独立检测方法 ─────────────────────────────────────────────────

    /**
     * 检测排队等待时长（WAIT）。
     * 仅对 {@code status = WAITING} 的会话生效，计时起点为 {@code started_at}。
     */
    private Optional<BreachCandidate> evaluateWait(ConversationEntity session,
                                                    SlaPolicy policy,
                                                    OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.WAITING) {
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getStartedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getWaitTimeTargetSec(),
                            policy.getWarningThresholdPct(), WAIT, session, now);
    }

    /**
     * 检测首次响应时长（FRT）。
     * 仅对 {@code status = ACTIVE} 且坐席尚未首响（{@code first_reply_at = null}）的会话生效，
     * 计时起点为 {@code accepted_at}。
     */
    private Optional<BreachCandidate> evaluateFrt(ConversationEntity session,
                                                   SlaPolicy policy,
                                                   OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE
                || session.getAcceptedAt() == null
                || session.getFirstReplyAt() != null) {
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getFrtTargetSec(),
                            policy.getWarningThresholdPct(), FRT, session, now);
    }

    /**
     * 检测总处理时长（HANDLE）。
     * 仅对 {@code status = ACTIVE} 且已接受（{@code accepted_at != null}）的会话生效，
     * 计时起点为 {@code accepted_at}。
     * FRT 和 HANDLE 共享相同的前置条件子集，但语义不同：FRT 关注"是否首响"，HANDLE 关注"总时长"。
     */
    private Optional<BreachCandidate> evaluateHandle(ConversationEntity session,
                                                      SlaPolicy policy,
                                                      OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE
                || session.getAcceptedAt() == null) {
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getHandleTimeTargetSec(),
                            policy.getWarningThresholdPct(), HANDLE, session, now);
    }

    // ── 通用辅助方法 ──────────────────────────────────────────────────────────

    /**
     * 根据已用时与阈值比较，决定产生 WARNING、BREACH 还是无违规。
     *
     * <pre>
     *   elapsed >= targetSec          → BREACH
     *   elapsed >= warnAtSec (target × pct / 100) → WARNING
     *   else                          → empty（未达预警线，不产生候选）
     * </pre>
     *
     * 该方法是三个指标检测逻辑的唯一出口，阈值判断收口于此，避免分散。
     */
    private Optional<BreachCandidate> resolveStage(long elapsed, int targetSec,
                                                    int warningPct, BreachType type,
                                                    ConversationEntity session,
                                                    OffsetDateTime now) {
        int warnAtSec = targetSec * warningPct / PCT_DIVISOR;
        if (elapsed >= targetSec) {
            return Optional.of(buildCandidate(session, type, BREACH, targetSec, warnAtSec, elapsed, now));
        }
        if (elapsed >= warnAtSec) {
            return Optional.of(buildCandidate(session, type, WARNING, targetSec, warnAtSec, elapsed, now));
        }
        return Optional.empty();
    }

    /**
     * 根据 {@code time_mode} 计算已用时（秒）。
     * <ul>
     *   <li>{@code BUSINESS_HOURS}：跳过非服务时段，只累计业务时间内的秒数</li>
     *   <li>{@code CALENDAR}：直接计算挂钟时间差</li>
     * </ul>
     */
    private long calcElapsed(OffsetDateTime start, OffsetDateTime now, TimeMode mode) {
        return mode == TimeMode.BUSINESS_HOURS
            ? businessHoursCalculator.calcBusinessSeconds(start, now)
            : ChronoUnit.SECONDS.between(start, now);
    }

    /**
     * 构造 {@link BreachCandidate}，统一入口防止字段遗漏。
     * {@code actualSec} 使用 {@code long} 入参，强转前已由调用方保证不超 {@code Integer.MAX_VALUE}
     * （SLA 阈值通常为分钟级，单次会话不会超过 24h = 86400s）。
     */
    private BreachCandidate buildCandidate(ConversationEntity session,
                                            BreachType type, BreachStage stage,
                                            int targetSec, int warnAtSec,
                                            long actualSec, OffsetDateTime detectedAt) {
        return new BreachCandidate(
            session.getSessionId(), type, stage,
            targetSec, warnAtSec, (int) actualSec, detectedAt
        );
    }
}
```

**`SlaBreachDetector`**（薄编排，自身无业务逻辑）：
```java
public void check(ConversationEntity session) {
    SlaPolicy policy = slaPolicyMatcher.findPolicy(session);  // 按优先级匹配，null=不监控
    if (policy == null) return;

    List<BreachCandidate> candidates =
        evaluator.evaluate(session, policy, OffsetDateTime.now());

    List<SlaBreachEntity> newBreaches = candidates.stream()
        .map(c -> recorder.record(c, policy.getId()))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(toList());

    if (!newBreaches.isEmpty()) {
        // 本轮同一 session 的所有新增违规（WARNING + BREACH 混合）聚合为一条 SSE，避免告警疲劳
        notifier.notifyBatch(newBreaches, policy, session);
    }
}
```

**`SlaBreachRecorder`**（幂等写入，三维唯一性检查）：
```java
@Component
@RequiredArgsConstructor
public class SlaBreachRecorder {

    private final SlaBreachMapper slaBreachMapper;

    /**
     * 幂等写入：按 (session_id, breach_type, stage) 三维检查，
     * WARNING 入库不阻断后续 BREACH 写入（两者 stage 不同）。
     */
    public Optional<SlaBreachEntity> record(BreachCandidate candidate, Long policyId) {
        // C5修复：检查维度必须包含 stage，否则 WARNING 入库后 BREACH 会被错误跳过
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

**`SlaBreachNotifier`**（SSE 告警 + 领域事件触发自动升级）：

> **I3 修复：** `SlaBreachNotifier` 不直接调用 `SessionQueueService.transfer()`（跨聚合直接调用违反 DDD 边界），改为发布 `SlaEscalationRequestedEvent` 领域事件，由 application 层的事件处理器响应并调用 `SessionQueueService`，两个聚合通过事件解耦。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachNotifier {

    @Value("${conversation.events.exchange}")
    private String eventsExchange;

    private final RabbitTemplate       eventsRabbitTemplate;
    private final ApplicationEventPublisher springEventPublisher;  // Spring 内部事件总线
    private final SlaBreachRecorder    recorder;

    public void notifyBatch(List<SlaBreachEntity> newBreaches,
                            SlaPolicy policy, ConversationEntity session) {
        SlaBreachActions actions = policy.getActions();
        OffsetDateTime now = OffsetDateTime.now();

        // SSE：聚合推送，WARNING + BREACH 合并为一条消息
        if (actions.isSseAlert()) {
            SlaBreachBatchEvent event = buildBatchEvent(newBreaches, policy, session);
            try {
                eventsRabbitTemplate.convertAndSend(eventsExchange, "", event);
                newBreaches.forEach(b -> recorder.markAlerted(b.getId(), now));
            } catch (Exception e) {
                log.error("[SLA] SSE publish failed session={}", session.getSessionId(), e);
            }
        }

        // 自动升级：仅 BREACH 阶段触发，发布领域事件，由 application 层处理
        boolean hasActualBreach = newBreaches.stream()
            .anyMatch(b -> BreachStage.BREACH.name().equals(b.getStage()));
        if (hasActualBreach && actions.isAutoEscalate()
                && actions.getEscalateToUserId() != null) {
            springEventPublisher.publishEvent(
                new SlaEscalationRequestedEvent(
                    session.getSessionId(), actions.getEscalateToUserId(),
                    newBreaches.stream()
                        .filter(b -> BreachStage.BREACH.name().equals(b.getStage()))
                        .map(SlaBreachEntity::getId).toList()
                )
            );
        }
    }
}

// application 层事件处理器
@Component
@RequiredArgsConstructor
public class SlaEscalationHandler {

    private final SessionQueueService  sessionQueueService;
    private final SlaBreachRecorder    recorder;

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

### 3.5 SSE 告警事件

复用现有 `/api/v1/sessions/events` 通道，新增事件类型 `SLA_BREACH`。

同一扫描周期内，同一 session 可能同时产生多条违规（如 WAIT BREACH + FRT WARNING），**聚合为一条 SSE** 推送，避免告警疲劳：

```json
{
  "type": "SLA_BREACH",
  "sessionId": "abc123",
  "visitorName": "张三",
  "agentId": "user_001",
  "policyName": "VIP-SLA",
  "breaches": [
    {"breachType": "WAIT",  "stage": "BREACH",  "targetSec": 60,  "actualSec": 95},
    {"breachType": "FRT",   "stage": "WARNING", "targetSec": 60,  "actualSec": 51}
  ]
}
```

`stage` 说明：
- `WARNING`：已达预警阈值（默认 80%），需关注但未违规
- `BREACH`：已超目标时长，正式违规；`autoEscalate` 仅在有 BREACH 时触发

### 3.6 故障场景

| 场景 | 影响 | 恢复机制 |
|---|---|---|
| Pod 扫描中途宕机 | 最多延迟 1 个扫描周期（30s）| 锁 TTL 25s 到期后其他 Pod 接管 |
| 所有 Pod 同时重启 | 最多 40s 无扫描 | initialDelay=10s 后自动恢复 |
| Redis 不可用 | 本轮跳过全部分片 | Redis 恢复后自动恢复，不影响其他功能 |
| 单条会话数据异常 | 该会话本轮跳过 | forEach 内 try-catch 隔离，不影响同分片其他会话 |

### 3.7 API

```
GET    /api/v1/admin/sla/policies              # 策略列表
POST   /api/v1/admin/sla/policies              # 新建策略
PUT    /api/v1/admin/sla/policies/{id}         # 修改策略（同时 evict Redis 缓存）
DELETE /api/v1/admin/sla/policies/{id}         # 删除策略
GET    /api/v1/admin/sla/breaches              # 违规记录，支持 sessionId/breachType/日期范围/分页
```

Dashboard `GET /api/v1/dashboard/overview` 追加：
```json
{ "slaBreachCount": 3, "slaBreachRate": 0.12 }
```

## 四、业务时间与离线自动回复

### 4.1 功能边界

AI 对话保持 7×24，业务时间**仅管控转人工路径**：

```
访客主动转人工   POST /api/v1/chat/transfer
AI 自动转人工    ChatAppService.stream() 内部意图触发
       ↓
SessionQueueService.enqueue() 前置检查
       ↓
isOpen() = false → 拦截，返回离线消息，不入队列
isOpen() = true  → 正常入队
```

### 4.2 数据模型

#### `cs_business_hours_schedule` — 每周排班

```sql
CREATE TABLE cs_business_hours_schedule (
    day_of_week  TINYINT     NOT NULL PRIMARY KEY COMMENT '1=周一 … 7=周日',
    is_open      TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '当天是否营业',
    time_ranges  JSON        NOT NULL              COMMENT '多时段数组（JacksonTypeHandler 反序列化为 List<TimeRange>）',
    timezone     VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='每周排班配置（7条固定记录，只允许 UPDATE 不允许 DELETE）';
```

`time_ranges` 支持每天多个时间段（满足午休间隔场景）：
```json
[{"start": "09:00", "end": "12:00"}, {"start": "14:00", "end": "18:00"}]
```

Flyway 初始化插入 7 条记录：周一至周五 09:00–18:00，周六周日关闭。

**I5修复 — 防误删保护：**
- `PUT /api/v1/admin/business-hours/schedule` 只做 `UPDATE`，接口层不暴露 `DELETE` 端点
- `BusinessHoursService.isOpen()` 增加防空降级：若周排班表查无数据（意外被删），记录 `WARN` 日志并降级返回 `true`（允许转人工，而非永久拒绝服务）

#### `cs_business_hours_holiday` — 节假日例外

```sql
CREATE TABLE cs_business_hours_holiday (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    date        DATE        NOT NULL COMMENT '具体日期',
    type        VARCHAR(10) NOT NULL COMMENT 'CLOSED | CUSTOM | WORKDAY',
    time_ranges JSON                 COMMENT 'CUSTOM/WORKDAY 类型有效，指定当天服务时段；CLOSED 时为 null',
    remark      VARCHAR(100)         COMMENT '备注，如"国庆节"',
    source      VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO | MANUAL',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (date)
) COMMENT='节假日例外配置';
```

`type` 枚举：

| 值 | 含义 | `time_ranges` | 来源 |
|---|---|---|---|
| `CLOSED` | 强制关闭（法定节假日）| null | holiday-cn / 管理员 |
| `WORKDAY` | 调休补班日（`isOffDay=false`）| **必填**，指定当天服务时段 | holiday-cn（同步时从周一排班复制）|
| `CUSTOM` | 临时自定义时段 | **必填** | 管理员手动 |

> **holiday-cn 同步 WORKDAY 时的 `time_ranges` 处理：** 自动同步任务写入 WORKDAY 记录时，将 `day_of_week=1`（周一）的 `time_ranges` 作为默认值复制过来，确保字段非空。管理员可后续修改单条记录覆盖。

`source` 枚举：`AUTO`（holiday-cn 同步）/ `MANUAL`（管理员手动）

### 4.3 节假日同步：holiday-cn

**数据源：** [NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn)，每日自动从国务院公告同步，通过 jsDelivr CDN 拉取：
```
https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json
```

数据格式：
```json
{
  "year": 2026,
  "days": [
    {"name": "元旦",     "date": "2026-01-01", "isOffDay": true},
    {"name": "元旦补班", "date": "2025-12-27", "isOffDay": false}
  ]
}
```

**三层同步策略：**

1. **年度自动同步**：每年 12 月 1 日 00:00 `@Scheduled cron` 拉取次年 JSON，写入 `cs_business_hours_holiday`，`source=AUTO`，幂等跳过已有数据
2. **管理员手动触发**：`POST /api/v1/admin/business-hours/holidays/sync?year={year}`，立即拉取指定年份
3. **单条手动兜底**：管理员可对任意记录增删改，处理国务院临时补充通知

**M8修复 — HTTP 超时与降级策略：**

jsDelivr CDN 在中国大陆网络环境中可能出现延迟或不可达，同步任务需明确超时和降级行为：

```java
// HolidaySyncService 中的 HTTP 配置
private static final int CONNECT_TIMEOUT_MS = 5_000;
private static final int READ_TIMEOUT_MS    = 10_000;
private static final int MAX_RETRY_TIMES    = 3;
```

- 连接超时 5s，读取超时 10s
- 失败时重试最多 3 次（指数退避：1s / 3s / 9s）
- 全部重试失败：年度自动同步记录 ERROR 日志并抛出异常（不静默吞掉）；手动触发接口返回 `500` 让前端提示重试
- 备用数据源：可在 `system_config` 中配置自定义 CDN 地址覆盖默认值

### 4.4 营业状态判断逻辑

```
BusinessHoursService.isOpen(now: ZonedDateTime): boolean

① 查节假日表 date = today
   CLOSED   → false（直接返回）
   WORKDAY  → 用该记录的 time_ranges 判断时间段（步骤③）
   CUSTOM   → 用该记录的 time_ranges 判断时间段（步骤③）
   无记录   → 继续步骤②

② 查周排班表 day_of_week = today.dayOfWeek
   is_open = false → false

③ 遍历 time_ranges，now.time 落在任意区间内 → true，否则 → false
```

**缓存策略（修正版）：**

> ⚠️ 不能缓存 `boolean` 结果——上午 09:01 缓存 `true`，午休 12:00 后取缓存仍是 `true`，导致非服务时间放行转人工。

正确做法：**缓存当天的排班区间列表**，每次 `isOpen()` 用实时时间与缓存的区间比较：

```
key:   business_hours:schedule:{yyyy-MM-dd}
value: List<TimeRange>（当天实际生效的时间段，已合并节假日覆盖）
TTL:   距当天午夜的剩余秒数（每天自动重新加载）
```

`isOpen()` 的 Redis 读写逻辑：
```java
public boolean isOpen(ZonedDateTime now) {
    String dateKey = "business_hours:schedule:" + now.toLocalDate();
    List<TimeRange> ranges = cache.get(dateKey);          // 取当天时间段列表
    if (ranges == null) {
        ranges = loadTodayRanges(now.toLocalDate());       // 查 DB（节假日 → 周排班）
        long ttl = secondsUntilMidnight(now);
        cache.set(dateKey, ranges, ttl);
    }
    LocalTime t = now.toLocalTime();
    return ranges.stream().anyMatch(r -> !t.isBefore(r.start()) && t.isBefore(r.end()));
}
```

缓存失效时机：管理员修改排班或节假日时，主动 `del business_hours:schedule:{date}` 使对应日期缓存失效。

### 4.5 集成点

**`SessionQueueService.enqueue()` 前置拦截：**
```java
if (!businessHoursService.isOpen(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))) {
    String msg = systemConfigService.get("agent.offlineMessage")
                     .replace("{nextOpenTime}", businessHoursService.nextOpenTime());
    throw new ServiceOfflineException(msg);
}
```

**`ChatAppService` 捕获：**
```java
catch (ServiceOfflineException e) {
    // 非 SSE：返回错误码 40301
    // SSE：emit event:offline { message, nextOpenTime }
}
```

**离线回复消息**复用 `system_config` 表，新增：
```
config_key:   agent.offlineMessage
config_value: 您好，当前不在服务时间，我们将在 {nextOpenTime} 恢复服务。
```

### 4.6 API

```
# 周排班
GET  /api/v1/admin/business-hours/schedule
PUT  /api/v1/admin/business-hours/schedule

# 节假日
GET    /api/v1/admin/business-hours/holidays          # 支持 year 过滤
POST   /api/v1/admin/business-hours/holidays
PUT    /api/v1/admin/business-hours/holidays/{id}
DELETE /api/v1/admin/business-hours/holidays/{id}
POST   /api/v1/admin/business-hours/holidays/sync     # 触发 holiday-cn 同步，param: year

# 离线回复消息
GET  /api/v1/admin/business-hours/offline-reply
PUT  /api/v1/admin/business-hours/offline-reply

# 状态查询（访客端 / 坐席端展示用）
GET  /api/v1/business-hours/status
Response: { "open": true, "nextOpenTime": "2026-07-23 09:00" }
```

### 4.7 错误码

| 错误码 | 常量名 | 触发场景 | 前端处理 |
|---|---|---|---|
| `40301` | `BUSINESS_HOURS_CLOSED` | 非服务时间转人工 | 展示离线留言入口（留言为后续迭代）|

## 五、访客标签与会话备注

### 5.1 设计原则

| 原则 | 说明 |
|---|---|
| 两层标签 | 访客级（跨会话持久）+ 会话级（单次附加）|
| 标签来源 | 预定义字典（`PRESET`）+ 坐席自定义（`CUSTOM`），统一存标签字典表 |
| 现有 tag 字段保留 | `cs_conversation.tag` 语义为"转人工原因"，不合并进新标签体系 |
| 备注不可见 | `cs_conversation_note` 仅坐席/管理员可读，对访客完全隔离 |

### 5.2 数据模型

#### `cs_tag` — 标签字典

```sql
CREATE TABLE cs_tag (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL                   COMMENT '标签名',
    color       VARCHAR(7)  NOT NULL DEFAULT '#6B7280' COMMENT '十六进制色值',
    source      VARCHAR(10) NOT NULL DEFAULT 'PRESET'  COMMENT 'PRESET | CUSTOM',
    usage_count INT         NOT NULL DEFAULT 0         COMMENT '使用次数（含访客+会话标签合计，原子更新）',
    created_by  VARCHAR(64)                            COMMENT '创建人 userId',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- I7修复：阿里规范字段名
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) COMMENT='标签字典（JSON 列 TypeHandler 见 Entity 注解）';
```

> **I4修复 — `usage_count` 并发安全：** 坐席打标签时，`usage_count` 自增使用 MyBatis-Plus 的 `update("usage_count = usage_count + 1")` 方式，让 DB 做原子操作，不允许先 SELECT 再 UPDATE 的非原子写法。
```

预置 5 个标签（Flyway 初始化，`source=PRESET`）：

| name | color |
|---|---|
| VIP | `#F59E0B` |
| 潜在客户 | `#10B981` |
| 投诉用户 | `#EF4444` |
| 高价值 | `#6366F1` |
| 需跟进 | `#F97316` |

#### `cs_visitor_tag` — 访客持久标签

```sql
CREATE TABLE cs_visitor_tag (
    visitor_id  VARCHAR(64) NOT NULL COMMENT 'anonymousId（对应 cs_conversation.visitor_id）',
    tag_id      BIGINT      NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64) NOT NULL COMMENT '操作坐席 userId',
    tagged_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (visitor_id, tag_id),
    INDEX idx_visitor_id (visitor_id)
) COMMENT='访客持久标签（跨会话）';
```

#### `cs_conversation_tag` — 会话级标签

```sql
CREATE TABLE cs_conversation_tag (
    session_id  VARCHAR(64) NOT NULL COMMENT 'FK → cs_conversation.session_id',
    tag_id      BIGINT      NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64) NOT NULL COMMENT '操作坐席 userId',
    tagged_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, tag_id),
    INDEX idx_session_id (session_id)
) COMMENT='会话级标签（单次）';
```

#### `cs_conversation_note` — 会话内部备注

```sql
CREATE TABLE cs_conversation_note (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL COMMENT 'FK → cs_conversation.session_id',
    content     TEXT        NOT NULL COMMENT '备注内容（对访客不可见）',
    created_by  VARCHAR(64) NOT NULL COMMENT '坐席 userId',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) COMMENT='会话内部备注';
```

### 5.3 标签自动创建规则

坐席打标签时可传 `tagId`（已有标签）或 `tagName`（自定义）：

```
传 tagName:
  → 查 cs_tag WHERE name = tagName
  → 存在：直接使用
  → 不存在：INSERT source=CUSTOM, created_by=当前坐席，返回新 TagVO（含 id + 默认色 #6B7280）
```

管理员后台可将 `CUSTOM` 标签提升为 `PRESET`（规范化），或删除低频标签。删除前检查 `usage_count`，大于 0 时前端提示二次确认。

### 5.4 VO 定义

```java
// 标签
record TagVO(Long id, String name, String color, String source) {}

// 备注
record NoteVO(Long id, String content, String createdBy,
              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

// SLA 实时状态（追加进 SessionQueueItem）
record SlaStatusVO(
    boolean waitBreached,   boolean frtBreached,   boolean handleBreached,
    Long    waitRemainSec,  Long    frtRemainSec,   Long    handleRemainSec
    // null 表示已通过该阶段（已接受 / 已首响 / 已结束）
) {}
```

### 5.5 现有接口改造

#### `SessionQueueItem` 追加字段（向后兼容）

```json
{
  "visitorTags": [{"id": 1, "name": "VIP", "color": "#F59E0B", "source": "PRESET"}],
  "slaStatus":   {"waitBreached": false, "frtBreached": false, "handleBreached": false,
                  "waitRemainSec": null, "frtRemainSec": 25, "handleRemainSec": 1650}
}
```

`visitorTags` 从 Redis `visitor:tags:{visitorId}` 缓存读取（TTL 24h），标签增删时同步失效，不走 DB join，不影响队列性能。

#### 新增 `GET /api/v1/sessions/{sessionId}` — 会话详情

坐席打开会话时一次性加载：

```json
{
  "sessionId": "...", "visitorName": "...", "visitorId": "...",
  "visitorIp": "...", "visitorDevice": "...", "transferReason": "...", "tag": "...",
  "status": "ACTIVE", "agentId": "...",
  "startedAt": "...", "acceptedAt": "...", "firstReplyAt": "...",
  "endedAt": null, "closedBy": null,
  "visitorTags": [...], "sessionTags": [...],
  "notes": [...],
  "slaStatus": {...}
}
```

#### `VisitorHistoryVO` 追加字段（向后兼容）

```json
{ "visitorTags": [...], "sessionTags": [...], "notes": [...], "slaBreached": true }
```

#### SSE 新增事件 `TAG_UPDATED`

多坐席协作时实时同步标签变化：

```json
{
  "type": "TAG_UPDATED",
  "sessionId": "...", "visitorId": "...",
  "visitorTags": [...], "sessionTags": [...]
}
```

### 5.6 权限设计

| 权限 key | 角色 | 说明 |
|---|---|---|
| `system:tag:manage` | super_admin / kf_manager | 标签字典 CRUD |
| `session:tag:write` | kf_staff | 打/移除标签 |
| `session:note:write` | kf_staff | 写备注 |
| `session:note:delete:own` | kf_staff | 删除自己的备注 |
| `session:note:delete:any` | super_admin / kf_manager | 删除任意备注 |

### 5.7 API

```
# 标签字典管理（管理员）
GET    /api/v1/admin/tags                          # 列表，支持 source 过滤
POST   /api/v1/admin/tags
PUT    /api/v1/admin/tags/{id}
DELETE /api/v1/admin/tags/{id}                     # usage_count>0 需前端二次确认

# 访客持久标签（坐席操作）
GET    /api/v1/sessions/{sessionId}/visitor/tags
POST   /api/v1/sessions/{sessionId}/visitor/tags   # body: { tagId } 或 { tagName }
DELETE /api/v1/sessions/{sessionId}/visitor/tags/{tagId}

# 会话级标签（坐席操作）
GET    /api/v1/sessions/{sessionId}/tags
POST   /api/v1/sessions/{sessionId}/tags           # body: { tagId } 或 { tagName }
DELETE /api/v1/sessions/{sessionId}/tags/{tagId}

# 会话备注（坐席操作）
GET    /api/v1/sessions/{sessionId}/notes
POST   /api/v1/sessions/{sessionId}/notes          # body: { content }
PUT    /api/v1/sessions/{sessionId}/notes/{noteId} # 仅本人可改
DELETE /api/v1/sessions/{sessionId}/notes/{noteId} # 本人或管理员
```

## 六、接口改造汇总

### 6.1 现有接口改动（向后兼容）

| 接口 / VO | 改动类型 | 是否破坏性 | 前端处理要点 |
|---|---|---|---|
| `SessionQueueItem` VO | 追加 `visitorTags`、`slaStatus` | 向后兼容 | 忽略新字段即可 |
| `POST /api/v1/chat/transfer` | 新增错误码 `40301` | **需处理** | 收到 40301 展示离线留言入口 |
| `GET /api/v1/chat/stream` SSE | 新增 `offline` 事件 | 向后兼容 | 忽略未知 event 名 |
| `GET /api/v1/sessions/events` SSE | 新增 `SLA_BREACH`、`TAG_UPDATED` 事件 | 向后兼容 | 忽略未知 type |
| `GET /api/v1/sessions/visitor-history` | `VisitorHistoryVO` 追加字段 | 向后兼容 | — |
| `GET /api/v1/dashboard/overview` | 追加 `slaBreachCount`、`slaBreachRate` | 向后兼容 | — |

### 6.2 新增 Controller 清单

| Controller | 路径前缀 | 说明 |
|---|---|---|
| `SlaController` | `/api/v1/admin/sla` | SLA 策略 CRUD + 违规记录查询 |
| `BusinessHoursController` | `/api/v1/admin/business-hours` | 排班 / 节假日 / 离线消息 |
| `BusinessHoursStatusController` | `/api/v1/business-hours` | 当前营业状态（访客/坐席用）|
| `TagAdminController` | `/api/v1/admin/tags` | 标签字典管理 |
| `SessionDetailController`（新增）| `/api/v1/sessions/{id}` | 会话详情 |
| `SessionTagController` | `/api/v1/sessions/{id}/tags` | 会话级标签操作 |
| `VisitorTagController` | `/api/v1/sessions/{id}/visitor/tags` | 访客持久标签操作 |
| `SessionNoteController` | `/api/v1/sessions/{id}/notes` | 会话备注 CRUD |
| `AdminSessionController`（扩展）| `/api/v1/admin/sessions` | 新增历史会话搜索端点 |

### 6.3 完整新增端点列表

```
# SLA 管理
GET    /api/v1/admin/sla/policies
POST   /api/v1/admin/sla/policies
PUT    /api/v1/admin/sla/policies/{id}
DELETE /api/v1/admin/sla/policies/{id}
GET    /api/v1/admin/sla/breaches                # 支持 sessionId/breachType/日期范围/分页

# 业务时间
GET    /api/v1/admin/business-hours/schedule
PUT    /api/v1/admin/business-hours/schedule
GET    /api/v1/admin/business-hours/holidays
POST   /api/v1/admin/business-hours/holidays
PUT    /api/v1/admin/business-hours/holidays/{id}
DELETE /api/v1/admin/business-hours/holidays/{id}
POST   /api/v1/admin/business-hours/holidays/sync  # param: year
GET    /api/v1/admin/business-hours/offline-reply
PUT    /api/v1/admin/business-hours/offline-reply
GET    /api/v1/business-hours/status

# 标签字典
GET    /api/v1/admin/tags
POST   /api/v1/admin/tags
PUT    /api/v1/admin/tags/{id}
DELETE /api/v1/admin/tags/{id}

# 会话详情（新增）
GET    /api/v1/sessions/{sessionId}

# 访客持久标签
GET    /api/v1/sessions/{sessionId}/visitor/tags
POST   /api/v1/sessions/{sessionId}/visitor/tags
DELETE /api/v1/sessions/{sessionId}/visitor/tags/{tagId}

# 会话级标签
GET    /api/v1/sessions/{sessionId}/tags
POST   /api/v1/sessions/{sessionId}/tags
DELETE /api/v1/sessions/{sessionId}/tags/{tagId}

# 会话备注
GET    /api/v1/sessions/{sessionId}/notes
POST   /api/v1/sessions/{sessionId}/notes
PUT    /api/v1/sessions/{sessionId}/notes/{noteId}
DELETE /api/v1/sessions/{sessionId}/notes/{noteId}

# 历史会话搜索（扩展 AdminSessionController）
GET    /api/v1/admin/sessions  # 支持 tagId/agentId/status/startDate/endDate/keyword/分页
```

### 6.4 SSE 枚举扩展

```java
public enum SessionEventType {
    ENQUEUE, ACCEPTED, CLOSED, TRANSFER,  // 现有
    SLA_BREACH,   // 新增：SLA 违规告警（含 WARNING 和 BREACH 两个阶段）
    TAG_UPDATED   // 新增：标签变更通知
}
```

访客侧 SSE 新增 event 名：`offline`，payload：`{ message, nextOpenTime }`

> **M4说明 — `SessionEventSubscriber` 无需修改：** 现有 `SessionEventSubscriber.onSessionEvent(Message)` 直接将 AMQP message body 的 JSON bytes 透传给所有已连接坐席的 `SseEmitter`，不反序列化 `SessionEvent` 对象。因此新增 `SLA_BREACH` 事件类型只需往同一个 fanout exchange 发送新 payload 对象即可，subscriber 自动广播，无需改动消费者代码。

### 6.5 权限 key 新增汇总

| 权限 key | 角色 | 说明 |
|---|---|---|
| `system:sla:manage` | super_admin / kf_manager | SLA 策略 CRUD |
| `system:sla:view` | super_admin / kf_manager | 违规记录查看 |
| `system:biz-hours:manage` | super_admin / kf_manager | 业务时间配置 |
| `system:tag:manage` | super_admin / kf_manager | 标签字典 CRUD |
| `session:tag:write` | kf_staff | 打/移除标签 |
| `session:note:write` | kf_staff | 写备注 |
| `session:note:delete:own` | kf_staff | 删除自己的备注 |
| `session:note:delete:any` | super_admin / kf_manager | 删除任意备注 |

## 七、数据库变更清单

### 7.1 Flyway 版本规划

在现有最新迁移版本之上，按功能独立建文件：

| 版本 | 文件名 | 内容 |
|---|---|---|
| V5 | `V5__add_sla_tables.sql` | `cs_sla_policy` + `cs_sla_breach` + 默认策略数据 |
| V6 | `V6__add_business_hours_tables.sql` | `cs_business_hours_schedule` + `cs_business_hours_holiday` + `system_config` 离线消息 |
| V7 | `V7__add_tag_and_note_tables.sql` | `cs_tag` + `cs_visitor_tag` + `cs_conversation_tag` + `cs_conversation_note` + 预置标签数据 |

每个文件独立，便于回滚定位。

### 7.2 V5 — SLA 表

```sql
-- V5__add_sla_tables.sql
CREATE TABLE cs_sla_policy (
    id                     BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                   VARCHAR(50) NOT NULL                    COMMENT '策略名称',
    is_enabled             TINYINT(1)  NOT NULL DEFAULT 1          COMMENT '是否启用',
    priority               INT         NOT NULL DEFAULT 0          COMMENT '优先级，越大越优先；同优先级按 id ASC',
    match_visitor_tags     JSON                                    COMMENT '访客标签白名单，空=不限',
    match_transfer_tags    JSON                                    COMMENT '转人工原因标签，空=不限',
    time_mode              VARCHAR(15) NOT NULL DEFAULT 'CALENDAR' COMMENT 'CALENDAR | BUSINESS_HOURS',
    wait_time_target_sec   INT         NOT NULL DEFAULT 120        COMMENT '排队等待超时（秒）',
    frt_target_sec         INT         NOT NULL DEFAULT 60         COMMENT '首次响应超时（秒）',
    handle_time_target_sec INT         NOT NULL DEFAULT 1800       COMMENT '处理总时长超时（秒）',
    warning_threshold_pct  TINYINT     NOT NULL DEFAULT 80         COMMENT '预警百分比阈值',
    actions                JSON        NOT NULL                    COMMENT '违规行为配置（JacksonTypeHandler 反序列化为 SlaBreachActions）',
    create_time            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_priority (is_enabled, priority)   -- I6修复：策略匹配按 priority DESC 全表扫描，需要复合索引
) COMMENT='SLA 策略';

-- 默认兜底策略，priority=0，无匹配条件限制，所有会话都会命中
INSERT INTO cs_sla_policy (name, is_enabled, priority, match_visitor_tags, match_transfer_tags,
    time_mode, wait_time_target_sec, frt_target_sec, handle_time_target_sec,
    warning_threshold_pct, actions)
VALUES ('默认 SLA', 1, 0, '[]', '[]', 'CALENDAR', 120, 60, 1800, 80,
    '{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false,"escalateToUserId":null}');

CREATE TABLE cs_sla_breach (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL COMMENT '关联 sessionId',
    policy_id    BIGINT      NOT NULL COMMENT '触发策略 ID（快照）',
    breach_type  VARCHAR(10) NOT NULL COMMENT 'WAIT | FRT | HANDLE',
    stage        VARCHAR(10) NOT NULL DEFAULT 'BREACH' COMMENT 'WARNING | BREACH',
    target_sec   INT         NOT NULL COMMENT '阈值快照（秒）',
    warn_at_sec  INT         NOT NULL COMMENT '预警阈值快照（秒）',
    actual_sec   INT         NOT NULL COMMENT '检测时实际耗时（秒）',
    breach_at    DATETIME    NOT NULL COMMENT '记录时间',
    alerted_at   DATETIME             COMMENT 'SSE 告警时间',
    escalated_at DATETIME             COMMENT '自动升级时间（WARNING 阶段不填）',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- I7修复：补齐阿里规范 create_time
    INDEX idx_session_id (session_id),
    INDEX idx_breach_at  (breach_at),
    UNIQUE KEY uk_session_type_stage (session_id, breach_type, stage)
) COMMENT='SLA 违规记录';
```

### 7.3 V6 — 业务时间表

```sql
-- V6__add_business_hours_tables.sql
CREATE TABLE cs_business_hours_schedule (
    day_of_week  TINYINT     NOT NULL PRIMARY KEY COMMENT '1=周一 … 7=周日',
    is_open      TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '当天是否营业',
    time_ranges  JSON        NOT NULL              COMMENT '[{"start":"HH:mm","end":"HH:mm"}]',
    timezone     VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='每周排班配置';

INSERT INTO cs_business_hours_schedule (day_of_week, is_open, time_ranges) VALUES
    (1, 1, '[{"start":"09:00","end":"18:00"}]'),
    (2, 1, '[{"start":"09:00","end":"18:00"}]'),
    (3, 1, '[{"start":"09:00","end":"18:00"}]'),
    (4, 1, '[{"start":"09:00","end":"18:00"}]'),
    (5, 1, '[{"start":"09:00","end":"18:00"}]'),
    (6, 0, '[]'),
    (7, 0, '[]');

CREATE TABLE cs_business_hours_holiday (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    date        DATE        NOT NULL COMMENT '具体日期',
    type        VARCHAR(10) NOT NULL COMMENT 'CLOSED | CUSTOM | WORKDAY',
    time_ranges JSON                 COMMENT 'type=CUSTOM 时有效',
    remark      VARCHAR(100)         COMMENT '备注',
    source      VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO | MANUAL',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (date)
) COMMENT='节假日例外配置';

INSERT INTO system_config (config_key, config_value, config_type, remark)
VALUES ('agent.offlineMessage',
        '您好，当前不在服务时间，我们将在 {nextOpenTime} 恢复服务，感谢您的耐心等待。',
        'CUSTOMER_SERVICE', '非服务时间离线自动回复消息');
```

### 7.4 V7 — 标签与备注表

```sql
-- V7__add_tag_and_note_tables.sql
CREATE TABLE cs_tag (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL                   COMMENT '标签名',
    color       VARCHAR(7)  NOT NULL DEFAULT '#6B7280' COMMENT '十六进制色值',
    source      VARCHAR(10) NOT NULL DEFAULT 'PRESET'  COMMENT 'PRESET | CUSTOM',
    usage_count INT         NOT NULL DEFAULT 0         COMMENT '使用次数（原子更新：usage_count = usage_count + 1）',
    created_by  VARCHAR(64)                            COMMENT '创建人 userId',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) COMMENT='标签字典';

INSERT INTO cs_tag (name, color, source) VALUES
    ('VIP',    '#F59E0B', 'PRESET'),
    ('潜在客户', '#10B981', 'PRESET'),
    ('投诉用户', '#EF4444', 'PRESET'),
    ('高价值',  '#6366F1', 'PRESET'),
    ('需跟进',  '#F97316', 'PRESET');

CREATE TABLE cs_visitor_tag (
    visitor_id  VARCHAR(64) NOT NULL COMMENT 'anonymousId',
    tag_id      BIGINT      NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64) NOT NULL COMMENT '坐席 userId',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (visitor_id, tag_id),
    INDEX idx_visitor_id (visitor_id)
) COMMENT='访客持久标签';

CREATE TABLE cs_conversation_tag (
    session_id  VARCHAR(64) NOT NULL COMMENT 'sessionId',
    tag_id      BIGINT      NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64) NOT NULL COMMENT '坐席 userId',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, tag_id),
    INDEX idx_session_id (session_id)
) COMMENT='会话级标签';

CREATE TABLE cs_conversation_note (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL COMMENT 'sessionId',
    content     TEXT        NOT NULL COMMENT '备注内容（对访客不可见）',
    created_by  VARCHAR(64) NOT NULL COMMENT '坐席 userId',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) COMMENT='会话内部备注';
```

### 7.5 现有表变更影响

| 表 | 变更 | 说明 |
|---|---|---|
| `cs_conversation` | 无结构变更 | `tag` 字段保留，语义不变 |
| `system_config` | 新增一行数据 | `agent.offlineMessage` |
| 其余表 | 无变更 | — |

### 7.6 回滚脚本

```sql
-- U7：标签与备注
DROP TABLE IF EXISTS cs_conversation_note;
DROP TABLE IF EXISTS cs_conversation_tag;
DROP TABLE IF EXISTS cs_visitor_tag;
DROP TABLE IF EXISTS cs_tag;

-- U6：业务时间
DROP TABLE IF EXISTS cs_business_hours_holiday;
DROP TABLE IF EXISTS cs_business_hours_schedule;
DELETE FROM system_config WHERE config_key = 'agent.offlineMessage';

-- U5：SLA
DROP TABLE IF EXISTS cs_sla_breach;
DROP TABLE IF EXISTS cs_sla_policy;
```

> Flyway Community 版不支持自动执行 undo，以上脚本作为手动回滚参考。

---

## 八、实现优先级与交付顺序

| 优先级 | 功能模块 | 依赖关系 | 理由 |
|---|---|---|---|
| **P0** | 业务时间 + 离线回复 | 无 | 直接影响线上用户体验，坐席下班后的兜底方案 |
| **P1** | 访客标签 + 会话备注 | 无（可与 P0 并行）| 坐席日常高频操作，提升协作效率 |
| **P2** | SLA 管理（含分片调度）| 无 | 管理层需求，不阻塞日常服务 |
| **P3** | 历史会话搜索 | 依赖 P1 标签体系 | 现有 Dashboard 有部分覆盖，不急 |

**P0 建议独立迭代交付，P1 + P2 可合并为一个迭代（互不依赖可并行开发）。**

---

## 九、测试要点

### SLA 检测（`SlaBreachEvaluatorTest`）

| 用例 | 验证点 |
|---|---|
| 等待超时 | WAITING 状态，`startedAt` 设为 3 分钟前，断言返回 WAIT 违规 |
| 首响超时 | ACTIVE 状态，`acceptedAt` 2 分钟前，`firstReplyAt = null`，断言 FRT |
| 处理超时 | ACTIVE 状态，`acceptedAt` 31 分钟前，断言 HANDLE |
| 未超时 | 各字段设为 30s 前，断言返回空列表 |
| 策略禁用 | `isEnabled=false`，断言 `SlaBreachDetector.check()` 不写 DB |
| 幂等保护 | 同一会话同一类型调用两次，断言 `cs_sla_breach` 只有 1 条 |

### 分片调度（集成测试）

用 Testcontainers 启动 MySQL + Redis，模拟 2 个 `SlaBreachScanScheduler` 实例并发执行，断言每条活跃会话恰好被处理一次（通过 `cs_sla_breach` 记录数验证幂等）。

### 业务时间（`BusinessHoursServiceTest`）

| 用例 | 验证点 |
|---|---|
| 工作日服务时间内 | 断言 `isOpen = true` |
| 工作日服务时间外（午休）| 断言 `isOpen = false` |
| 法定节假日（CLOSED）| 覆盖周表，断言 `false` |
| 调休补班（WORKDAY）| 周六有 WORKDAY 记录，断言 `true` |
| Redis 缓存命中 | 第二次调用不走 DB，断言 Mapper 只调用一次 |

---

*文档生成时间：2026-07-22*
