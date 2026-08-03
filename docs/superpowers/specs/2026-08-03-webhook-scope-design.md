# 通用 Webhook 配置（支持事件范围）技术设计

**日期：** 2026-08-03
**关联文档：** `docs/superpowers/specs/2026-07-22-sla-business-hours-tags-design.md`、`docs/superpowers/specs/2026-07-22-sla-shard-scheduler-design.md`
**状态：** 待实现
**涉及仓库：** `aria-server`（后端）、`aria-frontend`（前端）

---

## 1. 背景与问题

### 1.1 SLA 现状排查结论

对线上环境的 SLA 功能进行了代码与日志排查（2026-08-03），结论：**SLA 检测链路本身工作正常**，用户感知的"SLA 没生效"由以下事实解释：

| 事实 | 证据 |
|---|---|
| SLA 扫描调度器在运行 | `SlaBreachScanScheduler` 每 30s 通过 Redisson 分片锁扫描；Redis 1s 轮询实测捕获到 `lock:scheduler:sla-scan:shard:0` 锁 |
| 违规检测流水线工作正常 | `cs_sla_breach` 共 552 条记录（WAIT/FRT/HANDLE × WARNING/BREACH），8/1 实测违规在精确预期时间产生（WAIT 120s 目标 → 预警 11:38、违规 11:39；HANDLE 1800s → 违规 12:06） |
| 无新会话可检测 | `cs_conversation` 最后创建时间为 2026-08-01 11:37，之后无任何新会话；扫描器每轮只评估 179 个 8/1 前的存量 WAITING/ACTIVE 会话，其违规均已记录，幂等跳过 |
| 通知链路未被使用 | 当前唯一策略 actions = `{"sseAlert":true,"autoEscalate":false,"recordBreachOnly":true}`；`cs_webhook_config` 表为空（0 条配置），`webhook_notified_at`/`escalated_at` 全为 NULL 与配置一致 |

### 1.2 Webhook 功能现状

| 层 | 现状 | 问题 |
|---|---|---|
| 后端 API | `WebhookController` 已提供 CRUD + 测试接口（`/api/v1/admin/sla/webhooks`） | 接口路径为 SLA 专属，`WebhookConfigEntity` 无范围（scope）字段 |
| 绑定方式 | SLA 策略 `SlaBreachActions.webhookIds` 绑定 webhook ID | 仅 SLA 违规可用；新增其他事件通知需为每个场景重建一套绑定 |
| 前端页面 | `views/system/sla/webhook.vue` 已存在（列表/新增/编辑/删除/测试） | 无「事件范围」配置项，用户误以为没有配置入口 |
| 分发链路 | `SlaBreachNotifier` → `WebhookDispatcher.dispatch(webhookIds, SlaBreachContext, breachIds)` | 上下文与分发签名均为 SLA 专用，无法复用 |

### 1.3 核心诉求

1. **通用化**：Webhook 配置声明订阅哪些事件（scope），系统按事件自动匹配推送，任何业务事件都可接入。
2. **支持范围**：第一版支持 SLA 违规、会话生命周期（创建/转人工/关闭）、客户评价共 5 类事件范围。
3. **废弃策略级绑定**：SLA 违规通知彻底改为按 scope 匹配，移除 `SlaBreachActions.webhookIds`。
4. **前后端一并交付**：后端改造 + 前端 `webhook.vue` 增加范围配置。

---

## 2. 目标与非目标

### 2.1 目标

- 建立通用 Webhook 事件通知体系：**事件发布 → scope 匹配 → 异步分发**。
- `cs_webhook_config` 增加 `scopes` 列，支持一个 webhook 订阅多个事件范围。
- SLA 违规、会话创建/转人工/关闭、CSAT 评价 5 个事件点接入发布。
- 前端提供范围配置交互（多选），表格展示订阅范围。

### 2.2 非目标

- 不做消息去重/重试队列（当前 `webhookExecutor` 线程池 + 各 Sender 自身重试已够用）。
- 不引入新的事件总线（Spring ApplicationEvent / MQ fanout 已有 SSE 体系，webhook 独立走同步查库 + `@Async` 分发）。
- 不做 webhook 签名验证协议设计（保持现有 secret 签名能力）。
- 不覆盖访客侧页面（仅管理后台）。

## 3. 事件范围（Scope）模型

### 3.1 枚举定义

`WebhookScope` 枚举（domain 层，`com.aria.conversation.domain.model`）：

```java
public enum WebhookScope {
    /** SLA 违规告警（含 WARNING 预警 / BREACH 正式违规两阶段，eventType 区分） */
    SLA_BREACH,
    /** 新会话创建（访客进线） */
    SESSION_CREATED,
    /** 转人工 / 座席间转接 */
    SESSION_TRANSFERRED,
    /** 会话关闭 */
    SESSION_CLOSED,
    /** 客户提交评价 */
    CSAT_RATED
}
```

### 3.2 语义约定

| Scope | 触发时机 | eventType 取值 | 主要 payload 字段 |
|---|---|---|---|
| `SLA_BREACH` | SLA 扫描器评估出 WARNING 或 BREACH | `WAIT` / `FRT` / `HANDLE`（stage 在 payload 内） | sessionId、visitorName、policyName、breaches[]（type/stage/targetSec/actualSec） |
| `SESSION_CREATED` | 会话初始化成功（进入 WAITING / AI_CHAT） | `CREATED` | sessionId、visitorId、visitorName、channel、tag |
| `SESSION_TRANSFERRED` | 用户请求转人工 / 座席转接 | `TRANSFER` / `ENQUEUE` | sessionId、visitorName、fromAgentId、toAgentId、reason |
| `SESSION_CLOSED` | 会话关闭（座席主动关闭或断线） | `CLOSED` | sessionId、agentId、durationSec |
| `CSAT_RATED` | 客户提交评分 | `RATED` | sessionId、csatId、score、comment、channel |

> 说明：`SLA_BREACH` 的 `eventType` 复用 `BreachType`（WAIT/FRT/HANDLE），`stage`（WARNING/BREACH）放在 payload 中，避免枚举数量膨胀。

### 3.3 匹配规则

- webhook 的 `scopes` 为空数组：视为不订阅任何事件，不推送（与"未配置"等价）。
- 事件发生时，查询所有 `is_enabled=1` 且 `scopes` 包含该事件名的 webhook，逐个推送。
- 同一事件可命中多个 webhook；同一 webhook 可订阅多个事件。
- 禁用（`is_enabled=0`）或已删除的 webhook 不参与匹配。

## 4. 数据模型

### 4.1 `cs_webhook_config` 表结构变更

新增 `scopes` 列（Flyway 迁移脚本，`cs_conversation` schema）：

```sql
ALTER TABLE cs_conversation.cs_webhook_config
    ADD COLUMN scopes jsonb NOT NULL DEFAULT '["SLA_BREACH"]';
```

- **默认值 `["SLA_BREACH"]`**：保证存量行（当前表为空）与"创建时未显式传 scopes"的请求向后兼容——不传时默认只订阅 SLA 违规，与旧行为一致。
- 列类型 jsonb：与 `cs_sla_policy.match_visitor_tags` / `actions` 的既有存储方式保持一致（`JacksonTypeHandler` 序列化）。

变更后完整字段：

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 自增 |
| name | varchar(50) | 名称，唯一约束 |
| type | varchar(10) | FEISHU / DINGTALK / WECOM / CUSTOM |
| url | varchar(500) | HTTPS webhook 地址 |
| secret | varchar(200) | 飞书/钉钉签名密钥，可空 |
| custom_headers | jsonb | CUSTOM 类型专用请求头 |
| message_template | text | 自定义模板，空则用默认 |
| is_enabled | smallint | 1 启用 / 0 禁用 |
| scopes | jsonb | **新增**，订阅的事件范围数组，默认 `["SLA_BREACH"]` |
| create_time / update_time | timestamp | 审计字段 |

### 4.2 实体与 Mapper

**`WebhookConfigEntity`**：新增字段

```java
/** 订阅的事件范围列表（WebhookScope 枚举名），空数组表示不订阅任何事件 */
@TableField(value = "scopes", typeHandler = JacksonTypeHandler.class)
private List<String> scopes;
```

**`WebhookConfigMapper`**：新增按范围查询（XML 或注解，jsonb 包含查询）

```sql
-- Mapper 方法：List<WebhookConfigEntity> selectEnabledByScope(String scope)
SELECT *
FROM cs_conversation.cs_webhook_config
WHERE is_enabled = 1
  AND scopes @> ('["' || #{scope} || '"]')::jsonb
ORDER BY id ASC
```

`@>` 为 PostgreSQL jsonb 数组包含操作符，走 GIN 索引（可为 `scopes` 建 `GIN (scopes)` 加速，量级小时非必需）。

### 4.3 `SlaBreachActions` 变更

移除 `webhookIds` 字段：

```java
// 删除
/** 违规时推送的 Webhook 配置 ID 列表，空列表表示不推送 */
private List<Long> webhookIds;
```

- `cs_sla_policy.actions` JSON 中若残留 `webhookIds`，Jackson 默认忽略未知属性（Spring Boot 默认关闭 `FAIL_ON_UNKNOWN_PROPERTIES`），反序列化不受影响。
- 当前表内唯一策略无 `webhookIds`，无数据迁移成本。

### 4.4 Flyway 迁移

新增版本化迁移脚本（如 `V__add_webhook_scopes.sql`，序号按现有序列顺延）：
1. `ALTER TABLE ... ADD COLUMN scopes jsonb NOT NULL DEFAULT '["SLA_BREACH"]'`
2. 可选：`CREATE INDEX IF NOT EXISTS idx_webhook_config_scopes ON cs_conversation.cs_webhook_config USING GIN (scopes)`

## 5. 后端组件设计

### 5.1 架构与数据流

```
业务事件点                            WebhookEventPublisher               WebhookDispatcher (@Async)
┌─────────────────────┐              ┌──────────────────────┐           ┌────────────────────────┐
│ SlaBreachNotifier   │──publish───▶ │ 1. selectEnabledByScope│ ──dispatch▶│ 1. 按 type 选 Sender    │
│ SessionQueueService │  SLA_BREACH  │ 2. 空集合短路         │  (逐个)     │ 2. 渲染模板             │
│ (转人工处)          │  SESSION_*   │ 3. 逐个异步分发       │ ─────────▶ │ 3. Feishu/Dingtalk/    │
│ CsatService.rate    │  CSAT_RATED  │                        │           │    Wecom/Custom 发送    │
└─────────────────────┘              └──────────────────────┘           │ 4. SLA 成功标记 notified│
                                                                         └────────────────────────┘
```

要点：

- **同步发布、异步分发**：业务侧调用 `publish()` 仅做一次 DB 查询 + 提交 `@Async` 任务，不阻塞业务主流程（`webhookExecutor` 线程池）。
- **空集合短路**：无匹配 webhook 时零开销返回。
- **SLA 通知标记**：SLA 违规推送成功（Sender 返回成功）后回写 `webhook_notified_at`，保持现有审计语义。

### 5.2 新增 `WebhookEventContext`（泛化事件上下文）

替代 `SlaBreachContext`，适配所有事件类型：

```java
@Builder
@Data
public class WebhookEventContext {
    /** 触发的事件范围（必须） */
    private WebhookScope scope;
    /** 细化事件类型：SLA 的 WAIT/FRT/HANDLE；会话的 CREATED/TRANSFER/CLOSED；评价的 RATED */
    private String eventType;
    /** 会话 ID（可为空，如系统级事件） */
    private String sessionId;
    /** 访客名称（可为空） */
    private String visitorName;
    /** 事件专属业务字段（SLA 违规明细 / 会话状态 / 评分等） */
    private Map<String, Object> payload;
    /**
     * 推送成功后的回调（可选）。由事件发布方注入（如 SLA 违规回写 webhook_notified_at），
     * WebhookDispatcher 发送成功后统一调用；失败不调用。使 dispatcher 保持通用，无需按 scope 特判。
     */
    private Runnable onSuccess;
}
```

- `SlaBreachContext` 删除，`SlaBreachNotifier` 与 `WebhookDispatcher` 改为使用 `WebhookEventContext`。
- 各 Sender 渲染消息时，从 `scope` + `eventType` + `payload` 取字段；模板占位符按事件类型定义（见 5.4）。

### 5.3 新增 `WebhookEventPublisher`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventPublisher {

    private final WebhookConfigMapper webhookConfigMapper;
    private final WebhookDispatcher   webhookDispatcher;

    /**
     * 发布事件：查询订阅该 scope 的启用 webhook，逐个异步分发。
     * 无匹配 webhook 时直接返回（零开销）。
     *
     * <p><b>故障隔离（关键）</b>：整体 try/catch 包裹，任何异常只记 ERROR 日志后返回，
     * 绝不向上抛出——调用点位于 CsatService.rate（@Transactional）、
     * SessionQueueService.enqueue/close/transfer、VisitorSessionService.getOrCreate
     * （Redisson 锁内）等业务主流程，抛出会导致事务回滚 / 接口 500 / 锁内异常。
     */
    public void publish(WebhookScope scope, WebhookEventContext ctx) {
        try {
            List<WebhookConfigEntity> targets = webhookConfigMapper.selectEnabledByScope(scope.name());
            if (targets.isEmpty()) {
                return;
            }
            if (ctx.getScope() == null) {
                ctx.setScope(scope); // 仅 null 时兜底，不静默覆盖调用方显式值
            }
            targets.forEach(webhook -> webhookDispatcher.dispatch(webhook, ctx));
        } catch (Exception e) {
            log.error("[WebhookPublisher] 发布失败 scope={} sessionId={}",
                    scope, ctx.getSessionId(), e);
        }
    }
}
```

### 5.4 `WebhookDispatcher` 与 Sender 改造

**签名变更：**

```java
// 旧
@Async("webhookExecutor")
public void dispatch(List<Long> webhookIds, SlaBreachContext ctx, List<Long> breachIds)

// 新
@Async("webhookExecutor")
public void dispatch(WebhookConfigEntity webhook, WebhookEventContext ctx)
```

**内部流程：**
1. 按 `webhook.getType()` 路由到对应 Sender（Feishu / Dingtalk / Wecom / Custom）。
2. 模板选择：`webhook.getMessageTemplate()` 为空时使用按 `scope` 分类的默认模板（由 `WebhookDefaultTemplate` 提供）；否则渲染自定义模板（占位符统一为 `${var}` 语法，如 `${sessionId}`、`${visitorName}`、`${policyName}`、`${score}` 等，与 `AbstractWebhookSender.renderTemplate` 现有实现一致）。
3. 发送成功后：若 `scope == SLA_BREACH` 且 payload 携带 `breachIds`，调用 `SlaBreachRecorder.markWebhookNotified(breachIds, now)`（新增批量方法，委托给既有 `SlaBreachMapper.updateWebhookNotifiedAt`）。
4. 发送失败：记录 WARN 日志，不重试、不抛错（保持"通知失败不影响主流程"的既有语义）。

**各 Sender 现状复用：** `FeishuWebhookSender` / `DingtalkWebhookSender` / `WecomWebhookSender` / `CustomWebhookSender` 保留，仅调整入参（从 `SlaBreachContext` 改为 `WebhookEventContext`）；`WebhookTestSender` 发送固定测试消息不变。

### 5.5 `SlaBreachNotifier` 改造

删除 webhook 绑定路径，改为事件发布：

```java
// 旧：if (actions.getWebhookIds() != null && !actions.getWebhookIds().isEmpty()) { ... dispatch(...) }

// 新：直接发布，publisher 内部空集合短路，无匹配 webhook 时零开销
webhookEventPublisher.publish(WebhookScope.SLA_BREACH,
        WebhookEventContextFactory.buildSlaBreach(session, policy, newBreaches));
```

- `payload.breachIds` = 本轮新违规的 ID 列表，用于推送成功后回写 `webhook_notified_at`。
- 移除 `SlaBreachContext`、`WebhookDispatcher` 的构造依赖（改注入 `WebhookEventPublisher`）。
- 该发布点在 SSE 聚合推送之后、升级判断之后执行（与原 webhook 分支位置一致），顺序不依赖 webhook 推送结果。

### 5.6 会话生命周期发布点

| 事件 | 挂载位置 | 说明 |
|---|---|---|
| `SESSION_CREATED` | 会话初始化成功处（`VisitorSessionService` / `SessionQueueService` 创建会话后） | 幂等：仅在会话首次创建时发布，恢复/续用旧会话不重复发布 |
| `SESSION_TRANSFERRED` | 转人工方法（用户请求转人工 `transfer`、座席转接）成功后 | 与现有 `SessionEventType.TRANSFER/ENQUEUE` 的 SSE 发布点相邻 |
| `SESSION_CLOSED` | 会话关闭处（`SessionQueueService.close` 或 DB 状态落地消费处） | 与 `SessionEventType.CLOSED` 发布点相邻 |

> 实现细节：3 个发布点均调用 `webhookEventPublisher.publish(scope, ctx)`，context 由事件发生处的现有领域对象（`ConversationEntity` / 转接参数）构造。为避免重复代码，可在 `infrastructure/webhook` 包下提供事件上下文工厂（如 `WebhookEventContextFactory`）。

### 5.7 CSAT 发布点

`CsatService.rate()` 评分提交成功后：

```java
webhookEventPublisher.publish(WebhookScope.CSAT_RATED,
    WebhookEventContext.builder()
        .eventType("RATED")
        .sessionId(rating.getSessionId())
        .visitorName(visitorName)   // 由调用方按需传入，可空
        .payload(Map.of("csatId", csatId, "score", score, "comment", comment == null ? "" : comment,
                        "channel", rating.getChannel()))
        .build());
```

> 注：`CsatRatingDO` 无 `visitorName` 字段（仅 `visitorId`），如需访客名由调用方（`CsatController`）查询传入或置空。

### 5.8 默认消息模板

按 scope 提供默认模板（既有 SLA 默认模板内容保留并迁移到 `SLA_BREACH` 分类下）。模板占位符统一为 `${var}` 语法，由 `WebhookDefaultTemplate` 按 scope 提供，各 Sender 共享：

| Scope | 默认模板示例 |
|---|---|
| SLA_BREACH | `【SLA违规】会话 ${sessionId} 触发 ${eventType} ${stage}：目标 ${targetSec}s / 实际 ${actualSec}s` |
| SESSION_CREATED | `【新会话】访客 ${visitorName} 进入会话 ${sessionId}` |
| SESSION_TRANSFERRED | `【转人工】访客 ${visitorName} 转接会话 ${sessionId}` |
| SESSION_CLOSED | `【会话关闭】会话 ${sessionId} 已结束` |
| CSAT_RATED | `【客户评价】会话 ${sessionId} 评分 ${score} 星，评价：${comment}` |

## 6. API 变更

### 6.1 请求 / 响应结构

`POST /api/v1/admin/sla/webhooks` 与 `PUT /api/v1/admin/sla/webhooks/{id}` 请求体新增 `scopes`：

```json
{
  "name": "飞书告警机器人",
  "type": "FEISHU",
  "url": "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
  "secret": "optional-sign-secret",
  "messageTemplate": "可选自定义模板",
  "isEnabled": 1,
  "scopes": ["SLA_BREACH", "SESSION_TRANSFERRED"]
}
```

响应（`GET` 列表 / 创建 / 更新返回值）同步包含 `scopes` 字段。

### 6.2 校验规则

| 字段 | 规则 | 失败行为 |
|---|---|---|
| `scopes` | 可选；非空时数组元素必须是 `WebhookScope` 枚举名（`SLA_BREACH` / `SESSION_CREATED` / `SESSION_TRANSFERRED` / `SESSION_CLOSED` / `CSAT_RATED`）；不允许重复 | 400，`INVALID_PARAM` |
| `scopes` 为空数组 | 允许，等价"不订阅任何事件" | — |
| 未传 `scopes` | 后端默认 `["SLA_BREACH"]`（与 DB 列默认值一致） | — |

实现：`WebhookReq` 增加 `private List<String> scopes;`，Controller 校验后合入实体；枚举合法性校验建议用 `@Pattern` 或 Service 层校验（枚举名集合包含判断），返回业务码 `40000`。

### 6.3 接口兼容性

- **既有接口路径不变**（`/api/v1/admin/sla/webhooks`）。路径中的 `sla` 为历史遗留命名，本期不改路径避免前端与既有测试用例（`CONV-WH-001~008`）大改；可在文档中标注"通用 Webhook 配置"，后续如需可加别名路由。
- 老客户端不传 `scopes` 时行为不变（默认订阅 SLA 违规）。
- `GET` 列表响应新增字段为向后兼容（加字段不破坏旧客户端）。

### 6.4 测试接口

`POST /api/v1/admin/sla/webhooks/{id}/test` 不变：发送固定测试消息验证 URL 可达，不校验 scope。

## 7. 前端改动（aria-frontend）

### 7.1 API 类型与封装

`apps/src/api/webhook/index.ts`：

```ts
// WebhookVO 增加 scopes
export interface WebhookVO {
  id: number | string;
  name: string;
  type: 'CUSTOM' | 'DINGTALK' | 'FEISHU' | 'WECOM';
  url: string;
  secret?: string;
  customHeaders?: Record<string, string>;
  messageTemplate?: string;
  isEnabled: 0 | 1;
  scopes: string[]; // 新增
}

// 创建/更新请求体同步携带 scopes（Omit<WebhookVO,'id'> 自然包含）
```

### 7.2 `views/system/sla/webhook.vue`

1. **表单新增「事件范围」多选**（`Checkbox.Group` 或 `Select mode="multiple"`）：
   - 选项与说明：`SLA_BREACH`（SLA 违规告警）、`SESSION_CREATED`（新会话）、`SESSION_TRANSFERRED`（转人工）、`SESSION_CLOSED`（会话关闭）、`CSAT_RATED`（客户评价）。
   - 默认值：`['SLA_BREACH']`（与后端默认一致）。
   - 提交前校验：允许为空数组（提示"不订阅任何事件将不会收到推送"），非空时逐项校验枚举名。
2. **表格增加「事件范围」列**：以 Tag 列表渲染 `scopes`（如 `SLA违规` / `新会话` / `转人工` / `会话关闭` / `客户评价` 中文映射，超过 3 个折叠展示 "+N"）。
3. **编辑回显**：`openEdit` 时 `scopes: row.scopes ?? ['SLA_BREACH']`。
4. **提交载荷**：`buildPayload` 增加 `scopes: form.scopes`。

### 7.3 SLA 策略页面

`views/system/sla/index.vue`：若策略表单存在 webhook 绑定项（当前 `SlaBreachActions.webhookIds` 对应 UI），移除该项并提示"违规通知按 Webhook 配置的事件范围自动匹配"；若表单未暴露该字段则无需改动。

### 7.4 菜单与路由

`system/sla` 菜单组已含 `webhook.vue`（策略 / 违规记录 / Webhook 三个页面），确认路由与权限码 `system:sla:manage` 已配置，本期不新增菜单。

## 8. 测试与验证

### 8.1 后端单元测试

| 组件 | 用例 | 断言 |
|---|---|---|
| `WebhookEventPublisher` | scope 命中（存在订阅该 scope 的启用 webhook） | 每个命中 webhook 的 `dispatch` 被调用，ctx.scope 正确 |
| `WebhookEventPublisher` | scope 未命中（无 webhook 订阅） | `selectEnabledByScope` 返回空，`dispatch` 零调用 |
| `WebhookEventPublisher` | 禁用 webhook 不参与匹配 | 仅启用项被分发 |
| `WebhookConfigMapper.selectEnabledByScope` | jsonb 包含查询正确性 | 多 scope 命中、空 scopes 不命中、禁用过滤 |
| `SlaBreachNotifier` | 违规发生时发布 `SLA_BREACH` 事件 | mock publisher 收到调用且 payload 含 breachIds |
| `WebhookDispatcher` | CUSTOM 类型带 customHeaders 发送、SLA 成功后回写 notified | mock Sender 断言入参；`markWebhookNotified` 被调用 |
| 会话/CSAT 发布点 | 各事件点触发发布 | mock publisher 断言 scope 与关键 payload 字段 |

### 8.2 前端验证（手动）

1. 打开管理后台 → 系统管理 → SLA → Webhook，新增配置，勾选多个事件范围，保存。
2. 列表展示订阅范围 Tag；编辑回显正确。
3. 点击「测试」，确认测试消息到达目标群/URL。
4. 触发对应事件（制造 WAIT 违规 / 发起转人工 / 关闭会话 / 提交评价），确认收到对应推送。

### 8.3 端到端验证（后端+前端）

1. 创建 CUSTOM webhook（`https://webhook.site/xxx`），勾选 `SLA_BREACH`。
2. 新建访客会话 → 转人工进入 WAITING → 等待超时（WAIT 目标秒数）。
3. 验证：`cs_webhook_config` 新增配置生效；扫描器触发违规后 `webhook.site` 收到 `【SLA违规】...` 推送；`cs_sla_breach.webhook_notified_at` 被回写。
4. 回归：不配置任何 webhook 时，SLA 违规仅走 SSE 告警（`alerted_at` 置位，`webhook_notified_at` 保持 NULL），不抛错不影响主流程。

### 8.4 回归范围

- 现有 SLA 单测（`SlaBreachEvaluatorTest` / `SlaBreachRecorderTest`）不受影响，仅 `SlaBreachNotifier` 相关测试需同步改造。
- 接口自动化用例 `CONV-WH-001~008`：请求体不传 `scopes` 仍应通过（默认兼容）。
- `CONV-SLA-013`（WAIT 违规实测）：不依赖 webhook 配置，不受影响。
