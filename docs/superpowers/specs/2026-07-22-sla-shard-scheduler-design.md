# SLA 扫描任务多机分片设计

**日期：** 2026-07-22
**关联文档：** `docs/superpowers/specs/2026-07-22-sla-business-hours-tags-design.md`
**状态：** 待实现

---

## 1. 问题背景

### 1.1 单机扫描的问题

SLA 违规检测使用 Spring `@Scheduled` 每 30 秒扫描 `status IN ('WAITING','ACTIVE')` 的活跃会话。在多 Pod 部署时，若沿用现有调度器的 skip-if-busy 模式（单 Lock，抢到者全量扫描），存在以下问题：

| 问题 | 说明 |
|---|---|
| 资源浪费 | 同一时刻只有 1 个 Pod 在工作，其余 Pod 空转 |
| 单点压力 | 活跃会话量大时，全量扫描集中在一个 Pod |
| 扩容无效 | 增加 Pod 数量不能提升扫描吞吐，反而增加锁竞争 |

### 1.2 现有基础设施

| 组件 | 现状 | 可用能力 |
|---|---|---|
| `PodIdentity` | 已有 Bean，16 位 UUID，JVM 启动时生成 | 唯一节点标识 |
| `RedissonClient` | 已有 Bean，单节点模式 | 分布式锁 |
| 调度器模式 | `DocExpiryScheduler` / `CsatExpiryScheduler` | `tryLock(waitTime=0)` skip-if-busy |
| MySQL | 活跃会话存 `cs_conversation`，有 `session_id` 主键 | CRC32 分片路由 |

### 1.3 选型决策

选用**固定 N 分片 + Redisson tryLock** 方案（方案 A）：

- **与现有模式一致**：直接复用 `DocExpiryScheduler` 的 tryLock 写法，无学习成本
- **天然容灾**：Pod 宕机后分片锁 TTL 到期自动释放，下轮其他 Pod 接管
- **SQL 层分片**：`MOD(ABS(CRC32(session_id)), N) = shardIndex`，无额外 Redis 压力
- **不引入新依赖**：无需 XXL-Job、ShedLock 等外部调度框架

放弃方案 B（会话级抢占）原因：客服场景活跃会话量通常 < 500，方案 A 分片粒度已够细，不值得增加每轮 N 次 Redis SET 的逻辑复杂度。

## 2. 分片算法与锁设计

### 2.1 分片路由

使用 `session_id` 的 CRC32 值对分片总数取模，决定每条会话归属哪个分片：

```
shardIndex = MOD(ABS(CRC32(session_id)), shardCount)
```

**选用 `session_id` 而非数值主键的原因：**
- `session_id` 是 UUID 风格的字符串，分布更均匀，CRC32 结果散列性好
- 数值主键自增，低位分布不均（如活跃会话集中在近期 id 段）

**分片数 `shardCount` 默认值：4**
- 适配 2~8 Pod 的常见部署规模
- Pod 数 ≤ shardCount 时，多余分片由已有 Pod 多抢；Pod 数 > shardCount 时，部分 Pod 在某轮抢不到分片（下轮再竞争，平均分配）
- 可通过配置项调整，**无需重新部署**（见第 3.3 节）

### 2.2 Redis 锁结构

每个分片对应一把 Redisson 分布式锁：

```
lock key:   lock:scheduler:sla-scan:shard:{shardIndex}
lock value: 由 Redisson 内部管理（含 threadId）
leaseTime:  25 秒（略小于扫描间隔 30 秒，保证下轮可重新竞争）
waitTime:   0（skip-if-busy，与现有调度器保持一致）
```

**leaseTime 选 25 秒的理由：**
- 扫描间隔 30 秒，leaseTime < 30 秒确保锁不跨轮持有
- 预留 5 秒安全余量，防止扫描略超时导致锁续期冲突
- 正常扫描耗时远 < 1 秒（活跃会话 < 500 条），25 秒绰绰有余

### 2.3 每轮执行流程

```
@Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
SlaBreachScanScheduler.scan():

  for shardIndex in [0, shardCount):
    lock = redissonClient.getLock("lock:scheduler:sla-scan:shard:{shardIndex}")
    acquired = lock.tryLock(0, 25, SECONDS)
    if (!acquired) continue          // 该分片已被其他 Pod 处理，跳过

    try:
      sessions = mapper.selectActiveByShardIndex(shardIndex, shardCount)
      for each session:
        slaBreachDetector.check(session)
    finally:
      if lock.isHeldByCurrentThread(): lock.unlock()
```

**关键细节：**
- `initialDelay = 10_000`：Pod 启动后延迟 10 秒，等待其他 Bean 就绪后再开始扫描
- 遍历所有分片（不只抢一个），让 Pod 数 < shardCount 时单 Pod 能多承担分片
- `finally` 中用 `isHeldByCurrentThread()` 防止异常场景下解锁他人的锁

### 2.4 多 Pod 分片示意

以 `shardCount=4`、3 个 Pod 为例：

```
时刻 T0（同时触发）:
  Pod-1: 尝试抢 shard-0 ✓，shard-1 ✓，shard-2 ✗（Pod-2抢到），shard-3 ✗（Pod-3抢到）
  Pod-2: 尝试抢 shard-0 ✗，shard-1 ✗，shard-2 ✓，shard-3 ✗
  Pod-3: 尝试抢 shard-0 ✗，shard-1 ✗，shard-2 ✗，shard-3 ✓

处理分配：
  Pod-1 → shard-0 + shard-1（约50%数据）
  Pod-2 → shard-2（约25%数据）
  Pod-3 → shard-3（约25%数据）
```

这里 Pod-1 多处理了一个分片，是因为它在 Pod-2/3 抢到之前先遍历到了这两个分片。实际上每轮的分配会根据各 Pod 调度精确时间有所不同，但长期看趋于均衡。

### 2.5 SQL 分片查询

```xml
<!-- ConversationMapper.xml -->
<select id="selectActiveByShardIndex"
        resultType="com.aria.conversation.infrastructure.persistence.entity.ConversationEntity">
    SELECT *
    FROM cs_conversation
    WHERE status IN ('WAITING', 'ACTIVE')
      AND MOD(ABS(CRC32(session_id)), #{shardCount}) = #{shardIndex}
</select>
```

CRC32 是 MySQL 内置函数，无需额外索引，计算开销极低。`status` 字段上已有索引（SessionStatus 枚举），过滤效率足够。

## 3. 组件设计

### 3.1 新增类清单

```
infrastructure/
└── scheduler/
    ├── SlaBreachScanScheduler.java   ← 调度入口，分片锁逻辑
    └── SlaBreachDetector.java        ← 单会话 SLA 检测 + 违规处理
```

遵循现有结构：`DocExpiryScheduler` 在 `knowledge-service/infrastructure/scheduler/`，本次在 `conversation-service` 同层新建。

---

### 3.2 `SlaBreachScanScheduler`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachScanScheduler {

    private static final String LOCK_KEY_PREFIX = "lock:scheduler:sla-scan:shard:";
    private static final long   LEASE_SECONDS   = 25L;

    private final RedissonClient        redissonClient;
    private final ConversationMapper    conversationMapper;
    private final SlaBreachDetector     slaBreachDetector;
    private final SlaProperties         slaProperties;   // 见 3.3

    @Scheduled(fixedDelayString = "${sla.scan.interval-ms:30000}",
               initialDelay    = 10_000)
    public void scan() {
        int shardCount = slaProperties.getShardCount();   // 默认 4

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
                List<ConversationEntity> sessions =
                    conversationMapper.selectActiveByShardIndex(shardIndex, shardCount);
                log.debug("[SLA-scan] shard={}/{} sessions={}", shardIndex, shardCount, sessions.size());
                sessions.forEach(slaBreachDetector::check);
            } catch (Exception e) {
                log.error("[SLA-scan] shard={} error", shardIndex, e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
```

---

### 3.3 `SlaProperties` — 配置绑定

```java
@ConfigurationProperties(prefix = "sla")
@Data
public class SlaProperties {
    /** 分片总数，默认 4，运行时修改后下轮生效 */
    private int shardCount = 4;
    /** 扫描间隔（毫秒），默认 30 秒 */
    private long scanIntervalMs = 30_000;
}
```

`application.yml` 中可覆盖：

```yaml
sla:
  shard-count: 4        # 按实际 Pod 数调整，建议 = Pod 数 或 Pod 数的 2 倍
  scan-interval-ms: 30000
```

---

### 3.4 `SlaBreachDetector` — 单会话检测逻辑

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachDetector {

    private final SlaBreachMapper    slaBreachMapper;
    private final SlaPolicyCache     slaPolicyCache;     // Redis 缓存，TTL 5min
    private final SessionEventPublisher eventPublisher;  // 复用现有 SSE 发布通道
    private final SessionQueueService   sessionQueueService;

    public void check(ConversationEntity session) {
        SlaPolicy policy = slaPolicyCache.getActivePolicy();
        if (policy == null || !policy.isEnabled()) return;

        OffsetDateTime now = OffsetDateTime.now();

        // WAIT 违规：排队中超时
        if (session.getStatus() == SessionStatus.WAITING) {
            long waitSec = ChronoUnit.SECONDS.between(session.getStartedAt(), now);
            if (waitSec > policy.getWaitTimeTargetSec()) {
                handleBreach(session, policy, BreachType.WAIT,
                             policy.getWaitTimeTargetSec(), (int) waitSec, now);
            }
        }

        // FRT 违规：已接受但未首响
        if (session.getStatus() == SessionStatus.ACTIVE
                && session.getFirstReplyAt() == null
                && session.getAcceptedAt() != null) {
            long frtSec = ChronoUnit.SECONDS.between(session.getAcceptedAt(), now);
            if (frtSec > policy.getFrtTargetSec()) {
                handleBreach(session, policy, BreachType.FRT,
                             policy.getFrtTargetSec(), (int) frtSec, now);
            }
        }

        // HANDLE 违规：总处理时长超时
        if (session.getStatus() == SessionStatus.ACTIVE
                && session.getAcceptedAt() != null) {
            long handleSec = ChronoUnit.SECONDS.between(session.getAcceptedAt(), now);
            if (handleSec > policy.getHandleTimeTargetSec()) {
                handleBreach(session, policy, BreachType.HANDLE,
                             policy.getHandleTimeTargetSec(), (int) handleSec, now);
            }
        }
    }

    private void handleBreach(ConversationEntity session, SlaPolicy policy,
                               BreachType type, int targetSec, int actualSec,
                               OffsetDateTime now) {
        // 幂等：同一 (sessionId, breachType) 已记录则跳过
        if (slaBreachMapper.existsBySessionAndType(session.getSessionId(), type)) return;

        SlaBreachEntity breach = SlaBreachEntity.builder()
            .sessionId(session.getSessionId())
            .policyId(policy.getId())
            .breachType(type.name())
            .targetSec(targetSec)
            .actualSec(actualSec)
            .breachAt(now)
            .build();

        slaBreachMapper.insert(breach);

        SlaBreachActions actions = policy.getActions();

        // SSE 告警
        if (actions.isSseAlert()) {
            eventPublisher.publishSlaBreachEvent(session, policy, type, targetSec, actualSec);
            slaBreachMapper.updateAlertedAt(breach.getId(), now);
        }

        // 自动升级
        if (actions.isAutoEscalate() && actions.getEscalateToUserId() != null) {
            try {
                sessionQueueService.transfer(session.getSessionId(),
                                             actions.getEscalateToUserId());
                slaBreachMapper.updateEscalatedAt(breach.getId(), now);
            } catch (Exception e) {
                // 目标坐席不在线时降级为仅告警，不抛出
                log.warn("[SLA] autoEscalate failed session={} target={}",
                         session.getSessionId(), actions.getEscalateToUserId(), e);
            }
        }
    }
}
```

---

### 3.5 `SlaPolicyCache` — 策略缓存

```java
@Component
@RequiredArgsConstructor
public class SlaPolicyCache {

    private static final String KEY = "sla:policy:active";
    private final RedisTemplate<String, SlaPolicy> redisTemplate;
    private final SlaPolicyMapper slaPolicyMapper;

    /** 读取启用的策略，优先走 Redis 缓存（TTL 5min） */
    public SlaPolicy getActivePolicy() {
        SlaPolicy cached = redisTemplate.opsForValue().get(KEY);
        if (cached != null) return cached;

        SlaPolicy policy = slaPolicyMapper.selectFirstEnabled();
        if (policy != null) {
            redisTemplate.opsForValue().set(KEY, policy, 5, TimeUnit.MINUTES);
        }
        return policy;
    }

    /** 策略变更时主动失效缓存 */
    public void evict() {
        redisTemplate.delete(KEY);
    }
}
```

`SlaPolicyMapper.selectFirstEnabled()` 查询 `is_enabled=1` 的第一条策略（当前版本只支持单策略，后续扩展多策略时可调整）。

## 4. 故障场景、配置与测试

### 4.1 故障场景分析

#### 场景 1：Pod 在扫描中途宕机

```
Pod-1 持有 shard-0 锁，扫描到一半时进程崩溃
→ Redisson 锁 leaseTime = 25s，到期自动释放
→ 下一轮（最多 30s 后）其他 Pod 正常抢到 shard-0
→ 已处理的会话因幂等检查跳过，未处理的会话在本轮被检测
```

**影响：** 最多延迟一个扫描周期（30s），不丢失也不重复告警。

#### 场景 2：所有 Pod 同时重启

```
所有 Pod 触发 initialDelay = 10s 才开始扫描
→ 重启完成后 10s 内无扫描
→ 不影响 SLA 违规记录的完整性（违规时间戳已由 breach_at 精确记录）
```

**影响：** 最多 40s（重启时间 + 10s 延迟）无告警，SLA 数据完整。

#### 场景 3：分片数变更（如从 4 改为 8）

```
变更 sla.shard-count = 8 并重启 Pod
→ 同一 session_id 在新 shardCount=8 下计算出不同 shardIndex
→ 旧锁 key（shard:0~3）自动 TTL 失效，不影响新锁
→ 重启后第一轮用新分片数正常扫描
```

**影响：** 零停机变更分片数，但需滚动重启（各 Pod 分片数不一致时，同一会话可能被两个 Pod 同时处理，靠幂等保护）。**建议：一次性重启所有 Pod 避免短暂的双重扫描。**

#### 场景 4：Redis 不可用

```
RedissonClient.tryLock() 抛出 RedisException
→ SlaBreachScanScheduler.scan() 捕获异常，记录 ERROR 日志
→ 本轮跳过所有分片
→ Redis 恢复后自动恢复扫描
```

`SlaBreachScanScheduler` 中已有 `catch (Exception e)` 兜底，不影响其他 Bean。

#### 场景 5：`SlaBreachDetector.check()` 单条会话抛出异常

```
某条会话数据异常导致 NPE 或 DB 异常
→ forEach 内异常会中断当前分片剩余会话的处理
```

**修复：** 检测循环改为 try-catch 单条隔离：

```java
sessions.forEach(session -> {
    try {
        slaBreachDetector.check(session);
    } catch (Exception e) {
        log.error("[SLA-scan] check failed session={}", session.getSessionId(), e);
    }
});
```

---

### 4.2 分片数选型建议

| 部署 Pod 数 | 推荐 shardCount | 说明 |
|---|---|---|
| 1 | 1 | 单机退化为单锁，与现有调度器完全一致 |
| 2 | 4 | 每 Pod 平均 2 个分片，均衡且有冗余 |
| 3~4 | 4 | 标准配置 |
| 5~8 | 8 | 保证每 Pod 至少 1 个分片 |
| > 8 | Pod 数 | 避免部分 Pod 永远抢不到分片 |

**原则：** `shardCount ≥ Pod 数`，否则超出分片数的 Pod 每轮都抢不到分片，白白空转。

---

### 4.3 可观测性

**日志：**
```
# 每轮每分片一条 DEBUG 日志（正常情况不刷屏）
[SLA-scan] shard=0/4 sessions=12
[SLA-scan] shard=1/4 sessions=9

# 违规时 INFO 日志
[SLA-breach] session=abc123 type=WAIT target=120s actual=185s policy=默认SLA action=SSE_ALERT

# 分片被其他 Pod 持有时无日志（silent skip，避免 30s 刷一次 WARN）
```

**Actuator 指标（可选，后续扩展）：**
```
sla.scan.sessions.total{shard}   每轮扫描会话数
sla.breach.total{type}           累计违规次数
sla.scan.duration.ms{shard}      每分片扫描耗时
```

---

### 4.4 单元 / 集成测试要点

#### `SlaBreachDetectorTest`

| 测试用例 | 验证点 |
|---|---|
| 等待超时会话 | 插入 WAITING 状态会话，`startedAt` 设为 3 分钟前，断言触发 WAIT 违规 |
| 首响超时会话 | ACTIVE 状态，`acceptedAt` 设为 2 分钟前，`firstReplyAt = null`，断言触发 FRT 违规 |
| 未超时会话 | 等待 30s，断言不触发违规 |
| 幂等保护 | 同一会话同一类型调用两次 `check()`，断言 `cs_sla_breach` 只有一条记录 |
| 策略禁用 | `is_enabled = false`，断言无违规记录 |

#### `SlaBreachScanSchedulerTest`

| 测试用例 | 验证点 |
|---|---|
| 分片路由正确性 | `shardCount=4`，断言 4 个分片覆盖所有活跃会话，无遗漏无重复 |
| 锁竞争跳过 | 模拟 shard-0 锁被持有，断言 scheduler 跳过该分片 |
| Pod 宕机恢复 | 锁到期后，断言下轮正常获取并处理 |

#### 集成测试

用 Testcontainers 启动 MySQL + Redis，模拟 2 个 `SlaBreachScanScheduler` 实例同时运行，断言每条活跃会话恰好被处理一次（通过 `cs_sla_breach` 记录数验证幂等）。

---

### 4.5 与现有调度器的对比

| | `CsatExpiryScheduler` | `SlaBreachScanScheduler` |
|---|---|---|
| 触发频率 | 每 10 分钟 | 每 30 秒 |
| 分片 | 无（单锁） | 有（N 个分片锁） |
| 锁 key | `lock:scheduler:csat-expiry` | `lock:scheduler:sla-scan:shard:{N}` |
| leaseTime | 10 分钟 | 25 秒 |
| 数据量 | 小（CSAT 过期记录） | 小~中（活跃会话 < 500） |
| 幂等保护 | DB 状态 UPDATE | `cs_sla_breach` 存在性检查 |

---

*文档生成时间：2026-07-22*
