# ARIA 客服增强功能设计文档

> 文档版本：v1.0  
> 日期：2026-07-16  
> 状态：草稿

---

## 1. 背景与目标

### 1.1 背景

ARIA 已具备 AI 对话、RAG 知识库、DIT 意图路由、人机协作等核心能力，对比 Intercom、Zendesk、Freshdesk、网易七鱼等主流智能客服平台，以下五项功能属于企业级客服系统的**必要配置**，目前尚未实现：

| # | 功能 | 对标平台 |
|---|------|---------|
| 1 | 满意度评价（CSAT） | Intercom、Zendesk、网易七鱼 |
| 2 | 坐席快捷回复（Canned Responses） | Intercom、Crisp、Chatwoot |
| 3 | SLA 管理 | Zendesk、Freshdesk、Salesforce Service Cloud |
| 4 | 业务时间 & 离线自动回复 | 几乎所有主流平台 |
| 5 | 访客标签 & 会话备注 | Chatwoot、Freshdesk、Zoho Desk |

### 1.2 目标

- 五项功能均在现有三服务架构（auth-service / knowledge-service / conversation-service）内实现，不新增服务
- 数据模型通过 Flyway 迁移脚本管理，零人工干预
- 所有管理类配置 API 遵循现有 RBAC 权限体系（`@SaCheckPermission`）
- 新功能对现有对话主链路（`ChatAppService.stream`）的延迟影响 < 5ms

### 1.3 非目标

- 前端 UI 实现（仅提供后端 API）
- 离线渠道（邮件、短信）发送集成（预留接口，不实现发送）
- 多租户隔离（当前版本为单租户）

---

## 2. 模块归属与分层

所有功能均落在 **conversation-service**（端口 8082），遵循现有 DDD 四层架构：

```
interfaces/rest/        ← REST Controller（新增）
application/service/    ← 用例编排（新增 Service 类）
domain/                 ← 领域模型（新增聚合根、领域事件）
infrastructure/         ← 持久化（新增 Mapper、DO、Flyway）
```

**auth-service** 仅在以下场景扩展：
- SLA 规则管理 API（归属系统配置，复用 `SystemConfigService`）
- 业务时间配置 API（同上）

---

## 3. 术语定义

| 术语 | 说明 |
|------|------|
| CSAT | Customer Satisfaction Score，会话结束后的用户满意度评分 |
| Canned Response | 预存的快捷回复模板，坐席 `/` 触发搜索后插入 |
| SLA | Service Level Agreement，服务等级协议，定义响应时限并跟踪违约 |
| 业务时间 | 人工坐席的在线服务时段，非业务时间转人工走离线留言 |
| 访客标签 | 坐席手动为访客打的分类标签（VIP / 潜客 / 投诉等） |
| 会话备注 | 坐席在会话内写的对访客不可见的内部记录 |

## 4. 满意度评价（CSAT）

### 4.1 功能描述

会话被关闭（人工坐席主动关闭 或 AI 会话自然结束）后，系统向访客推送一次评价邀请。访客选择星级（1–5）并可附文字说明。评价数据汇入运营看板，支持按时段、坐席、意图维度分析。

**触发场景：**

| 场景 | 触发时机 | 推送方式 |
|------|---------|---------|
| 人工会话关闭 | 坐席调用 `POST /sessions/{id}/close` 后 | WebSocket `event:csat_request` |
| AI 会话结束 | SSE `event:done` 推送后（可配置延迟秒数） | SSE `event:csat_request` |
| 访客主动离开 | WebSocket 断开后 N 秒（可配置，默认 30s） | 下次重连时推送 |

### 4.2 业务规则

1. **每次会话最多评价一次**：已评价的会话再次触发推送时静默忽略。
2. **评价窗口**：评价邀请发出后 24 小时内有效，超时自动标记 `EXPIRED`。
3. **匿名允许**：访客 Token 未登录时也可评价，使用 `sessionId` 关联。
4. **评价不可修改**：提交后不可撤销，设计简单防作弊。
5. **可配置是否启用**：系统配置项 `csat.enabled`，默认 `true`；可按 Domain 级别独立开关。

### 4.3 领域模型

```
CsatRating（满意度评价聚合根）
├── id            bigint PK
├── session_id    varchar(64) NOT NULL UNIQUE  会话唯一关联
├── visitor_id    varchar(64)                  访客标识（可匿名）
├── agent_id      bigint                       接待坐席（AI 会话为 null）
├── score         smallint NOT NULL CHECK(score BETWEEN 1 AND 5)
├── comment       text                         文字说明（可空）
├── channel       varchar(20)                  HUMAN / AI
├── status        varchar(20) NOT NULL         PENDING / RATED / EXPIRED / SKIPPED
├── requested_at  timestamptz NOT NULL         邀请发出时间
├── rated_at      timestamptz                  评价提交时间
└── expired_at    timestamptz                  邀请过期时间（requested_at + 24h）
```

### 4.4 状态机

```
            会话关闭
               │
               ▼
           PENDING ──── 超时 ──────► EXPIRED
               │
         ┌─────┴──────┐
      用户评价       用户跳过
         │              │
         ▼              ▼
       RATED          SKIPPED
```

### 4.5 SSE / WebSocket 事件协议

**推送评价邀请（conversation-service → 前端）：**

```json
event: csat_request
data: {
  "csatId": "123",
  "sessionId": "sess_abc",
  "message": "请对本次服务进行评价",
  "expiresAt": "2026-07-17T10:00:00Z"
}
```

**访客提交评价（前端 → REST API）：**

```
POST /api/v1/chat/csat/{csatId}/rate
{
  "score": 5,
  "comment": "回答很详细，非常满意"
}
```

**访客跳过评价：**

```
POST /api/v1/chat/csat/{csatId}/skip
```

### 4.6 看板指标扩展

在 `DashboardOverviewVO` 新增：

| 字段 | 说明 |
|------|------|
| `csatAvgScore` | 近 30 天平均 CSAT 分 |
| `csatResponseRate` | 近 30 天评价响应率（RATED / (RATED + EXPIRED + SKIPPED)） |
| `csatDistribution` | 1–5 星各档分布（饼图数据） |

新增独立端点：

```
GET /api/v1/dashboard/csat-trend         按天聚合的 CSAT 均分趋势
GET /api/v1/dashboard/csat-distribution  星级分布
GET /api/v1/dashboard/csat-by-agent      分坐席 CSAT 排名
```

### 4.7 定时任务

`CsatExpiryScheduler`：每隔 10 分钟扫描 `status=PENDING` 且 `expired_at < NOW()` 的记录，批量更新为 `EXPIRED`。使用 Redisson 分布式锁防止多实例重复执行。

## 5. 坐席快捷回复（Canned Responses）

### 5.1 功能描述

坐席在人工接待界面输入 `/` 后弹出快捷回复搜索框，按关键词或标题筛选预存模板，选中后自动填入输入框（不自动发送，坐席可二次编辑）。管理员在后台维护快捷回复库，支持分组管理。

**核心交互流程：**

```
坐席输入 "/" → 前端弹出搜索框 → 输入关键词 → 
调用 GET /api/v1/canned-responses/search?q=xxx → 
展示列表 → 坐席选择 → 内容填入输入框 → 坐席确认发送
```

### 5.2 业务规则

1. **分组管理**：快捷回复按组归类（如"售后处理"、"账号问题"、"通用问候"），最多支持 3 级分组，默认分组为"未分类"。
2. **关键词搜索**：搜索范围覆盖标题 + 正文内容（全文检索），支持拼音首字母简拼（可选，后期扩展）。
3. **变量替换**：模板正文支持 `{{visitor_name}}`、`{{agent_name}}`、`{{session_id}}` 等占位符，填入时自动替换。
4. **访问权限**：
   - 普通坐席：可使用所有 `scope=PUBLIC` 的快捷回复；可创建 `scope=PRIVATE` 的个人模板（仅自己可用）。
   - 客服管理员：可管理公共快捷回复库（CRUD）。
5. **使用统计**：每次插入记录 `use_count++`，看板可展示最常用的快捷回复 Top 10。
6. **软删除**：删除快捷回复不影响历史统计，`deleted=true` 逻辑删除。

### 5.3 领域模型

```
CannedResponseGroup（分组）
├── id          bigint PK
├── name        varchar(64) NOT NULL
├── parent_id   bigint                    父分组（null = 根分组）
├── sort_order  int DEFAULT 0
├── created_by  bigint NOT NULL
└── deleted     boolean DEFAULT false

CannedResponse（快捷回复）
├── id           bigint PK
├── group_id     bigint                   所属分组（null = 未分类）
├── title        varchar(128) NOT NULL    标题（搜索命中依据之一）
├── content      text NOT NULL            正文，支持 {{变量}} 占位符
├── scope        varchar(16) NOT NULL     PUBLIC / PRIVATE
├── owner_id     bigint                   PRIVATE 时的所属坐席 ID
├── use_count    int DEFAULT 0            使用次数统计
├── sort_order   int DEFAULT 0
├── created_by   bigint NOT NULL
├── created_at   timestamptz NOT NULL
├── updated_at   timestamptz NOT NULL
└── deleted      boolean DEFAULT false
```

### 5.4 支持的变量占位符

| 占位符 | 替换内容 | 来源 |
|--------|---------|------|
| `{{visitor_name}}` | 访客昵称或"访客" | session 上下文 |
| `{{agent_name}}` | 当前坐席姓名 | Sa-Token 当前登录用户 |
| `{{session_id}}` | 当前会话 ID | path/query 参数 |
| `{{current_time}}` | 当前时间（HH:mm） | 服务端时间 |
| `{{current_date}}` | 当前日期（yyyy-MM-dd） | 服务端时间 |

变量替换在**前端**完成（后端仅存储原始模板），后端搜索接口返回原始内容，前端在插入前替换。

### 5.5 搜索接口设计

```
GET /api/v1/canned-responses/search
Query Params:
  q          string    关键词（标题 + 内容模糊匹配）
  group_id   long?     限定分组（含子分组）
  limit      int       返回条数，默认 10，最大 30

Response:
[
  {
    "id": 1,
    "title": "感谢您的耐心等待",
    "content": "您好 {{visitor_name}}，感谢您的耐心等待，我来为您处理。",
    "groupName": "通用问候",
    "scope": "PUBLIC",
    "useCount": 432
  }
]
```

搜索逻辑：`WHERE (title ILIKE '%q%' OR content ILIKE '%q%') AND deleted=false AND (scope='PUBLIC' OR (scope='PRIVATE' AND owner_id=:agentId))` + 按 `use_count DESC` 排序。

### 5.6 管理端 API

```
# 分组管理（需要 canned-response:manage 权限）
GET    /api/v1/admin/canned-response-groups          分组树形列表
POST   /api/v1/admin/canned-response-groups          新建分组
PUT    /api/v1/admin/canned-response-groups/{id}     编辑分组
DELETE /api/v1/admin/canned-response-groups/{id}     删除分组（需无子项）

# 快捷回复管理
GET    /api/v1/admin/canned-responses                分页列表（支持 groupId/scope 过滤）
POST   /api/v1/admin/canned-responses                新建
PUT    /api/v1/admin/canned-responses/{id}           编辑
DELETE /api/v1/admin/canned-responses/{id}           软删除

# 使用记录（坐席插入时调用，用于统计）
POST   /api/v1/canned-responses/{id}/use             记录一次使用（use_count+1）
```

## 6. SLA 管理

### 6.1 功能描述

SLA（Service Level Agreement）允许管理员定义服务响应时限规则，系统实时跟踪每条会话是否在限时内完成首次响应和会话关闭，超时时触发告警（WebSocket 推送给坐席 + 记录违约日志）。这是企业采购智能客服系统的必考项。

**三个核心指标：**

| 指标 | 说明 | 业界标准 |
|------|------|---------|
| FRT（First Response Time） | 从访客发出第一条消息到坐席/AI 首次回复的时长 | ≤ 2 分钟（人工）/ ≤ 5 秒（AI） |
| NRT（Next Response Time） | 会话进行中坐席每次回复的最大间隔 | ≤ 5 分钟 |
| RT（Resolution Time） | 从会话创建到关闭的总时长 | ≤ 30 分钟（视业务） |

### 6.2 业务规则

1. **规则优先级**：支持多条 SLA 规则，按 `priority` 字段排序，优先匹配第一条满足条件的规则。
2. **匹配维度**：可按 `channel`（HUMAN/AI）、`tag`（访客标签）、`domain_code` 配置不同的规则。
3. **暂停计时**：业务时间之外不计入 SLA 时钟（需与业务时间模块联动，详见第 7 章）。
4. **分级告警**：支持配置"预警阈值"（如已用 80% 时限）和"违约阈值"（100%），分别推送不同等级的告警。
5. **违约记录**：每次违约自动写入 `sla_breach_log`，用于看板统计和追责。
6. **AI 会话豁免**：默认 AI 会话不受 FRT/NRT 约束（AI 响应足够快），但 RT 仍可配置。

### 6.3 领域模型

```
SlaRule（SLA 规则）
├── id                  bigint PK
├── name                varchar(64) NOT NULL       规则名称
├── priority            int NOT NULL DEFAULT 0     数字越小优先级越高
├── channel             varchar(16)                HUMAN / AI / null（不限）
├── domain_code         varchar(64)                绑定域（null = 全域）
├── frt_minutes         int                        首响时限（分钟，null=不限）
├── nrt_minutes         int                        二次响应时限（分钟，null=不限）
├── rt_minutes          int                        解决时限（分钟，null=不限）
├── warn_percent        int DEFAULT 80             预警触发阈值（%）
├── respect_business_hours boolean DEFAULT true   是否仅计业务时间
├── enabled             boolean DEFAULT true
├── created_by          bigint NOT NULL
└── created_at          timestamptz NOT NULL

SlaTracker（会话 SLA 跟踪记录，1:1 关联会话）
├── id                  bigint PK
├── session_id          varchar(64) NOT NULL UNIQUE
├── rule_id             bigint NOT NULL             命中的规则
├── frt_deadline        timestamptz                首响截止时间
├── rt_deadline         timestamptz                解决截止时间
├── frt_achieved_at     timestamptz                实际首响时间
├── rt_achieved_at      timestamptz                实际解决时间
├── frt_status          varchar(16)                PENDING / MET / BREACHED
├── rt_status           varchar(16)                PENDING / MET / BREACHED
└── created_at          timestamptz NOT NULL

SlaBreachLog（违约记录）
├── id                  bigint PK
├── session_id          varchar(64) NOT NULL
├── rule_id             bigint NOT NULL
├── breach_type         varchar(16) NOT NULL        FRT / NRT / RT
├── deadline            timestamptz NOT NULL
├── actual_at           timestamptz                 实际完成时间（null=尚未完成）
├── delay_seconds       int                         超时秒数
├── agent_id            bigint                      责任坐席
└── created_at          timestamptz NOT NULL
```

### 6.4 SLA 生命周期

```
会话创建
   │
   ├─► 匹配 SLA 规则 → 创建 SlaTracker，计算 frt_deadline / rt_deadline
   │
   ├─► 坐席/AI 首次回复 → 更新 frt_achieved_at，标记 frt_status=MET（或 BREACHED）
   │
   ├─► 定时检查（每分钟）→ 扫描 PENDING 且 deadline < NOW() 的 Tracker
   │         ├─ 预警区间（已用 warn_percent%）→ 推送 WebSocket 告警（warn 级）
   │         └─ 超过 deadline → 写入 SlaBreachLog，推 WebSocket 告警（breach 级）
   │
   └─► 会话关闭 → 更新 rt_achieved_at，最终标记 rt_status
```

### 6.5 实时告警推送

通过坐席 WebSocket 通道（`/ws/agent`）推送告警事件：

```json
{
  "type": "SLA_WARN",
  "sessionId": "sess_abc",
  "breachType": "FRT",
  "deadline": "2026-07-16T10:02:00Z",
  "remainingSeconds": 24,
  "level": "WARN"
}
```

```json
{
  "type": "SLA_BREACH",
  "sessionId": "sess_abc",
  "breachType": "RT",
  "deadline": "2026-07-16T10:30:00Z",
  "delaySeconds": 180,
  "level": "BREACH"
}
```

### 6.6 管理端 API

```
# SLA 规则管理（需要 sla:manage 权限）
GET    /api/v1/admin/sla-rules          规则列表（按 priority 排序）
POST   /api/v1/admin/sla-rules          新建规则
PUT    /api/v1/admin/sla-rules/{id}     编辑规则
DELETE /api/v1/admin/sla-rules/{id}     删除规则
PUT    /api/v1/admin/sla-rules/sort     批量更新优先级排序

# SLA 违约记录查询
GET    /api/v1/admin/sla-breaches       违约日志（支持时间范围、坐席、类型过滤）
GET    /api/v1/dashboard/sla-overview   SLA 达标率概览
```

### 6.7 看板指标

```
GET /api/v1/dashboard/sla-overview

Response:
{
  "frtBreachRate": 0.03,        // FRT 违约率
  "rtBreachRate": 0.07,         // RT 违约率
  "avgFrtSeconds": 45,          // 平均首响秒数
  "avgRtMinutes": 12,           // 平均解决分钟数
  "breachCountToday": 5         // 今日违约次数
}
```

### 6.8 定时任务

`SlaCheckScheduler`：每分钟执行一次，查询所有 `frt_status=PENDING OR rt_status=PENDING` 的 `SlaTracker`，判断是否进入预警区间或超过 deadline，分别推送告警或写入违约日志。使用 Redisson 分布式锁（`sla:check:lock`）保证多实例下只有一个节点执行。

## 7. 业务时间 & 离线自动回复

### 7.1 功能描述

配置人工坐席的服务时段（工作日/节假日/自定义），当访客在非业务时间请求转人工时，系统自动拦截并回复离线留言提示，同时将留言记录存储，等坐席上班后可在队列中处理。

### 7.2 业务规则

1. **多套时间表**：支持配置多套时间表（工作日、周末、节假日），按日期匹配，优先级：节假日 > 特殊日期 > 周几规则。
2. **时区支持**：时间表基于配置的时区（默认 `Asia/Shanghai`），避免夏令时问题。
3. **实时判断**：`POST /api/v1/chat/transfer` 请求时同步判断是否在业务时间内，非业务时间不入人工队列，改为存储离线留言。
4. **AI 不受限制**：业务时间仅影响**人工接入**路径，AI 对话始终 7×24 可用。
5. **离线留言通知**：坐席上班后（首次 WebSocket 连接），推送"您有 N 条离线留言待处理"提示。
6. **自动恢复入队**：可配置业务时间开始时，自动将当日离线留言批量入队（或需坐席手动确认）。
7. **自定义回复文案**：管理员可为每套时间表配置独立的离线自动回复话术。

### 7.3 领域模型

```
BusinessHoursConfig（业务时间配置）
├── id              bigint PK
├── name            varchar(64) NOT NULL         配置名称
├── timezone        varchar(64) DEFAULT 'Asia/Shanghai'
├── offline_message text NOT NULL                非业务时间的自动回复话术
├── auto_enqueue_on_open  boolean DEFAULT false  业务时间开始时自动入队离线留言
├── enabled         boolean DEFAULT true
├── is_default      boolean DEFAULT false        默认配置（系统最多一条）
└── created_at      timestamptz NOT NULL

BusinessHoursSlot（时段条目，N:1 关联 Config）
├── id              bigint PK
├── config_id       bigint NOT NULL FK
├── slot_type       varchar(16) NOT NULL         WEEKDAY / SPECIFIC_DATE / HOLIDAY
├── day_of_week     smallint                     1=周一…7=周日（WEEKDAY 时使用）
├── specific_date   date                         特定日期（SPECIFIC_DATE/HOLIDAY）
├── start_time      time NOT NULL                开始时间（如 09:00）
├── end_time        time NOT NULL                结束时间（如 18:00）
├── is_closed       boolean DEFAULT false        true = 全天关闭（节假日）
└── sort_order      int DEFAULT 0

OfflineMessage（离线留言）
├── id              bigint PK
├── session_id      varchar(64) NOT NULL
├── visitor_id      varchar(64) NOT NULL
├── visitor_name    varchar(64)
├── content         text NOT NULL                访客留言内容
├── contact_info    varchar(128)                 联系方式（手机/邮箱，可选）
├── status          varchar(16) NOT NULL         PENDING / PROCESSING / DONE / IGNORED
├── assigned_agent_id bigint                     分配的坐席
├── created_at      timestamptz NOT NULL
└── processed_at    timestamptz
```

### 7.4 业务时间判断逻辑

```java
// 伪代码，优先级：节假日 > 特定日期 > 星期几
public boolean isBusinessHours(LocalDateTime now, BusinessHoursConfig config) {
    ZonedDateTime zdt = now.atZone(ZoneId.of(config.getTimezone()));
    LocalDate today = zdt.toLocalDate();
    LocalTime time  = zdt.toLocalTime();

    // 1. 查找节假日/特定日期（HOLIDAY / SPECIFIC_DATE）
    Optional<BusinessHoursSlot> specific = findSpecificSlot(config, today);
    if (specific.isPresent()) {
        BusinessHoursSlot slot = specific.get();
        return !slot.isClosed() && !time.isBefore(slot.getStartTime())
                                 && time.isBefore(slot.getEndTime());
    }

    // 2. 星期几规则
    int dow = zdt.getDayOfWeek().getValue(); // 1=Monday
    Optional<BusinessHoursSlot> weekday = findWeekdaySlot(config, dow);
    if (weekday.isPresent()) {
        BusinessHoursSlot slot = weekday.get();
        return !slot.isClosed() && !time.isBefore(slot.getStartTime())
                                 && time.isBefore(slot.getEndTime());
    }

    // 3. 未配置该天 → 视为关闭
    return false;
}
```

### 7.5 转人工拦截流程

```
POST /api/v1/chat/transfer
        │
        ▼
  isBusinessHours() ?
       ├── YES → 正常入队（现有逻辑）
       └── NO  → 不入队
               ├── 创建 OfflineMessage 记录
               ├── 返回离线回复话术（SSE event:transfer_offline）
               └── 向访客推送自动回复文案
```

新增 SSE 事件类型：

```json
event: transfer_offline
data: {
  "message": "当前客服不在线，服务时间为工作日 9:00-18:00，您的留言已记录，我们将在工作时间内联系您。",
  "offlineMessageId": "789",
  "nextBusinessStart": "2026-07-17T09:00:00+08:00"
}
```

### 7.6 管理端 API

```
# 业务时间配置（需要 business-hours:manage 权限）
GET    /api/v1/admin/business-hours              配置列表
POST   /api/v1/admin/business-hours              新建配置
PUT    /api/v1/admin/business-hours/{id}         编辑配置
DELETE /api/v1/admin/business-hours/{id}         删除配置
PUT    /api/v1/admin/business-hours/{id}/default 设为默认

# 时段管理
GET    /api/v1/admin/business-hours/{id}/slots   时段列表
POST   /api/v1/admin/business-hours/{id}/slots   新增时段
PUT    /api/v1/admin/business-hours/{id}/slots/{slotId}   编辑时段
DELETE /api/v1/admin/business-hours/{id}/slots/{slotId}   删除时段

# 离线留言管理（客服管理员）
GET    /api/v1/admin/offline-messages            离线留言列表（分页）
PUT    /api/v1/admin/offline-messages/{id}/assign 分配给指定坐席
PUT    /api/v1/admin/offline-messages/{id}/done  标记已处理
PUT    /api/v1/admin/offline-messages/{id}/ignore 忽略

# 查询工具（供前端判断当前是否在业务时间）
GET    /api/v1/business-hours/status             { "open": true, "nextChange": "..." }
```

### 7.7 与 SLA 联动

`SlaCheckScheduler` 计算 deadline 时，调用 `BusinessHoursService.isBusinessHours()` 判断当前时刻是否计入 SLA 时钟。非业务时间段的分钟数不计入 FRT / RT 的已用时长。

## 8. 访客标签 & 会话备注

### 8.1 功能描述

坐席在处理会话时可为**访客**打分类标签（跨会话持久，如 VIP / 潜客 / 投诉），也可在**单次会话**内写内部备注（对访客完全不可见，仅坐席/管理员可见）。两者是坐席日常运营和后续追踪的核心工具。

### 8.2 访客标签

#### 8.2.1 业务规则

1. **跨会话持久化**：标签绑定在访客维度（`visitor_id`），不论哪次会话都能看到该访客的历史标签。
2. **多标签**：一个访客可同时拥有多个标签，无上限。
3. **标签库管理**：管理员维护全局标签库，设置标签名称、颜色、描述；坐席只能选择已有标签，不能自由创建。
4. **坐席权限**：任何坐席均可为访客添加/移除标签（不需要特殊权限），但修改标签库定义需要 `tag:manage` 权限。
5. **标签历史**：记录每次打标/去标的操作人和时间，用于审计。
6. **过滤与统计**：管理界面支持按标签筛选访客列表；看板提供标签分布统计（已有 `getTagDistribution` 接口，需与新标签体系对接）。

#### 8.2.2 领域模型

```
TagDefinition（标签定义，管理员维护）
├── id          bigint PK
├── name        varchar(32) NOT NULL UNIQUE    标签名称
├── color       varchar(7) NOT NULL            十六进制颜色 #RRGGBB
├── description varchar(128)                   说明（坐席悬浮提示用）
├── sort_order  int DEFAULT 0
├── enabled     boolean DEFAULT true
├── created_by  bigint NOT NULL
└── created_at  timestamptz NOT NULL

VisitorTag（访客标签关联，N:M 关系展开）
├── id           bigint PK
├── visitor_id   varchar(64) NOT NULL           访客标识
├── tag_id       bigint NOT NULL FK
├── applied_by   bigint NOT NULL                操作坐席 ID
├── applied_at   timestamptz NOT NULL
├── removed_by   bigint                         移除坐席 ID（null = 仍有效）
└── removed_at   timestamptz
```

联合唯一约束：`UNIQUE(visitor_id, tag_id) WHERE removed_at IS NULL`（同一访客同一标签只能有一条有效记录）。

#### 8.2.3 API

```
# 标签定义管理（需要 tag:manage 权限）
GET    /api/v1/admin/tags             标签列表
POST   /api/v1/admin/tags             新建标签
PUT    /api/v1/admin/tags/{id}        编辑标签
DELETE /api/v1/admin/tags/{id}        禁用标签（软删除）

# 访客标签操作（所有坐席可用）
GET    /api/v1/visitors/{visitorId}/tags         获取访客当前标签列表
POST   /api/v1/visitors/{visitorId}/tags         为访客打标签
DELETE /api/v1/visitors/{visitorId}/tags/{tagId} 移除标签

# 按标签筛选访客（管理端）
GET    /api/v1/admin/visitors?tagId=xxx&page=1   按标签分页查询访客
```

打标签请求体：
```json
POST /api/v1/visitors/{visitorId}/tags
{ "tagId": 3 }
```

---

### 8.3 会话备注（Internal Note）

#### 8.3.1 业务规则

1. **仅内部可见**：备注消息类型为 `NOTE`，通过 WebSocket 坐席通道推送，绝不出现在访客侧 WebSocket 或 SSE 流中。
2. **多人协作**：同一会话中多个坐席均可写备注（如主坐席 + 主管辅助标注）。
3. **支持富文本**：备注内容支持 Markdown，前端渲染时展示格式化文字（代码块、加粗等）。
4. **@提及**：备注内容中可 `@agent_id` 提及坐席，被提及坐席通过 WebSocket 收到通知。
5. **可编辑/删除**：备注创建者在 10 分钟内可编辑，任意时间可软删除（需同步通知坐席端）。
6. **历史可查**：坐席打开历史会话时，备注随消息流一起加载，但以不同样式区分（如灰底斜体）。
7. **纳入 AI 摘要**：`AiSummaryService` 生成会话摘要时，可选择是否包含内部备注内容。

#### 8.3.2 领域模型

```
SessionNote（会话备注）
├── id            bigint PK
├── session_id    varchar(64) NOT NULL
├── content       text NOT NULL              Markdown 格式
├── author_id     bigint NOT NULL            坐席 ID
├── mentioned_ids bigint[]                   被@提及的坐席 ID 列表（PostgreSQL array）
├── edited        boolean DEFAULT false      是否被编辑过
├── deleted       boolean DEFAULT false      软删除
├── created_at    timestamptz NOT NULL
└── updated_at    timestamptz NOT NULL
```

#### 8.3.3 WebSocket 事件

备注通过坐席 WebSocket 通道（`/ws/agent`）实时同步。

**新备注推送（所有接入该会话的坐席）：**
```json
{
  "type": "SESSION_NOTE_ADDED",
  "sessionId": "sess_abc",
  "note": {
    "id": 42,
    "content": "访客情绪激动，建议主管介入 @1001",
    "authorName": "张三",
    "mentionedAgentIds": [1001],
    "createdAt": "2026-07-16T14:30:00Z"
  }
}
```

**@提及通知（仅推送给被提及坐席）：**
```json
{
  "type": "NOTE_MENTION",
  "sessionId": "sess_abc",
  "noteId": 42,
  "message": "张三在会话中 @了你"
}
```

#### 8.3.4 API

```
# 会话备注（需坐席角色）
GET    /api/v1/sessions/{sessionId}/notes         获取会话所有备注（按时间升序）
POST   /api/v1/sessions/{sessionId}/notes         新增备注
PUT    /api/v1/sessions/{sessionId}/notes/{id}    编辑备注（10分钟内，仅作者）
DELETE /api/v1/sessions/{sessionId}/notes/{id}    软删除备注（仅作者或管理员）
```

新增备注请求体：
```json
{
  "content": "访客情绪激动，建议主管介入 @1001",
  "mentionedAgentIds": [1001]
}
```

---

### 8.4 与现有功能的集成点

| 集成点 | 说明 |
|--------|------|
| 坐席 WebSocket（`AgentChannelWsHandler`） | 备注新增/编辑/删除事件通过此通道广播 |
| `AiSummaryService` | 生成摘要时可附带 `includeNotes=true` 参数 |
| `DashboardAppService` | `getTagDistribution()` 对接新的 `TagDefinition` 表 |
| 访客历史查询 | `GET /chat/history` 响应中 `role=note` 类型消息仅在坐席侧返回 |

## 9. 数据模型汇总（Flyway 迁移 SQL）

> 所有新表均归属 conversation-service 的 PostgreSQL 数据库（`aria_cs`，schema `public`）。
> 脚本文件命名：`V5__cs_features.sql`（假设当前最新迁移版本为 V4）。

```sql
-- =============================================================
-- V5__cs_features.sql
-- 新增：CSAT、快捷回复、SLA、业务时间、访客标签、会话备注
-- =============================================================

-- -----------------------------------------------------------
-- 1. 满意度评价（CSAT）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS csat_rating (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL,
    visitor_id    VARCHAR(64),
    agent_id      BIGINT,
    score         SMALLINT     CHECK (score BETWEEN 1 AND 5),
    comment       TEXT,
    channel       VARCHAR(20)  NOT NULL DEFAULT 'AI',  -- AI / HUMAN
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING/RATED/EXPIRED/SKIPPED
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    rated_at      TIMESTAMPTZ,
    expired_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_csat_session UNIQUE (session_id)
);
CREATE INDEX idx_csat_status_expired ON csat_rating (status, expired_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_csat_agent_rated ON csat_rating (agent_id, rated_at)
    WHERE agent_id IS NOT NULL;

COMMENT ON TABLE  csat_rating             IS '会话满意度评价';
COMMENT ON COLUMN csat_rating.channel     IS 'AI=AI对话, HUMAN=人工接待';
COMMENT ON COLUMN csat_rating.status      IS 'PENDING=待评价, RATED=已评价, EXPIRED=已过期, SKIPPED=已跳过';

-- -----------------------------------------------------------
-- 2. 快捷回复
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS canned_response_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    parent_id   BIGINT       REFERENCES canned_response_group (id) ON DELETE SET NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_crg_parent ON canned_response_group (parent_id) WHERE deleted = FALSE;

COMMENT ON TABLE canned_response_group IS '快捷回复分组';

CREATE TABLE IF NOT EXISTS canned_response (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT       REFERENCES canned_response_group (id) ON DELETE SET NULL,
    title       VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC', -- PUBLIC / PRIVATE
    owner_id    BIGINT,                                 -- PRIVATE 时的所属坐席
    use_count   INT          NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
-- 全文检索索引（中文用 simple，如部署 pg_jieba 可换 jieba）
CREATE INDEX idx_cr_fts ON canned_response
    USING GIN (to_tsvector('simple', title || ' ' || content))
    WHERE deleted = FALSE;
CREATE INDEX idx_cr_scope_owner ON canned_response (scope, owner_id) WHERE deleted = FALSE;

COMMENT ON TABLE  canned_response          IS '快捷回复模板';
COMMENT ON COLUMN canned_response.scope    IS 'PUBLIC=公共, PRIVATE=个人';
COMMENT ON COLUMN canned_response.use_count IS '使用次数，用于搜索排序';

-- -----------------------------------------------------------
-- 3. SLA 规则 & 跟踪
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS sla_rule (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(64)  NOT NULL,
    priority                INT          NOT NULL DEFAULT 0,
    channel                 VARCHAR(16),                    -- HUMAN / AI / null=不限
    domain_code             VARCHAR(64),
    frt_minutes             INT,
    nrt_minutes             INT,
    rt_minutes              INT,
    warn_percent            INT          NOT NULL DEFAULT 80,
    respect_business_hours  BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by              BIGINT       NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_rule_priority ON sla_rule (priority) WHERE enabled = TRUE;

COMMENT ON TABLE  sla_rule                     IS 'SLA 规则定义';
COMMENT ON COLUMN sla_rule.warn_percent        IS '预警触发阈值百分比，默认80%';
COMMENT ON COLUMN sla_rule.respect_business_hours IS 'true=仅计业务时间内的分钟数';

CREATE TABLE IF NOT EXISTS sla_tracker (
    id               BIGSERIAL PRIMARY KEY,
    session_id       VARCHAR(64)  NOT NULL,
    rule_id          BIGINT       NOT NULL REFERENCES sla_rule (id),
    frt_deadline     TIMESTAMPTZ,
    rt_deadline      TIMESTAMPTZ,
    frt_achieved_at  TIMESTAMPTZ,
    rt_achieved_at   TIMESTAMPTZ,
    frt_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/MET/BREACHED
    rt_status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sla_session UNIQUE (session_id)
);
CREATE INDEX idx_sla_tracker_pending ON sla_tracker (frt_deadline, rt_deadline)
    WHERE frt_status = 'PENDING' OR rt_status = 'PENDING';

CREATE TABLE IF NOT EXISTS sla_breach_log (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL,
    rule_id         BIGINT       NOT NULL REFERENCES sla_rule (id),
    breach_type     VARCHAR(16)  NOT NULL, -- FRT / NRT / RT
    deadline        TIMESTAMPTZ  NOT NULL,
    actual_at       TIMESTAMPTZ,
    delay_seconds   INT,
    agent_id        BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_breach_session   ON sla_breach_log (session_id);
CREATE INDEX idx_sla_breach_created   ON sla_breach_log (created_at DESC);

COMMENT ON TABLE sla_breach_log IS 'SLA 违约记录';

-- -----------------------------------------------------------
-- 4. 业务时间 & 离线留言
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS business_hours_config (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(64)  NOT NULL,
    timezone               VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    offline_message        TEXT         NOT NULL,
    auto_enqueue_on_open   BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- 最多一条默认配置
CREATE UNIQUE INDEX uq_bh_default ON business_hours_config (is_default)
    WHERE is_default = TRUE;

COMMENT ON TABLE  business_hours_config                   IS '业务时间配置';
COMMENT ON COLUMN business_hours_config.auto_enqueue_on_open IS '业务时间开始时自动入队当日离线留言';

CREATE TABLE IF NOT EXISTS business_hours_slot (
    id             BIGSERIAL PRIMARY KEY,
    config_id      BIGINT       NOT NULL REFERENCES business_hours_config (id) ON DELETE CASCADE,
    slot_type      VARCHAR(20)  NOT NULL, -- WEEKDAY / SPECIFIC_DATE / HOLIDAY
    day_of_week    SMALLINT,              -- 1=周一…7=周日（WEEKDAY 时使用）
    specific_date  DATE,
    start_time     TIME,
    end_time       TIME,
    is_closed      BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order     INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_bh_slot_config ON business_hours_slot (config_id, slot_type);

COMMENT ON TABLE  business_hours_slot          IS '业务时间段配置项';
COMMENT ON COLUMN business_hours_slot.is_closed IS 'true=全天关闭（节假日/特殊日期）';

CREATE TABLE IF NOT EXISTS offline_message (
    id                BIGSERIAL PRIMARY KEY,
    session_id        VARCHAR(64)  NOT NULL,
    visitor_id        VARCHAR(64)  NOT NULL,
    visitor_name      VARCHAR(64),
    content           TEXT         NOT NULL,
    contact_info      VARCHAR(128),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/PROCESSING/DONE/IGNORED
    assigned_agent_id BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMPTZ
);
CREATE INDEX idx_offline_msg_status  ON offline_message (status, created_at);
CREATE INDEX idx_offline_msg_visitor ON offline_message (visitor_id);

COMMENT ON TABLE offline_message IS '业务时间外的访客离线留言';

-- -----------------------------------------------------------
-- 5. 访客标签
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS tag_definition (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL,
    color       VARCHAR(7)   NOT NULL DEFAULT '#6366f1', -- HEX color
    description VARCHAR(128),
    sort_order  INT          NOT NULL DEFAULT 0,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tag_name UNIQUE (name)
);

COMMENT ON TABLE tag_definition IS '访客标签定义（管理员维护）';

CREATE TABLE IF NOT EXISTS visitor_tag (
    id          BIGSERIAL PRIMARY KEY,
    visitor_id  VARCHAR(64)  NOT NULL,
    tag_id      BIGINT       NOT NULL REFERENCES tag_definition (id),
    applied_by  BIGINT       NOT NULL,
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    removed_by  BIGINT,
    removed_at  TIMESTAMPTZ
);
-- 同一访客同一标签只有一条有效记录
CREATE UNIQUE INDEX uq_visitor_tag_active
    ON visitor_tag (visitor_id, tag_id)
    WHERE removed_at IS NULL;
CREATE INDEX idx_visitor_tag_visitor ON visitor_tag (visitor_id) WHERE removed_at IS NULL;
CREATE INDEX idx_visitor_tag_tag     ON visitor_tag (tag_id)     WHERE removed_at IS NULL;

COMMENT ON TABLE visitor_tag IS '访客标签关联（含操作审计）';

-- -----------------------------------------------------------
-- 6. 会话备注
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_note (
    id             BIGSERIAL PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    content        TEXT         NOT NULL,
    author_id      BIGINT       NOT NULL,
    mentioned_ids  BIGINT[]     NOT NULL DEFAULT '{}',
    edited         BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_session_note_session ON session_note (session_id, created_at)
    WHERE deleted = FALSE;

COMMENT ON TABLE  session_note              IS '会话内部备注（仅坐席可见）';
COMMENT ON COLUMN session_note.mentioned_ids IS '被@提及的坐席 ID 数组';
```

## 10. API 接口汇总

> 所有接口均归属 conversation-service（:8082）。  
> 管理类接口需对应权限码（`@SaCheckPermission`），访客侧接口需访客 Token，坐席侧接口需坐席 Token。

### 10.1 权限码新增清单

在现有 RBAC 权限码体系中新增以下权限码：

| 权限码 | 说明 |
|--------|------|
| `csat:view` | 查看 CSAT 统计 |
| `canned-response:use` | 使用快捷回复（所有坐席默认拥有） |
| `canned-response:manage` | 管理公共快捷回复库 |
| `sla:view` | 查看 SLA 统计和违约记录 |
| `sla:manage` | 创建/编辑/删除 SLA 规则 |
| `business-hours:manage` | 管理业务时间配置 |
| `offline-message:manage` | 查看和处理离线留言 |
| `tag:manage` | 管理标签定义 |
| `session-note:view` | 查看会话备注（所有坐席默认拥有） |
| `session-note:manage` | 编辑/删除他人备注（仅管理员） |

---

### 10.2 CSAT API

```
# 访客侧（访客 Token）
POST   /api/v1/chat/csat/{csatId}/rate     提交评分
       Body: { "score": 5, "comment": "..." }
POST   /api/v1/chat/csat/{csatId}/skip     跳过评价

# 管理端（需 csat:view 权限）
GET    /api/v1/dashboard/csat-overview     CSAT 概览（均分、响应率）
GET    /api/v1/dashboard/csat-trend        按天 CSAT 均分趋势
       Query: rangeType=week|month|custom, days=N
GET    /api/v1/dashboard/csat-distribution 1-5 星分布
GET    /api/v1/dashboard/csat-by-agent     分坐席 CSAT 排名（分页）
GET    /api/v1/admin/csat-ratings          原始评价记录（分页，支持时间/坐席/分数过滤）
```

**CSAT 概览响应示例：**
```json
{
  "avgScore": 4.2,
  "responseRate": 0.63,
  "totalRated": 1240,
  "totalSkipped": 89,
  "totalExpired": 638
}
```

---

### 10.3 快捷回复 API

```
# 坐席使用（需登录）
GET    /api/v1/canned-responses/search
       Query: q=关键词, group_id=1, limit=10
POST   /api/v1/canned-responses/{id}/use   记录使用（异步写 use_count）

# 个人快捷回复（PRIVATE scope，所有坐席）
GET    /api/v1/canned-responses/mine       我的私人快捷回复列表
POST   /api/v1/canned-responses/mine       新建私人快捷回复
PUT    /api/v1/canned-responses/mine/{id}  编辑私人快捷回复
DELETE /api/v1/canned-responses/mine/{id}  删除私人快捷回复

# 公共库管理（需 canned-response:manage 权限）
GET    /api/v1/admin/canned-response-groups           分组树
POST   /api/v1/admin/canned-response-groups           新建分组
PUT    /api/v1/admin/canned-response-groups/{id}      编辑分组
DELETE /api/v1/admin/canned-response-groups/{id}      删除分组
GET    /api/v1/admin/canned-responses                 公共快捷回复列表（分页）
POST   /api/v1/admin/canned-responses                 新建公共快捷回复
PUT    /api/v1/admin/canned-responses/{id}            编辑
DELETE /api/v1/admin/canned-responses/{id}            软删除
```

**搜索响应示例：**
```json
[
  {
    "id": 12,
    "title": "感谢等待",
    "content": "您好 {{visitor_name}}，感谢耐心等待，马上为您处理。",
    "groupName": "通用问候",
    "scope": "PUBLIC",
    "useCount": 312
  }
]
```

---

### 10.4 SLA API

```
# SLA 规则管理（需 sla:manage 权限）
GET    /api/v1/admin/sla-rules              规则列表（按 priority 排序）
POST   /api/v1/admin/sla-rules              新建规则
PUT    /api/v1/admin/sla-rules/{id}         编辑规则
DELETE /api/v1/admin/sla-rules/{id}         删除规则
PUT    /api/v1/admin/sla-rules/sort         批量更新排序
       Body: [{ "id": 1, "priority": 0 }, { "id": 2, "priority": 1 }]

# SLA 统计（需 sla:view 权限）
GET    /api/v1/dashboard/sla-overview       SLA 达标率概览
GET    /api/v1/admin/sla-breaches           违约记录列表（分页）
       Query: type=FRT|NRT|RT, agentId, startDate, endDate

# 会话 SLA 状态（坐席查看当前会话 SLA）
GET    /api/v1/sessions/{sessionId}/sla     当前会话 SLA 追踪状态
```

**SLA 概览响应示例：**
```json
{
  "frtBreachRate": 0.03,
  "rtBreachRate": 0.07,
  "avgFrtSeconds": 45,
  "avgRtMinutes": 12,
  "breachCountToday": 5,
  "frtMeetRate": 0.97
}
```

**SLA 规则创建请求体：**
```json
{
  "name": "人工会话标准 SLA",
  "priority": 0,
  "channel": "HUMAN",
  "frtMinutes": 2,
  "nrtMinutes": 5,
  "rtMinutes": 30,
  "warnPercent": 80,
  "respectBusinessHours": true,
  "enabled": true
}
```

---

### 10.5 业务时间 API

```
# 配置管理（需 business-hours:manage 权限）
GET    /api/v1/admin/business-hours                           配置列表
POST   /api/v1/admin/business-hours                          新建配置
PUT    /api/v1/admin/business-hours/{id}                     编辑配置
DELETE /api/v1/admin/business-hours/{id}                     删除配置
PUT    /api/v1/admin/business-hours/{id}/default             设为默认

# 时段管理
GET    /api/v1/admin/business-hours/{id}/slots               时段列表
POST   /api/v1/admin/business-hours/{id}/slots               新增时段
PUT    /api/v1/admin/business-hours/{id}/slots/{slotId}      编辑时段
DELETE /api/v1/admin/business-hours/{id}/slots/{slotId}      删除时段

# 离线留言管理（需 offline-message:manage 权限）
GET    /api/v1/admin/offline-messages                         列表（分页，支持 status 过滤）
PUT    /api/v1/admin/offline-messages/{id}/assign            分配给坐席
       Body: { "agentId": 1001 }
PUT    /api/v1/admin/offline-messages/{id}/done              标记已处理
PUT    /api/v1/admin/offline-messages/{id}/ignore            忽略

# 状态查询（公开，前端判断是否在业务时间）
GET    /api/v1/business-hours/status
```

**业务时间状态响应：**
```json
{
  "open": false,
  "message": "当前不在服务时间，服务时间为工作日 9:00-18:00",
  "nextBusinessStart": "2026-07-17T09:00:00+08:00",
  "timezone": "Asia/Shanghai"
}
```

---

### 10.6 访客标签 API

```
# 标签定义管理（需 tag:manage 权限）
GET    /api/v1/admin/tags              标签库列表
POST   /api/v1/admin/tags             新建标签
       Body: { "name": "VIP", "color": "#f59e0b", "description": "高价值用户" }
PUT    /api/v1/admin/tags/{id}        编辑标签
DELETE /api/v1/admin/tags/{id}        禁用（软删除）

# 访客标签操作（所有坐席）
GET    /api/v1/visitors/{visitorId}/tags          获取访客标签列表
POST   /api/v1/visitors/{visitorId}/tags          打标签
       Body: { "tagId": 3 }
DELETE /api/v1/visitors/{visitorId}/tags/{tagId}  移除标签

# 按标签查询访客（需 tag:manage 权限）
GET    /api/v1/admin/visitors         访客列表（支持 tagId 过滤，分页）
       Query: tagId=3, page=1, size=20
```

---

### 10.7 会话备注 API

```
# 会话备注（坐席侧，需登录）
GET    /api/v1/sessions/{sessionId}/notes         备注列表（时间升序）
POST   /api/v1/sessions/{sessionId}/notes         新增备注
       Body: { "content": "...", "mentionedAgentIds": [1001] }
PUT    /api/v1/sessions/{sessionId}/notes/{id}    编辑（10分钟内，仅作者）
       Body: { "content": "..." }
DELETE /api/v1/sessions/{sessionId}/notes/{id}    软删除（作者或有 session-note:manage 权限）
```

**备注列表响应示例：**
```json
[
  {
    "id": 42,
    "content": "访客情绪激动，建议主管介入 @张三",
    "authorId": 1002,
    "authorName": "李四",
    "mentionedAgentIds": [1001],
    "edited": false,
    "createdAt": "2026-07-16T14:30:00Z",
    "updatedAt": "2026-07-16T14:30:00Z"
  }
]
```

---

### 10.8 现有接口变更

| 接口 | 变更内容 |
|------|---------|
| `POST /api/v1/chat/transfer` | 新增业务时间检查，非业务时间返回 `event:transfer_offline` |
| `POST /api/v1/sessions/{id}/close` | 关闭时触发 CSAT 邀请创建（如配置启用） |
| `GET /api/v1/dashboard/overview` | 新增 `csatAvgScore`、`csatResponseRate` 字段 |
| `GET /api/v1/sessions` | 新增 `slaStatus` 字段（当前会话 SLA 状态） |
| `GET /api/v1/sessions/{id}/ai-summary` | 新增可选参数 `includeNotes=true` |

## 11. 实现顺序 & 注意事项

### 11.1 推荐实现顺序

按业务价值和技术依赖关系，建议以下顺序实现：

```
阶段一（基础数据层，无外部依赖）
  ├── 访客标签 & 会话备注       — 纯 CRUD，零外部依赖，最快落地
  └── 快捷回复                  — 纯 CRUD + 简单全文检索，独立完整

阶段二（业务流程层）
  ├── 业务时间 & 离线自动回复   — 需改造 ChatAppService.stream 转人工拦截
  └── CSAT                      — 需改造 SessionQueueService.close 触发推送

阶段三（规则引擎层，依赖阶段二）
  └── SLA 管理                  — 依赖业务时间模块（暂停计时联动）
```

### 11.2 各功能关键实现路径

#### 访客标签 & 会话备注
- Flyway 新增 `tag_definition`、`visitor_tag`、`session_note` 表
- 新建 `TagDefinition`/`VisitorTag`/`SessionNote` DO 类和对应 Mapper
- `VisitorTagAppService`、`SessionNoteAppService` 处理业务逻辑
- `AgentChannelWsHandler` 新增 `SESSION_NOTE_ADDED` / `NOTE_MENTION` 事件分发
- 注意：备注的 `mentioned_ids` 使用 PostgreSQL `BIGINT[]`，MyBatis-Plus 需自定义 `TypeHandler`（参考现有 `JsonbTypeHandler` 的实现模式）

#### 快捷回复
- Flyway 新增 `canned_response_group`、`canned_response` 表
- 搜索接口使用 `pg_tsvector` + GIN 索引，MyBatis 原生 SQL `to_tsquery('simple', :q)` 实现
- `use_count` 递增用 `UPDATE canned_response SET use_count = use_count + 1` 原子更新，无需分布式锁
- 变量替换（`{{visitor_name}}` 等）在前端完成，后端不处理占位符

#### 业务时间 & 离线自动回复
- Flyway 新增 `business_hours_config`、`business_hours_slot`、`offline_message` 表
- `BusinessHoursService.isBusinessHours()` 的判断结果**不要缓存**（节假日配置可能随时变），但可用 Caffeine 缓存 1 分钟（`maximumSize=1, expireAfterWrite=1min`）减少 DB 压力
- 改造 `ChatAppService.requestTransfer()` → 注入 `BusinessHoursService`，非业务时间走 `OfflineMessageService.record()` 而非 `SessionQueueService.enqueue()`
- SSE 新增 `TRANSFER_OFFLINE` 事件类型，需同步更新 `ChatEvent.EventType` 枚举
- `auto_enqueue_on_open` 功能：`BusinessHoursOpenScheduler` 每分钟检查一次是否刚进入业务时间窗口（用 Redis key `bh:opened:{configId}:{date}` 防重入），满足条件则批量将当日 `PENDING` 离线留言转为正式队列条目

#### CSAT
- Flyway 新增 `csat_rating` 表
- 触发点 1：`SessionQueueService.close()` 人工关闭时 → 异步创建 CSAT 记录并推 WebSocket `csat_request`
- 触发点 2：`FaqChatAppService` SSE `event:done` 推送后 → 异步延迟 N 秒（可配置）推 SSE `csat_request`
- `CsatExpiryScheduler` 用 Redisson `tryLock("csat:expiry:lock", 50s)` 防多实例重复执行
- 看板 SQL 新增：`AVG(score) FILTER (WHERE status='RATED')` 等聚合查询，建议在 `DashboardStatsRepository` 中扩展

#### SLA
- Flyway 新增 `sla_rule`、`sla_tracker`、`sla_breach_log` 表
- 会话创建时（`SessionQueueService.enqueue()` 后）匹配规则、创建 `SlaTracker`，计算 deadline
  - `frt_deadline = NOW() + frt_minutes 分钟（扣除非业务时间）`
  - 扣除非业务时间的算法：从当前时刻起，在业务时间表中累计可用分钟，直到凑满所需分钟数
- `SlaCheckScheduler`（每分钟）：
  ```
  查询 frt_status=PENDING 且 frt_deadline <= NOW() + warn_window → 推预警
  查询 frt_status=PENDING 且 frt_deadline <= NOW()              → 写违约日志，更新 BREACHED
  同理处理 rt_status
  ```
- 坐席首次回复时（消息 `role=agent` 写入时）更新 `frt_achieved_at`，标记 `frt_status=MET`
- 使用 Redisson `tryLock("sla:check:lock", 55s)` 防调度重叠

---

### 11.3 竞品调研补充发现

后台调研（Zendesk / Salesforce / Intercom / Freshdesk / Tidio / Crisp / Chatwoot / 网易七鱼 / 阿里云 / 腾讯云）还发现以下高价值功能，作为后续迭代参考：

| 功能 | 来自竞品 | 优先级 | 说明 |
|------|---------|--------|------|
| **Agent Copilot（坐席 AI 助手）** | Zendesk、Intercom、Freshdesk | 🔴 高 | 坐席接手时自动摘要上下文、实时草拟回复建议；ARIA 的 AI 能力完全支撑，架构接近 |
| **自动化 QA（100% 会话评分）** | Zendesk Klaus、网易七鱼 | 🔴 高 | 对每条 AI + 人工会话打分（合规/语气/解决率）；当前看板只做统计，缺评分维度 |
| **情感分析（实时 + 批量）** | Zendesk、Salesforce | 🟠 中高 | 消息级情感分（正/负/中性），会话级趋势；可作为 SLA 升级触发条件 |
| **无代码工作流编排** | Intercom Workflows、Freshdesk | 🟠 中 | 可视化配置路由/升级/自动回复规则，让运营人员不依赖开发 |
| **知识库自动缺口检测** | Freshdesk Freddy Insights | 🟠 中 | 分析未命中 RAG 的问题，自动起草新 KB 文章草稿 |
| **MCP Server 层开放** | Tidio Lyro Smart Actions | 🟠 中 | 将 ARIA 的知识库搜索、工单、客户画像包装为 MCP Server，供外部 AI 编排器接入 |
| **语音渠道 AI 接入** | Intercom Fin for Voice、网易七鱼 | 🟡 低中 | ASR → LLM → TTS 管道；2025-2026 行业增速最快的渠道 |
| **多渠道 Channel Adapter** | Chatwoot、腾讯云 | 🟡 低中 | 微信公众号/企业微信/WhatsApp 接入；需 Channel Adapter 层复用现有对话引擎 |

---

### 11.4 技术注意事项

**多实例安全**

| 组件 | 处理方式 |
|------|---------|
| `CsatExpiryScheduler` | Redisson `tryLock("csat:expiry:lock", 50s)` |
| `SlaCheckScheduler` | Redisson `tryLock("sla:check:lock", 55s)` |
| `BusinessHoursOpenScheduler` | Redis key `bh:opened:{configId}:{date}` 防重入 |
| SLA Tracker 更新（首响） | 数据库 `UPDATE ... WHERE frt_status='PENDING'`（乐观锁） |

**性能影响评估**

- CSAT 触发（会话关闭时）：异步写入，对主链路无延迟影响
- 业务时间检查（转人工时）：单次 DB 查询 + 内存计算，< 2ms
- SLA Tracker 创建（会话入队时）：异步写入，不阻塞入队响应
- 所有 Scheduler 使用 `@Scheduled`，建议 `fixedDelay` 而非 `fixedRate` 避免任务堆积

**事务边界**

- CSAT 邀请创建与会话关闭**不在同一事务**：先关闭会话（主事务），关闭成功后异步发起 CSAT，避免 CSAT 写失败导致会话关闭回滚。
- SLA Tracker 创建与会话入队**同一事务**：保证 Tracker 和队列条目原子创建，不出现队列有条目但 Tracker 缺失的情况。

**向后兼容**

- `GET /api/v1/chat/history` 响应中新增 `role=note` 类型，前端需忽略未知 role（已有类型检查的前端代码需更新）
- `GET /api/v1/dashboard/overview` 响应新增 CSAT 字段，已有客户端向后兼容（JSON 新增字段不破坏旧客户端）
- `POST /api/v1/chat/transfer` 原本总是返回 201 入队成功，现在非业务时间返回 200 + 离线消息体，前端需区分处理

---

### 11.5 测试策略

| 模块 | 关键测试点 |
|------|-----------|
| CSAT | 重复评价幂等、过期定时、Channel=AI vs HUMAN 触发路径 |
| 快捷回复 | 全文搜索（中英文混合）、`use_count` 并发递增、PRIVATE scope 隔离 |
| SLA | 业务时间扣除计算（跨天、节假日）、多实例调度不重复 |
| 业务时间 | 时区转换（非 +8:00）、节假日 > 特定日期 > 周几优先级 |
| 访客标签 | 唯一约束（同访客同标签不重复）、移除后重打 |
| 会话备注 | 10 分钟编辑窗口、@提及推送仅到被提及坐席、备注不出现在访客 SSE |
