# SLA 管理 / 业务时间 / 访客标签与会话备注 — 设计文档

**日期：** 2026-07-22
**作者：** lycodeing
**状态：** 待实现

---

## 1. 概述与背景

### 1.1 现状

Aria 客服系统目前具备 AI 对话、人工坐席队列、知识库检索、CSAT 满意度等核心能力，但缺少以下企业级客服必备功能：

| 功能 | 现状 |
|---|---|
| SLA 管理 | 仅有原始时间字段（`accepted_at`/`first_reply_at`），无策略定义、无违规检测、无告警 |
| 业务时间 | AI 7×24 在线，但无业务时间管控，坐席下班后访客转人工无人接听 |
| 节假日配置 | 完全缺失 |
| 访客标签 | `cs_conversation.tag` 仅为单字符串转人工原因，不支持多标签、跨会话持久标签 |
| 会话备注 | 完全缺失，坐席无法写内部备注 |

### 1.2 目标

1. **SLA 管理**：管理员可定义 SLA 策略（等待时长、首响时长、处理时长阈值），违规时按配置触发：仅记录 / SSE 实时告警 / 自动升级转坐席。
2. **业务时间与离线自动回复**：支持按周排班（每天多时段）+ 节假日例外（含调休补班），非服务时间访客转人工时拦截并返回离线消息。节假日数据自动从 [holiday-cn](https://github.com/NateScarlet/holiday-cn) 同步。
3. **访客标签与会话备注**：两层标签体系（访客持久标签 + 单次会话标签）+ 会话内部备注。标签来源支持预定义字典和坐席自定义，自定义标签可由管理员后续整理入字典。

### 1.3 非目标

- 不实现多语言/多时区（仅支持 `Asia/Shanghai`）
- 不实现 SLA 邮件/短信通知（仅 SSE）
- 不修改 AI 7×24 响应能力，业务时间仅管控转人工路径
- 不实现访客标签的自动化打标（纯人工操作）

### 1.4 系统架构定位

本次三个功能均在 `ai-conversation` 模块内实现，遵循现有 DDD 四层架构：

```
domain/        ← 新增：SlaPolicy, BusinessHours, Tag 领域对象
application/   ← 新增：SlaBreachDetectorService, BusinessHoursService, TagAppService, NoteAppService
infrastructure/ ← 新增：Mapper/Repository + holiday-cn 定时同步
interfaces/    ← 新增/修改：Controller + VO
```

SLA 策略配置、业务时间配置存入 `cs_conversation` schema；标签字典与 auth 无关，同样放 `cs_conversation` schema。

## 2. SLA 管理

### 2.1 数据模型

#### `cs_sla_policy` — SLA 策略定义

```sql
CREATE TABLE cs_sla_policy (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(50)  NOT NULL COMMENT '策略名称',
    is_enabled            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '策略开关',
    wait_time_target_sec  INT          NOT NULL DEFAULT 120 COMMENT '排队等待超时阈值（秒）',
    frt_target_sec        INT          NOT NULL DEFAULT 60  COMMENT '首次响应超时阈值（秒）',
    handle_time_target_sec INT         NOT NULL DEFAULT 1800 COMMENT '会话总处理时长阈值（秒）',
    actions               JSON         NOT NULL COMMENT '违规触发行为配置',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='SLA 策略';
```

`actions` JSON 结构（每条策略独立配置）：

```json
{
  "recordBreachOnly": true,      // 始终为 true，所有违规均记录
  "sseAlert": true,              // true = 违规时通过 SSE 推送给所有在线坐席/管理员
  "autoEscalate": false,         // true = 超时后自动转给指定坐席
  "escalateToUserId": null       // autoEscalate=true 时的目标坐席 userId
}
```

#### `cs_sla_breach` — SLA 违规记录

```sql
CREATE TABLE cs_sla_breach (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL COMMENT '关联会话 sessionId',
    policy_id    BIGINT      NOT NULL COMMENT '触发时使用的策略 ID',
    breach_type  VARCHAR(10) NOT NULL COMMENT 'WAIT | FRT | HANDLE',
    target_sec   INT         NOT NULL COMMENT '策略阈值快照（防策略变更影响历史）',
    actual_sec   INT         NOT NULL COMMENT '实际耗时秒数',
    breach_at    DATETIME    NOT NULL COMMENT '违规发生时间',
    alerted_at   DATETIME             COMMENT 'SSE 推送时间，null 表示未推送',
    escalated_at DATETIME             COMMENT '自动升级时间，null 表示未触发',
    INDEX idx_session_id (session_id),
    INDEX idx_breach_at  (breach_at)
) COMMENT='SLA 违规记录';
```

### 2.2 检测机制

使用 Spring `@Scheduled` 定时任务，每 30 秒扫描一次活跃会话（`status IN (WAITING, ACTIVE)`），对比时间字段与当前时刻：

| 违规类型 | 计算公式 | 前提条件 |
|---|---|---|
| `WAIT` | `now - started_at > wait_time_target_sec` | `status = WAITING` |
| `FRT` | `now - accepted_at > frt_target_sec` | `status = ACTIVE` 且 `first_reply_at IS NULL` |
| `HANDLE` | `now - accepted_at > handle_time_target_sec` | `status = ACTIVE` |

**幂等保证：** 每次扫描前先查 `cs_sla_breach` 是否已有该 `(session_id, breach_type)` 记录，有则跳过，防止重复告警。

**选型理由：** 30 秒精度对 SLA 场景（阈值通常为分钟级）完全够用，且比 RabbitMQ DLX 方案实现更简单，不依赖 Broker 延迟队列配置。

### 2.3 SSE 告警事件

复用现有 `/api/v1/sessions/events` SSE 通道，新增事件类型 `SLA_BREACH`：

```json
{
  "type": "SLA_BREACH",
  "item": { /* 完整 SessionQueueItem */ },
  "breachType": "WAIT",
  "targetSec": 120,
  "actualSec": 185,
  "policyName": "标准 SLA"
}
```

### 2.4 自动升级流程

`autoEscalate=true` 时，检测到违规后调用现有 `SessionQueueService.transfer(sessionId, escalateToUserId)`，同时写 `cs_sla_breach.escalated_at`。若目标坐席不在线，降级为仅 SSE 告警，不报错。

### 2.5 新增 API

#### 策略管理（管理员）

```
GET    /api/v1/admin/sla/policies
POST   /api/v1/admin/sla/policies
PUT    /api/v1/admin/sla/policies/{id}
DELETE /api/v1/admin/sla/policies/{id}
```

请求体（POST/PUT）：
```json
{
  "name": "标准 SLA",
  "isEnabled": true,
  "waitTimeTargetSec": 120,
  "frtTargetSec": 60,
  "handleTimeTargetSec": 1800,
  "actions": {
    "recordBreachOnly": true,
    "sseAlert": true,
    "autoEscalate": false,
    "escalateToUserId": null
  }
}
```

#### 违规记录查询（管理员）

```
GET /api/v1/admin/sla/breaches
```

查询参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sessionId` | String | 按会话筛选 |
| `breachType` | String | WAIT / FRT / HANDLE |
| `startDate` | LocalDate | 开始日期 |
| `endDate` | LocalDate | 结束日期 |
| `page` | int | 页码，默认 1 |
| `pageSize` | int | 每页条数，默认 20，最大 100 |

### 2.6 Dashboard 补充字段

`GET /api/v1/dashboard/overview` 响应追加：

```json
{
  "slaBreachCount": 3,
  "slaBreachRate": 0.12
}
```

`slaBreachRate` = 当日有违规的会话数 / 当日总人工会话数。

## 3. 业务时间与离线自动回复

### 3.1 功能边界

AI 对话保持 7×24 不变，业务时间**仅管控转人工路径**：
- 访客在 AI 对话中主动点"转人工" → `POST /api/v1/chat/transfer`
- AI 意图识别触发自动转人工 → `ChatAppService.stream()` 内部

两条路径都在调用 `SessionQueueService.enqueue()` 前先判断业务时间，非服务时间时拦截并返回离线消息，不入队列。

### 3.2 数据模型

#### `cs_business_hours_schedule` — 每周常规排班

```sql
CREATE TABLE cs_business_hours_schedule (
    day_of_week  TINYINT     NOT NULL PRIMARY KEY COMMENT '1=周一 … 7=周日',
    is_open      TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '当天是否营业',
    time_ranges  JSON        NOT NULL COMMENT '时间段数组',
    timezone     VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='每周排班配置（7条固定记录）';
```

`time_ranges` JSON 结构（支持每天多个时间段，满足午休间隔场景）：
```json
[
  {"start": "09:00", "end": "12:00"},
  {"start": "14:00", "end": "18:00"}
]
```

初始化时用 Flyway 插入 7 条默认记录（周一到周五 9:00-18:00，周六周日关闭）。

#### `cs_business_hours_holiday` — 节假日例外

```sql
CREATE TABLE cs_business_hours_holiday (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    date         DATE        NOT NULL COMMENT '具体日期',
    type         VARCHAR(10) NOT NULL COMMENT 'CLOSED | CUSTOM | WORKDAY',
    time_ranges  JSON                 COMMENT 'type=CUSTOM 时有效',
    remark       VARCHAR(100)         COMMENT '备注，如"国庆节"',
    source       VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO | MANUAL',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (date)
) COMMENT='节假日例外配置';
```

`type` 枚举说明：

| 值 | 含义 |
|---|---|
| `CLOSED` | 强制关闭（法定节假日） |
| `CUSTOM` | 自定义时间段（临时调整营业时间） |
| `WORKDAY` | 调休补班日（holiday-cn 中 `isOffDay=false` 的日期，视为普通工作日） |

#### 离线回复消息

复用现有 `system_config` 表（`type=CUSTOMER_SERVICE`），新增一条记录：

| `config_key` | `config_value` | 说明 |
|---|---|---|
| `agent.offlineMessage` | `"当前不在服务时间，我们将在 {nextOpenTime} 恢复服务，请留下您的问题。"` | 支持 `{nextOpenTime}` 占位符 |

### 3.3 节假日同步：holiday-cn

数据来源：[NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn)，通过 jsDelivr CDN 拉取：

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

**同步策略：**

1. **自动同步**：每年 12 月 1 日 00:00（`@Scheduled cron`）自动拉取次年 JSON，写入 `cs_business_hours_holiday`，`source=AUTO`，已有数据幂等跳过
2. **手动同步**：管理员点击"同步节假日"按钮，调用 `POST /api/v1/admin/business-hours/holidays/sync?year={year}`，立即触发拉取
3. **兜底**：管理员可对单条记录增删改，处理国务院临时补充通知

### 3.4 业务时间判断逻辑

```
BusinessHoursService.isOpen(now: ZonedDateTime): boolean

1. 查 cs_business_hours_holiday，date = today
   - 有记录且 type = CLOSED   → return false
   - 有记录且 type = WORKDAY  → 跳到步骤3（按工作日处理，忽略周表）
   - 有记录且 type = CUSTOM   → 用该记录的 time_ranges 判断当前时间
   - 无记录                   → 继续步骤2

2. 查 cs_business_hours_schedule，day_of_week = today.dayOfWeek
   - is_open = false → return false

3. 遍历 time_ranges，判断 now.time 是否落在任意时间段内
   - 在范围内 → return true
   - 不在范围内 → return false
```

结果缓存 Redis，TTL 设为距当天午夜的剩余秒数（每天自动失效）：

```
key: business_hours:status:{yyyy-MM-dd}
value: true | false
```

### 3.5 集成点

**`SessionQueueService.enqueue()` 前置拦截：**

```java
public SessionQueueItem enqueue(TransferRequest req) {
    if (!businessHoursService.isOpen(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))) {
        throw new ServiceOfflineException(
            systemConfigService.get("agent.offlineMessage")
                .replace("{nextOpenTime}", businessHoursService.nextOpenTime())
        );
    }
    // 原有入队逻辑...
}
```

**`ChatAppService` 捕获异常：**

```java
} catch (ServiceOfflineException e) {
    // 非 SSE 场景：返回错误码 40301
    // SSE 场景：emit event:offline { message: e.getMessage() }
}
```

### 3.6 新增 API

```
# 周排班
GET  /api/v1/admin/business-hours/schedule       # 读取 7 天排班
PUT  /api/v1/admin/business-hours/schedule       # 整体覆盖更新

# 节假日
GET    /api/v1/admin/business-hours/holidays               # 分页列表，支持 year 过滤
POST   /api/v1/admin/business-hours/holidays               # 新增单条
PUT    /api/v1/admin/business-hours/holidays/{id}          # 修改
DELETE /api/v1/admin/business-hours/holidays/{id}          # 删除
POST   /api/v1/admin/business-hours/holidays/sync          # 手动同步 holiday-cn
  Param: year (int, 默认当前年+1)

# 离线回复消息
GET  /api/v1/admin/business-hours/offline-reply    # 读取当前离线消息
PUT  /api/v1/admin/business-hours/offline-reply    # 更新

# 状态查询（访客端/坐席端使用）
GET  /api/v1/business-hours/status
Response: { "open": true, "nextOpenTime": "2026-07-23 09:00" }
```

### 3.7 错误码

| 错误码 | 说明 | 触发场景 |
|---|---|---|
| `40301` | `BUSINESS_HOURS_CLOSED` | 非服务时间转人工 |

前端收到 `40301` 后展示离线留言入口（留言功能为后续迭代，本期仅拦截）。

## 4. 访客标签与会话备注

### 4.1 设计原则

- **两层标签**：访客级（跨会话持久）+ 会话级（单次附加）
- **来源两种**：预定义字典（`PRESET`）+ 坐席自定义（`CUSTOM`），均存入同一张标签字典表
- **现有 `tag` 字段保留**：`cs_conversation.tag` 语义为"转人工原因分类"（如"咨询/投诉"），与标签体系职责不同，不合并
- **备注对访客不可见**：`cs_conversation_note` 仅坐席/管理员可读

### 4.2 数据模型

#### `cs_tag` — 标签字典

```sql
CREATE TABLE cs_tag (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL COMMENT '标签名',
    color       VARCHAR(7)   NOT NULL DEFAULT '#6B7280' COMMENT '十六进制色值',
    source      VARCHAR(10)  NOT NULL DEFAULT 'PRESET' COMMENT 'PRESET | CUSTOM',
    usage_count INT          NOT NULL DEFAULT 0 COMMENT '使用次数（含访客标签+会话标签）',
    created_by  VARCHAR(64)            COMMENT '创建人 userId',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) COMMENT='标签字典';
```

预置标签（Flyway 初始化数据，`source=PRESET`）：

| name | color |
|---|---|
| VIP | #F59E0B |
| 潜在客户 | #10B981 |
| 投诉用户 | #EF4444 |
| 高价值 | #6366F1 |
| 需跟进 | #F97316 |

#### `cs_visitor_tag` — 访客级标签（跨会话持久）

```sql
CREATE TABLE cs_visitor_tag (
    visitor_id  VARCHAR(64) NOT NULL COMMENT '对应 cs_conversation.visitor_id（anonymousId）',
    tag_id      BIGINT      NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64) NOT NULL COMMENT '操作坐席 userId',
    tagged_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (visitor_id, tag_id),
    INDEX idx_visitor_id (visitor_id)
) COMMENT='访客持久标签（跨会话）';
```

#### `cs_conversation_tag` — 会话级标签（单次会话附加）

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

### 4.3 标签自动创建规则

坐席打标签时，若输入的 `name` 在 `cs_tag` 中不存在：
1. 自动创建，`source=CUSTOM`，`created_by=当前坐席`
2. 返回新创建的 `TagVO`（含自动分配的 id 和默认颜色 `#6B7280`）

管理员可在标签字典管理页将 `CUSTOM` 标签改为 `PRESET`（规范化），或删除低频无用标签。删除前检查 `usage_count`，大于 0 时提示确认。

### 4.4 VO 定义

```java
// 标签
record TagVO(Long id, String name, String color, String source) {}

// 备注
record NoteVO(Long id, String content, String createdBy,
              OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

// SLA 实时状态（追加进 SessionQueueItem）
record SlaStatusVO(
    boolean waitBreached, boolean frtBreached, boolean handleBreached,
    Long waitRemainSec,   // null 表示已接受
    Long frtRemainSec,    // null 表示已首响
    Long handleRemainSec  // null 表示已结束
) {}
```

### 4.5 现有接口改造

#### `SessionQueueItem` 追加字段

```json
{
  "sessionId": "...",
  "userName": "...",
  "transferReason": "...",
  "tag": "...",
  "waitSince": 1753200000,
  "status": "ACTIVE",
  "agentId": "user_001",

  // 新增 ↓
  "visitorTags": [
    {"id": 1, "name": "VIP", "color": "#F59E0B", "source": "PRESET"}
  ],
  "slaStatus": {
    "waitBreached": false,
    "frtBreached": false,
    "handleBreached": false,
    "waitRemainSec": null,
    "frtRemainSec": 25,
    "handleRemainSec": 1650
  }
}
```

`visitorTags` 从 Redis 缓存读取（key: `visitor:tags:{visitorId}`），标签增删时同步刷新，TTL 24h。

#### 新增 `GET /api/v1/sessions/{sessionId}` — 会话详情

坐席打开会话时调用，一次性拿到完整信息：

```json
{
  "sessionId": "...",
  "visitorName": "...",
  "visitorId": "...",
  "visitorIp": "...",
  "visitorDevice": "Mozilla/5.0 ...",
  "transferReason": "...",
  "tag": "...",
  "status": "ACTIVE",
  "agentId": "...",
  "startedAt": "2026-07-22T09:00:00+08:00",
  "acceptedAt": "2026-07-22T09:02:10+08:00",
  "firstReplyAt": "2026-07-22T09:02:45+08:00",
  "endedAt": null,
  "closedBy": null,
  "visitorTags": [...],
  "sessionTags": [...],
  "notes": [...],
  "slaStatus": {...}
}
```

#### `VisitorHistoryVO` 追加字段

```json
{
  // 现有字段不变...
  "visitorTags": [...],
  "sessionTags": [...],
  "notes": [...],
  "slaBreached": true
}
```

#### SSE 新增事件 `TAG_UPDATED`

多坐席协作时实时同步标签变化：

```json
{
  "type": "TAG_UPDATED",
  "sessionId": "...",
  "visitorId": "...",
  "visitorTags": [...],
  "sessionTags": [...]
}
```

### 4.6 新增 API

#### 标签字典管理（管理员）

```
GET    /api/v1/admin/tags              # 列表，支持 source 过滤
POST   /api/v1/admin/tags              # 新建预定义标签
PUT    /api/v1/admin/tags/{id}         # 修改名称/颜色/source
DELETE /api/v1/admin/tags/{id}         # 删除（usage_count>0 时需前端二次确认）
```

#### 访客级标签（坐席操作）

```
GET    /api/v1/sessions/{sessionId}/visitor/tags              # 查询访客当前所有标签
POST   /api/v1/sessions/{sessionId}/visitor/tags              # 打标签
  Body: { "tagId": 1 } 或 { "tagName": "新标签名" }          # id 和 name 二选一
DELETE /api/v1/sessions/{sessionId}/visitor/tags/{tagId}      # 移除标签
```

#### 会话级标签（坐席操作）

```
GET    /api/v1/sessions/{sessionId}/tags
POST   /api/v1/sessions/{sessionId}/tags
  Body: { "tagId": 1 } 或 { "tagName": "新标签名" }
DELETE /api/v1/sessions/{sessionId}/tags/{tagId}
```

#### 会话备注（坐席操作）

```
GET    /api/v1/sessions/{sessionId}/notes                     # 查询该会话所有备注
POST   /api/v1/sessions/{sessionId}/notes                     # 新增备注
  Body: { "content": "访客是 CEO，需要特别关注" }
PUT    /api/v1/sessions/{sessionId}/notes/{noteId}            # 修改备注（仅本人可改）
DELETE /api/v1/sessions/{sessionId}/notes/{noteId}            # 删除备注（仅本人/管理员）
```

### 4.7 历史会话搜索（新增）

当前 `GET /api/v1/sessions?closedLimit=N` 无任何过滤能力，补充管理端历史搜索接口：

```
GET /api/v1/admin/sessions
```

查询参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `tagId` | Long | 按标签筛选（访客标签或会话标签均命中）|
| `agentId` | String | 按坐席筛选 |
| `status` | String | AI_CHAT / WAITING / ACTIVE / CLOSED |
| `startDate` | LocalDate | 开始日期（按 started_at）|
| `endDate` | LocalDate | 结束日期 |
| `keyword` | String | 访客名模糊搜索 |
| `page` | int | 默认 1 |
| `pageSize` | int | 默认 20，最大 100 |

响应：`Page<AdminSessionVO>`，`AdminSessionVO` 在 `RecentSessionVO` 基础上追加 `visitorTags`、`sessionTags`。

## 5. 接口改造汇总

### 5.1 改动影响矩阵

| 接口 / VO | 改动类型 | 是否破坏性 | 影响范围 |
|---|---|---|---|
| `SessionQueueItem` VO | 追加 `visitorTags`、`slaStatus` 字段 | 向后兼容 | 所有返回该 VO 的接口 |
| `POST /api/v1/chat/transfer` | 新增错误码 `40301` | 前端需处理新错误码 | 访客端转人工流程 |
| `GET /api/v1/chat/stream` (SSE) | 新增 `offline` 事件 | 向后兼容，忽略未知 event 即可 | 访客端 SSE 处理 |
| `GET /api/v1/sessions/events` (SSE) | 新增 `SLA_BREACH`、`TAG_UPDATED` 事件类型 | 向后兼容 | 坐席端 SSE 处理 |
| `GET /api/v1/sessions/visitor-history` | `VisitorHistoryVO` 追加字段 | 向后兼容 | 坐席端访客历史面板 |
| `GET /api/v1/dashboard/overview` | 追加 `slaBreachCount`、`slaBreachRate` | 向后兼容 | 管理员 Dashboard |

### 5.2 全部接口清单（含新增）

#### ai-conversation 模块新增 Controller

| Controller | 路径前缀 | 说明 |
|---|---|---|
| `SlaController` | `/api/v1/admin/sla` | SLA 策略 CRUD + 违规查询 |
| `BusinessHoursController` | `/api/v1/admin/business-hours` | 排班/节假日/离线消息 |
| `BusinessHoursStatusController` | `/api/v1/business-hours` | 对外状态查询（访客/坐席） |
| `TagAdminController` | `/api/v1/admin/tags` | 标签字典管理 |
| `SessionTagController` | `/api/v1/sessions/{id}/tags` | 会话标签操作 |
| `VisitorTagController` | `/api/v1/sessions/{id}/visitor/tags` | 访客标签操作 |
| `SessionNoteController` | `/api/v1/sessions/{id}/notes` | 会话备注 CRUD |
| `AdminSessionController`（已有，扩展） | `/api/v1/admin/sessions` | 新增历史会话搜索 |
| `SessionDetailController`（新增） | `/api/v1/sessions/{id}` | 单会话详情 |

#### 完整端点列表

```
# SLA
GET    /api/v1/admin/sla/policies
POST   /api/v1/admin/sla/policies
PUT    /api/v1/admin/sla/policies/{id}
DELETE /api/v1/admin/sla/policies/{id}
GET    /api/v1/admin/sla/breaches

# 业务时间
GET    /api/v1/admin/business-hours/schedule
PUT    /api/v1/admin/business-hours/schedule
GET    /api/v1/admin/business-hours/holidays
POST   /api/v1/admin/business-hours/holidays
PUT    /api/v1/admin/business-hours/holidays/{id}
DELETE /api/v1/admin/business-hours/holidays/{id}
POST   /api/v1/admin/business-hours/holidays/sync
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

# 访客标签
GET    /api/v1/sessions/{sessionId}/visitor/tags
POST   /api/v1/sessions/{sessionId}/visitor/tags
DELETE /api/v1/sessions/{sessionId}/visitor/tags/{tagId}

# 会话标签
GET    /api/v1/sessions/{sessionId}/tags
POST   /api/v1/sessions/{sessionId}/tags
DELETE /api/v1/sessions/{sessionId}/tags/{tagId}

# 会话备注
GET    /api/v1/sessions/{sessionId}/notes
POST   /api/v1/sessions/{sessionId}/notes
PUT    /api/v1/sessions/{sessionId}/notes/{noteId}
DELETE /api/v1/sessions/{sessionId}/notes/{noteId}

# 历史会话搜索（扩展现有 AdminSessionController）
GET    /api/v1/admin/sessions
```

### 5.3 权限约定

遵循现有 Sa-Token + 权限 key 体系，新增以下权限 key：

| 权限 key | 适用角色 | 说明 |
|---|---|---|
| `system:sla:manage` | super_admin / kf_manager | SLA 策略 CRUD |
| `system:sla:view` | super_admin / kf_manager | 违规记录查看 |
| `system:biz-hours:manage` | super_admin / kf_manager | 业务时间配置 |
| `system:tag:manage` | super_admin / kf_manager | 标签字典 CRUD |
| `session:tag:write` | kf_staff | 打/移除标签 |
| `session:note:write` | kf_staff | 写备注 |
| `session:note:delete:own` | kf_staff | 删除自己的备注 |
| `session:note:delete:any` | super_admin / kf_manager | 删除任意备注 |

### 5.4 Redis 缓存 Key 约定

| Key | TTL | 说明 |
|---|---|---|
| `visitor:tags:{visitorId}` | 24h | 访客标签列表缓存，标签变更时 invalidate |
| `business_hours:status:{yyyy-MM-dd}` | 距当天午夜剩余秒数 | 当天营业状态缓存 |
| `sla:policy:active` | 5min | 当前启用的 SLA 策略缓存（避免每次扫描都查 DB）|

### 5.5 SSE 事件枚举扩展

现有 `SessionEventType` 枚举追加：

```java
public enum SessionEventType {
    // 现有
    ENQUEUE, ACCEPTED, CLOSED, TRANSFER,
    // 新增
    SLA_BREACH,    // SLA 违规告警
    TAG_UPDATED    // 标签变更通知
}
```

访客侧 SSE 新增 event 名：

| event name | data shape | 触发时机 |
|---|---|---|
| `offline` | `{"message":"...","nextOpenTime":"..."}` | 非服务时间转人工 |

## 6. 数据库变更清单

### 6.1 Flyway 迁移版本规划

在现有最新版本之上，按功能独立建文件：

| 版本号 | 文件名 | 内容 |
|---|---|---|
| V5 | `V5__add_sla_tables.sql` | `cs_sla_policy` + `cs_sla_breach` |
| V6 | `V6__add_business_hours_tables.sql` | `cs_business_hours_schedule` + `cs_business_hours_holiday` + system_config 初始数据 |
| V7 | `V7__add_tag_and_note_tables.sql` | `cs_tag` + `cs_visitor_tag` + `cs_conversation_tag` + `cs_conversation_note` + 预置标签数据 |

每个迁移文件独立，方便回滚定位。

### 6.2 V5 — SLA 表

```sql
-- V5__add_sla_tables.sql

CREATE TABLE cs_sla_policy (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                   VARCHAR(50)  NOT NULL                        COMMENT '策略名称',
    is_enabled             TINYINT(1)   NOT NULL DEFAULT 1              COMMENT '是否启用',
    wait_time_target_sec   INT          NOT NULL DEFAULT 120            COMMENT '排队等待超时（秒）',
    frt_target_sec         INT          NOT NULL DEFAULT 60             COMMENT '首次响应超时（秒）',
    handle_time_target_sec INT          NOT NULL DEFAULT 1800           COMMENT '处理总时长超时（秒）',
    actions                JSON         NOT NULL                        COMMENT '违规行为配置 JSON',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='SLA 策略';

-- 默认策略
INSERT INTO cs_sla_policy (name, is_enabled, wait_time_target_sec, frt_target_sec,
    handle_time_target_sec, actions)
VALUES ('默认 SLA', 1, 120, 60, 1800,
    '{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false,"escalateToUserId":null}');

CREATE TABLE cs_sla_breach (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64)  NOT NULL COMMENT '关联 sessionId',
    policy_id    BIGINT       NOT NULL COMMENT '触发策略 ID',
    breach_type  VARCHAR(10)  NOT NULL COMMENT 'WAIT | FRT | HANDLE',
    target_sec   INT          NOT NULL COMMENT '阈值快照（秒）',
    actual_sec   INT          NOT NULL COMMENT '实际耗时（秒）',
    breach_at    DATETIME     NOT NULL COMMENT '违规时间',
    alerted_at   DATETIME              COMMENT 'SSE 告警时间',
    escalated_at DATETIME              COMMENT '自动升级时间',
    INDEX idx_session_id (session_id),
    INDEX idx_breach_at  (breach_at)
) COMMENT='SLA 违规记录';
```

### 6.3 V6 — 业务时间表

```sql
-- V6__add_business_hours_tables.sql

CREATE TABLE cs_business_hours_schedule (
    day_of_week  TINYINT      NOT NULL PRIMARY KEY COMMENT '1=周一 … 7=周日',
    is_open      TINYINT(1)   NOT NULL DEFAULT 1   COMMENT '当天是否营业',
    time_ranges  JSON         NOT NULL              COMMENT '[{"start":"09:00","end":"18:00"}]',
    timezone     VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='每周排班配置';

-- 初始化 7 天数据：周一到周五 9-18，周六周日关闭
INSERT INTO cs_business_hours_schedule (day_of_week, is_open, time_ranges) VALUES
    (1, 1, '[{"start":"09:00","end":"18:00"}]'),
    (2, 1, '[{"start":"09:00","end":"18:00"}]'),
    (3, 1, '[{"start":"09:00","end":"18:00"}]'),
    (4, 1, '[{"start":"09:00","end":"18:00"}]'),
    (5, 1, '[{"start":"09:00","end":"18:00"}]'),
    (6, 0, '[]'),
    (7, 0, '[]');

CREATE TABLE cs_business_hours_holiday (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    date        DATE         NOT NULL COMMENT '具体日期',
    type        VARCHAR(10)  NOT NULL COMMENT 'CLOSED | CUSTOM | WORKDAY',
    time_ranges JSON                  COMMENT 'type=CUSTOM 时有效',
    remark      VARCHAR(100)          COMMENT '备注',
    source      VARCHAR(10)  NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO | MANUAL',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (date)
) COMMENT='节假日例外配置';

-- 离线回复消息（写入 system_config，复用现有表）
-- 注意：system_config 表结构为 (config_key, config_value, config_type, ...)
INSERT INTO system_config (config_key, config_value, config_type, remark)
VALUES ('agent.offlineMessage',
        '您好，当前不在服务时间，我们将尽快回复您，感谢您的耐心等待。',
        'CUSTOMER_SERVICE', '非服务时间离线自动回复消息');
```

### 6.4 V7 — 标签与备注表

```sql
-- V7__add_tag_and_note_tables.sql

CREATE TABLE cs_tag (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL                        COMMENT '标签名',
    color       VARCHAR(7)   NOT NULL DEFAULT '#6B7280'      COMMENT '十六进制色值',
    source      VARCHAR(10)  NOT NULL DEFAULT 'PRESET'       COMMENT 'PRESET | CUSTOM',
    usage_count INT          NOT NULL DEFAULT 0              COMMENT '使用次数',
    created_by  VARCHAR(64)                                  COMMENT '创建人 userId',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) COMMENT='标签字典';

-- 预置标签
INSERT INTO cs_tag (name, color, source) VALUES
    ('VIP',    '#F59E0B', 'PRESET'),
    ('潜在客户', '#10B981', 'PRESET'),
    ('投诉用户', '#EF4444', 'PRESET'),
    ('高价值',  '#6366F1', 'PRESET'),
    ('需跟进',  '#F97316', 'PRESET');

CREATE TABLE cs_visitor_tag (
    visitor_id  VARCHAR(64)  NOT NULL COMMENT 'anonymousId',
    tag_id      BIGINT       NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64)  NOT NULL COMMENT '坐席 userId',
    tagged_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (visitor_id, tag_id),
    INDEX idx_visitor_id (visitor_id)
) COMMENT='访客持久标签';

CREATE TABLE cs_conversation_tag (
    session_id  VARCHAR(64)  NOT NULL COMMENT 'sessionId',
    tag_id      BIGINT       NOT NULL COMMENT 'FK → cs_tag.id',
    tagged_by   VARCHAR(64)  NOT NULL COMMENT '坐席 userId',
    tagged_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, tag_id),
    INDEX idx_session_id (session_id)
) COMMENT='会话级标签';

CREATE TABLE cs_conversation_note (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL COMMENT 'sessionId',
    content     TEXT         NOT NULL COMMENT '备注内容（对访客不可见）',
    created_by  VARCHAR(64)  NOT NULL COMMENT '坐席 userId',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) COMMENT='会话内部备注';
```

### 6.5 变更影响评估

| 现有表 | 变更 | 说明 |
|---|---|---|
| `cs_conversation` | 无结构变更 | `tag` 字段保留，语义不变 |
| `system_config` | 新增一行数据 | `agent.offlineMessage` |
| 其余现有表 | 无变更 | — |

### 6.6 回滚方案

每个 Flyway 版本对应 undo 脚本（存放于 `src/main/resources/db/migration/undo/`）：

```sql
-- U7__add_tag_and_note_tables.sql
DROP TABLE IF EXISTS cs_conversation_note;
DROP TABLE IF EXISTS cs_conversation_tag;
DROP TABLE IF EXISTS cs_visitor_tag;
DROP TABLE IF EXISTS cs_tag;

-- U6__add_business_hours_tables.sql
DROP TABLE IF EXISTS cs_business_hours_holiday;
DROP TABLE IF EXISTS cs_business_hours_schedule;
DELETE FROM system_config WHERE config_key = 'agent.offlineMessage';

-- U5__add_sla_tables.sql
DROP TABLE IF EXISTS cs_sla_breach;
DROP TABLE IF EXISTS cs_sla_policy;
```

> 注意：Flyway Community 版不支持自动执行 undo，以上脚本作为手动回滚参考。

---

## 7. 实现优先级建议

按依赖关系和业务价值排序：

| 优先级 | 功能 | 理由 |
|---|---|---|
| P0 | 业务时间 + 离线回复 | 影响现有用户体验，坐席下班后必须有兜底 |
| P1 | 访客标签 + 会话备注 | 高频操作需求，坐席日常使用 |
| P2 | SLA 管理 | 管理层需求，不影响日常服务流程 |
| P3 | 历史会话搜索 | 依赖标签体系，且现有 Dashboard 有部分覆盖 |

---

*文档生成时间：2026-07-22*
