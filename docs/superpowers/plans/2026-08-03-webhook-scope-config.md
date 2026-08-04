# 通用 Webhook 配置（支持事件范围）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 SLA 专用 webhook 改造为通用事件通知体系——webhook 配置声明订阅的事件范围（scope），业务事件按 scope 自动匹配推送，前后端同步交付。

**Architecture:** 新增 `WebhookScope` 枚举 + 泛化 `WebhookEventContext` 替代 `SlaBreachContext`；新增 `WebhookEventPublisher`（查 scopes 匹配的启用 webhook → `WebhookDispatcher` 异步分发）；`SlaBreachNotifier` 移除策略级 `webhookIds` 绑定，改发布 `SLA_BREACH` 事件；会话生命周期（创建/转人工/关闭）与 CSAT 评价各挂发布点。前端 `webhook.vue` 增加范围多选。

**Tech Stack:** Java 17 / Spring Boot / MyBatis-Plus / PostgreSQL jsonb / Flyway（本服务无，schema 由 `docs/sql/conversation-service-schema.sql` + 增量 SQL 管理）/ Vue 3 / vben-admin / ant-design-vue

**Spec:** `docs/superpowers/specs/2026-08-03-webhook-scope-design.md`（commit `e62ffa7`）

## Global Constraints

- 事件范围枚举值固定 5 个：`SLA_BREACH`、`SESSION_CREATED`、`SESSION_TRANSFERRED`、`SESSION_CLOSED`、`CSAT_RATED`。
- `cs_webhook_config.scopes` 列：`jsonb NOT NULL DEFAULT '["SLA_BREACH"]'`（兼容存量与缺省请求）。
- `scopes` 空数组 = 不订阅任何事件，不推送。
- 禁用（`is_enabled=0`）webhook 不参与匹配。
- `SlaBreachActions.webhookIds` 字段**移除**；`cs_sla_policy.actions` JSON 残留该键由 Jackson 默认忽略（Spring Boot 默认关闭 `FAIL_ON_UNKNOWN_PROPERTIES`），无需数据清理。
- 后端在 `aria-server` 仓库，前端在 `/Users/lycodeing/WebstormProjects/aria-frontend` 仓库，两个仓库各自独立 commit。
- 现有接口路径 `/api/v1/admin/sla/webhooks` 不变（历史命名，本期不迁移）。
- 分发的异步线程池 `webhookExecutor`、重试逻辑（3 次指数退避）、`SlaBreachMapper.updateWebhookNotifiedAt` 批量回写均**复用现有实现**，不新增。
- **现状基线（I6，2026-08-03 核实）**：conversation-service 现有测试套件在 HEAD **不可编译**——`WebhookDispatcherTest`/`FeishuWebhookSenderTest` 使用 `breachType("WAIT")`/`stage("BREACH")`（String），而 `SlaBreachEntity.breachType/stage` 已枚举化（BreachType/BreachStage）；且 conversation-service 编译依赖本地 maven 仓库的 `aria-common-web`，未 install 时 `SessionQueueRepository.hPutIfAbsent` 找不到符号。**Task 5/6 重写相关测试即修复枚举问题；所有 Maven 命令必须从仓库根执行 `mvn -pl ai-conversation/conversation-service -am ...`（-am 连带构建 common 模块）。**
- 每个任务结束必须运行对应测试并 commit；提交信息遵循仓库现有约定（`feat(conversation): ...` / `fix(conversation): ...` / `feat: ...`）。

## 文件结构总览

**后端（aria-server，conversation-service）：**

| 文件 | 动作 | 职责 |
|---|---|---|
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/WebhookScope.java` | 新建 | 事件范围枚举 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContext.java` | 新建 | 泛化事件上下文（替代 SlaBreachContext） |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContextFactory.java` | 新建 | 事件上下文工厂（覆盖 5 个 scope） |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventTypes.java` | 新建 | 事件细化类型常量 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookDefaultTemplate.java` | 新建 | 按 scope 的默认模板提供者 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/SlaBreachContext.java` | 删除 | 被 WebhookEventContext 替代 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventPublisher.java` | 新建 | 事件发布器（scope 匹配 + 异步分发） |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/WebhookConfigEntity.java` | 修改 | 加 `scopes` 字段 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/WebhookConfigMapper.java` | 修改 | 加 `selectEnabledByScope` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookDispatcher.java` | 修改 | 签名改 `dispatch(WebhookConfigEntity, WebhookEventContext)` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/AbstractWebhookSender.java` | 修改 | `buildVariables` 泛化 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/{Feishu,Dingtalk,Wecom,Custom}WebhookSender.java` | 修改 | `send(WebhookConfigEntity, WebhookEventContext)` 适配 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookTestSender.java` | 修改 | 测试消息改构造 `WebhookEventContext` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookSender.java` | 修改 | 接口签名改 `WebhookEventContext` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/scheduler/SlaBreachNotifier.java` | 修改 | 移除 webhookIds，发布 SLA_BREACH |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/SlaBreachActions.java` | 修改 | 移除 `webhookIds` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/WebhookController.java` | 修改 | `WebhookReq` 加 `scopes` 与校验 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/WebhookAppService.java` | 修改 | 创建/更新合入 scopes |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorSessionService.java` | 修改 | 新建会话发布 `SESSION_CREATED` |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java` | 修改 | 转人工/关闭发布事件 |
| `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/CsatService.java` | 修改 | 评分成功发布 `CSAT_RATED` |
| `docs/sql/conversation-service-schema.sql` | 修改 | cs_webhook_config 定义加 scopes 列 |
| `docs/sql/migrations/2026-08-03-add-webhook-scopes.sql` | 新建 | 增量迁移（现有环境执行） |

**后端测试：**

| 文件 | 动作 |
|---|---|
| `src/test/.../infrastructure/webhook/WebhookDispatcherTest.java` | 修改（新签名） |
| `src/test/.../infrastructure/webhook/FeishuWebhookSenderTest.java` | 修改（新签名） |
| `src/test/.../scheduler/SlaBreachNotifierTest.java` | 新建 |
| `src/test/.../infrastructure/webhook/WebhookEventPublisherTest.java` | 新建 |
| `src/test/.../application/service/SessionQueueEnqueueOfflineTest.java`、`SessionQueueServiceGetAgentIdTest.java` | 修改（构造器加参） |
| `src/test/.../application/service/CsatServiceTest.java`、`CsatServicePendingTest.java` | 修改（mock publisher） |
| `src/test/.../application/service/VisitorSessionServiceTest.java` | 修改（mock publisher） |

**前端（aria-frontend）：**

| 文件 | 动作 |
|---|---|
| `apps/src/api/webhook/index.ts` | 修改（WebhookVO 加 scopes） |
| `apps/src/views/system/sla/webhook.vue` | 修改（范围多选 + 表格范围列） |
| `apps/src/views/system/sla/index.vue` | 检查/移除 webhook 绑定项 |

---

### Task 1: WebhookScope 枚举 + WebhookEventContext + 事件上下文工厂

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/WebhookScope.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContext.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventTypes.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContextFactory.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/WebhookEventContextTest.java`
- （`SlaBreachContext.java` 的删除在 Task 6，本任务不动）

**Interfaces:**
- Produces:
  - `enum WebhookScope { SLA_BREACH, SESSION_CREATED, SESSION_TRANSFERRED, SESSION_CLOSED, CSAT_RATED }`
  - `@Builder @Data class WebhookEventContext { WebhookScope scope; String eventType; String sessionId; String visitorName; Map<String,Object> payload; Runnable onSuccess; }`
  - `WebhookEventTypes` 常量类（SESSION_CREATED/SESSION_ENQUEUE/SESSION_TRANSFER/SESSION_CLOSED/CSAT_RATED）
  - `WebhookEventContextFactory.buildSlaBreach(...)` / `buildSessionEvent(...)` / `buildCsatRated(...)` → `WebhookEventContext`

- [ ] **Step 1: 明确 SlaBreachContext 删除时机（本任务不删）**

`SlaBreachContext` 当前被 `WebhookSender`/`AbstractWebhookSender`/各 Sender/`WebhookDispatcher`/`SlaBreachNotifier`/`WebhookTestSender` 引用。本任务**仅新建** `WebhookScope`/`WebhookEventContext`/`WebhookEventTypes`/`WebhookEventContextFactory`，**不删除** `SlaBreachContext`（保证本任务提交后工程可编译）。删除动作统一放在 Task 6（SlaBreachNotifier 改造完成后），届时全部引用已切换到 `WebhookEventContext`。

- [ ] **Step 2: 新建 WebhookScope 枚举**

```java
package com.aria.conversation.domain.model;

/**
 * Webhook 事件范围枚举。
 * webhook 配置通过 scopes 字段声明订阅哪些事件；业务事件按 scope 自动匹配推送。
 */
public enum WebhookScope {
    /** SLA 违规告警（含 WARNING 预警 / BREACH 正式违规两阶段） */
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

- [ ] **Step 3: 新建 WebhookEventContext**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 通用 Webhook 事件上下文（替代 SlaBreachContext）。
 * 携带触发事件的范围、细化类型与业务 payload，供各 WebhookSender 渲染消息。
 *
 * <p>并发注意：同一实例可能被 {@link WebhookDispatcher} 并发传给多个 @Async 分发任务，
 * Sender 必须只读本对象（当前字段仅读），不要在其内部修改 ctx。
 */
@Builder
@Data
public class WebhookEventContext {

    /** 触发的事件范围（必须） */
    private WebhookScope scope;

    /** 细化事件类型：SLA 的 WAIT/FRT/HANDLE；会话的 ENQUEUE/TRANSFER/CLOSED/CREATED；评价的 RATED */
    private String eventType;

    /** 会话 ID（可为空，如系统级事件） */
    private String sessionId;

    /** 访客名称（可为空） */
    private String visitorName;

    /** 事件专属业务字段（SLA 违规明细 / 会话状态 / 评分等） */
    private Map<String, Object> payload;

    /**
     * 推送成功后的回调（可选）。由事件发布方注入（如 SLA 违规回写 webhook_notified_at），
     * WebhookDispatcher 发送成功后统一调用；失败不调用。
     * 使 dispatcher 保持通用，无需按 scope 特判。
     */
    private Runnable onSuccess;
}
```

- [ ] **Step 4a: 新建 WebhookEventTypes 常量类（集中 eventType 魔法串）**

```java
package com.aria.conversation.infrastructure.webhook;

/**
 * Webhook 事件细化类型常量，集中定义避免各处魔法字符串。
 */
public final class WebhookEventTypes {

    private WebhookEventTypes() {}

    // SLA 违规（eventType 复用 BreachType：WAIT/FRT/HANDLE）
    // 会话生命周期
    public static final String SESSION_CREATED = "CREATED";
    public static final String SESSION_ENQUEUE = "ENQUEUE";
    public static final String SESSION_TRANSFER = "TRANSFER";
    public static final String SESSION_CLOSED = "CLOSED";
    // 客户评价
    public static final String CSAT_RATED = "RATED";
}
```

- [ ] **Step 4b: 新建 WebhookEventContextFactory（覆盖全部 5 个 scope，统一构造）**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;

import java.util.List;
import java.util.Map;

/**
 * Webhook 事件上下文工厂：统一构造各事件类型的上下文，避免调用方重复拼装。
 * 所有 scope 的事件上下文均通过本工厂构造，事件类型使用 {@link WebhookEventTypes} 常量。
 */
public final class WebhookEventContextFactory {

    private WebhookEventContextFactory() {}

    /** 构造 SLA 违规事件上下文（payload 含 policyName/breaches/breachIds）。 */
    public static WebhookEventContext buildSlaBreach(ConversationEntity session,
                                                      SlaPolicyEntity policy,
                                                      List<SlaBreachEntity> breaches) {
        SlaBreachEntity first = breaches.get(0);
        return WebhookEventContext.builder()
                .scope(WebhookScope.SLA_BREACH)
                .eventType(first.getBreachType() != null ? first.getBreachType().getValue() : "")
                .sessionId(session.getSessionId())
                .visitorName(session.getVisitorName())
                .payload(Map.of(
                        "policyName", policy.getName(),
                        "breaches", breaches,
                        "breachIds", breaches.stream().map(SlaBreachEntity::getId).toList()))
                .build();
    }

    /** 构造会话生命周期事件上下文（SESSION_CREATED / SESSION_TRANSFERRED / SESSION_CLOSED）。 */
    public static WebhookEventContext buildSessionEvent(WebhookScope scope,
                                                        String eventType,
                                                        String sessionId,
                                                        String visitorName,
                                                        Map<String, Object> extra) {
        return WebhookEventContext.builder()
                .scope(scope)
                .eventType(eventType)
                .sessionId(sessionId)
                .visitorName(visitorName)
                .payload(extra)
                .build();
    }

    /** 构造客户评价事件上下文（payload 含 csatId/score/comment/channel）。 */
    public static WebhookEventContext buildCsatRated(String sessionId,
                                                     Long csatId,
                                                     Object score,
                                                     String comment,
                                                     String channel) {
        return WebhookEventContext.builder()
                .scope(WebhookScope.CSAT_RATED)
                .eventType(WebhookEventTypes.CSAT_RATED)
                .sessionId(sessionId)
                .payload(Map.of(
                        "csatId", csatId,
                        "score", score == null ? "" : score,
                        "comment", comment == null ? "" : comment,
                        "channel", channel == null ? "" : channel))
                .build();
    }
}
```

- [ ] **Step 5: 新建单元测试（验证 builder 与 factory）**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventContextTest {

    @Test
    @DisplayName("buildSlaBreach 填充 scope/eventType/sessionId/payload")
    void buildSlaBreach_fillsAllFields() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-1");
        session.setVisitorName("张三");

        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("默认 SLA");

        SlaBreachEntity breach = SlaBreachEntity.builder()
                .id(10L)
                .sessionId("sess-1")
                .build();
        breach.setBreachType(com.aria.conversation.domain.model.BreachType.WAIT);

        WebhookEventContext ctx = WebhookEventContextFactory.buildSlaBreach(
                session, policy, List.of(breach));

        assertThat(ctx.getScope()).isEqualTo(WebhookScope.SLA_BREACH);
        assertThat(ctx.getEventType()).isEqualTo("WAIT");
        assertThat(ctx.getSessionId()).isEqualTo("sess-1");
        assertThat(ctx.getVisitorName()).isEqualTo("张三");
        assertThat(ctx.getPayload())
                .containsEntry("policyName", "默认 SLA")
                .containsEntry("breachIds", List.of(10L));
    }

    @Test
    @DisplayName("WebhookEventContext builder 可构建任意事件上下文")
    void builder_buildsGenericContext() {
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SESSION_CLOSED)
                .eventType("CLOSED")
                .sessionId("sess-2")
                .build();
        assertThat(ctx.getScope()).isEqualTo(WebhookScope.SESSION_CLOSED);
        assertThat(ctx.getEventType()).isEqualTo("CLOSED");
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest=WebhookEventContextTest`
Expected: BUILD SUCCESS，2 tests passed

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/WebhookScope.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContext.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventContextFactory.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/SlaBreachContext.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/WebhookEventContextTest.java
git commit -m "feat(conversation): 通用 WebhookEventContext 替代 SlaBreachContext"
```

> 注：本任务删除 `SlaBreachContext` 会导致 `WebhookSender`/`AbstractWebhookSender`/各 Sender/`WebhookDispatcher`/`SlaBreachNotifier` 编译失败——这些在 Task 5/6 修复。为保持中间提交可编译，可在 Step 7 前临时保留 SlaBreachContext 文件、Step 7 提交时一并带上删除；若追求每步可编译，将删除动作推迟到 Task 5/6 完成后再做。**推荐顺序：本任务先不删 SlaBreachContext（仅新建三个类），删除动作放 Task 6 末尾统一执行。**

---

### Task 2: 数据模型迁移（schema 文档 + 增量 SQL + 现有环境 ALTER）

**Files:**
- Modify: `docs/sql/conversation-service-schema.sql`（cs_webhook_config 定义）
- Create: `docs/sql/migrations/2026-08-03-add-webhook-scopes.sql`

**Interfaces:**
- Produces: `cs_webhook_config.scopes jsonb NOT NULL DEFAULT '["SLA_BREACH"]'`（已存在环境）

- [ ] **Step 1: 更新 schema 文档**

在 `docs/sql/conversation-service-schema.sql` 中 `cs_webhook_config` 的 `message_template TEXT,` 行后插入 `scopes` 列定义：

```sql
    message_template TEXT,
    scopes           JSONB         NOT NULL DEFAULT '["SLA_BREACH"]',
    is_enabled       SMALLINT      NOT NULL DEFAULT 1,
```

- [ ] **Step 2: 新建增量迁移 SQL**

```sql
-- 2026-08-03 通用 Webhook 配置：新增事件范围（scope）订阅
-- 适用：已存在的数据库环境（新环境由 conversation-service-schema.sql 全量初始化）
-- 执行方式：psql -U postgres -d aria_cs -f 本文件
ALTER TABLE cs_conversation.cs_webhook_config
    ADD COLUMN scopes JSONB NOT NULL DEFAULT '["SLA_BREACH"]';

COMMENT ON COLUMN cs_conversation.cs_webhook_config.scopes
    IS '订阅的事件范围数组（WebhookScope 枚举名），空数组=不订阅任何事件；默认 ["SLA_BREACH"]';
```

- [ ] **Step 3: 在现有环境执行（幂等保护）**

```bash
docker exec ai-cs-postgres psql -U postgres -d aria_cs -v ON_ERROR_STOP=1 \
  -c "ALTER TABLE cs_conversation.cs_webhook_config ADD COLUMN IF NOT EXISTS scopes JSONB NOT NULL DEFAULT '[\"SLA_BREACH\"]';"
```

- [ ] **Step 4: 验证列已存在**

```bash
docker exec ai-cs-postgres psql -U postgres -d aria_cs -c "\d cs_conversation.cs_webhook_config"
```
Expected: 输出包含 `scopes | jsonb | not null | '["SLA_BREACH"]'::jsonb`

- [ ] **Step 5: Commit**

```bash
git add docs/sql/conversation-service-schema.sql docs/sql/migrations/2026-08-03-add-webhook-scopes.sql
git commit -m "feat(conversation): cs_webhook_config 增加 scopes 事件范围列"
```

---

### Task 3: WebhookConfigEntity.scopes 字段 + Mapper.selectEnabledByScope

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/WebhookConfigEntity.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/WebhookConfigMapper.java`

**Interfaces:**
- Consumes: `cs_webhook_config.scopes` 列（Task 2）、`StringListTypeHandler`（已存在：`com.aria.conversation.infrastructure.config.StringListTypeHandler`）
- Produces: `WebhookConfigEntity.getScopes()/setScopes()`；`List<WebhookConfigEntity> WebhookConfigMapper.selectEnabledByScope(String scope)`

- [ ] **Step 1: 实体加 scopes 字段**

在 `WebhookConfigEntity` 中 `messageTemplate` 字段后新增：

```java
    /** 订阅的事件范围列表（WebhookScope 枚举名），空数组表示不订阅任何事件 */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> scopes;
```

补充 import：`import com.aria.conversation.infrastructure.config.StringListTypeHandler;`、`import java.util.List;`

- [ ] **Step 2: Mapper 加按范围查询**

在 `WebhookConfigMapper` 接口中新增方法。**注意：不能使用 `@Select("SELECT *")` 手写 SQL（违反项目约定 + 自定映射无法处理 jsonb 列）**，必须使用 `BaseMapper` 的 `LambdaQueryWrapper` + `apply`：

```java
    /**
     * 查询订阅了指定事件范围且启用的 Webhook 配置（按 id 升序）。
     * 使用 jsonb 数组包含操作符 {@code @>}，通过 MyBatis-Plus apply 参数化注入。
     *
     * <p>使用 apply 而非 @Select 手写 SQL，原因：
     * <ol>
     *   <li>保持 MyBatis-Plus 自动 ResultMap，确保 jsonb 列（scopes/customHeaders）通过 StringListTypeHandler/StringMapTypeHandler 正确映射</li>
     *   <li>避免 @Select 全量 SELECT * 不走 LambdaQueryWrapper 的自动列映射</li>
     * </ol>
     *
     * @param scope WebhookScope 枚举名，如 "SLA_BREACH"
     * @return 匹配的启用配置列表，无则返回空列表
     */
    default List<WebhookConfigEntity> selectEnabledByScope(String scope) {
        return selectList(Wrappers.<WebhookConfigEntity>lambdaQuery()
                .eq(WebhookConfigEntity::getIsEnabled, 1)
                .apply("scopes @> ('[\"' || {0} || '\"]')::jsonb", scope)
                .orderByAsc(WebhookConfigEntity::getId));
    }
```

- [ ] **Step 3: 编译验证**

Run: `mvn -pl ai-conversation/conversation-service -am compile`
Expected: BUILD SUCCESS（注意：`-am` 自动编译 common-web 模块解决 `hPutIfAbsent` 签名缺失问题）

- [ ] **Step 4: 使用 Testcontainers 或真实 PG 集成验证 JSONB 查询**

新增集成测试（`src/test/.../persistence/mapper/WebhookConfigMapperIntegrationTest.java`），覆盖以下场景：
- 插入一条 `scopes=["SLA_BREACH","SESSION_CREATED"]` 的 webhook，查询 `"SLA_BREACH"` → 命中
- 查询未订阅的 `"CSAT_RATED"` → 不命中
- `is_enabled=0` 的配置不命中
- `scopes=[]`（空数组）的配置不命中

需使用 Testcontainers（PostgreSQL image），在 `@DataJpaTest` 或 `@MybatisPlusTest` 中执行。若无 Testcontainers 基础设施，至少准备一段真实 PG 手动验证 SQL 并在计划文档中记录。

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/WebhookConfigEntity.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/WebhookConfigMapper.java
git commit -m "feat(conversation): Webhook 配置实体与 Mapper 支持 scopes 事件范围"
```

> 注：`apply("{0}")` 的占位符语法为 MyBatis-Plus 原生参数化机制，由 `Wrappers` 内部处理，无 SQL 注入风险。

---

### Task 4: WebhookEventPublisher（scope 匹配 + 异步分发）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventPublisher.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/WebhookEventPublisherTest.java`

**Interfaces:**
- Consumes: `WebhookScope`（Task 1）、`WebhookEventContext`（Task 1）、`WebhookConfigMapper.selectEnabledByScope(String)`（Task 3）、`WebhookDispatcher.dispatch(WebhookConfigEntity, WebhookEventContext)`（Task 5，本任务以接口签名为准编译）
- Produces: `void WebhookEventPublisher.publish(WebhookScope scope, WebhookEventContext ctx)`

> 依赖说明：本任务引用的 `WebhookDispatcher.dispatch(WebhookConfigEntity, WebhookEventContext)` 新签名在 Task 5 实现。**执行顺序建议：Task 4 与 Task 5 连续完成（同一提交周期），或先按新签名改好 dispatcher 再写 publisher。** 本任务代码以最终签名编写，编译验证放到 Task 5 完成后统一执行。

- [ ] **Step 1: 新建 WebhookEventPublisher**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 事件发布器。
 *
 * <p>业务事件点调用 {@link #publish(WebhookScope, WebhookEventContext)}，
 * 内部查询订阅该 scope 的启用 webhook 并逐个异步分发：
 * <ol>
 *   <li>通过 {@link WebhookConfigMapper#selectEnabledByScope} 匹配订阅 webhook</li>
 *   <li>空集合直接返回（零开销）</li>
 *   <li>逐个提交给 {@link WebhookDispatcher} 异步发送（webhookExecutor 线程池）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventPublisher {

    private final WebhookConfigMapper webhookConfigMapper;
    private final WebhookDispatcher   webhookDispatcher;

    /**
     * 发布事件：查询订阅该 scope 的启用 webhook，逐个异步分发。
     *
     * <p><b>故障隔离（关键）</b>：整体 try/catch 包裹，任何异常（DB 查询失败、
     * dispatcher 异常）都只记 ERROR 日志后返回，绝不向上抛出——
     * 调用点位于 {@code CsatService.rate}（@Transactional）、
     * {@code SessionQueueService.enqueue/close/transfer}、
     * {@code VisitorSessionService.getOrCreate}（Redisson 锁内）等业务主流程，
     * 一旦抛出会导致评分事务回滚、会话关闭 500、锁内异常等连锁故障。
     * 保持"通知失败不影响主流程"语义。
     *
     * @param scope 事件范围（必须非 null）
     * @param ctx   事件上下文；若 ctx.scope 为 null 则以入参为准兜底赋值
     */
    public void publish(WebhookScope scope, WebhookEventContext ctx) {
        try {
            List<WebhookConfigEntity> targets = webhookConfigMapper.selectEnabledByScope(scope.name());
            if (targets.isEmpty()) {
                log.debug("[WebhookPublisher] scope={} 无匹配 webhook，跳过", scope);
                return;
            }
            if (ctx.getScope() == null) {
                ctx.setScope(scope); // 仅 null 时兜底，不静默覆盖调用方显式值
            }
            targets.forEach(webhook -> webhookDispatcher.dispatch(webhook, ctx));
            log.debug("[WebhookPublisher] scope={} 命中 {} 个 webhook", scope, targets.size());
        } catch (Exception e) {
            // 故障隔离：通知链路异常绝不回抛到业务主流程
            log.error("[WebhookPublisher] 发布失败 scope={} sessionId={}",
                    scope, ctx.getSessionId(), e);
        }
    }
}
```

- [ ] **Step 2: 新建单元测试**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookEventPublisherTest {

    @Mock WebhookConfigMapper webhookConfigMapper;
    @Mock WebhookDispatcher   webhookDispatcher;

    WebhookEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WebhookEventPublisher(webhookConfigMapper, webhookDispatcher);
    }

    @Test
    @DisplayName("无匹配 webhook 时零分发且不抛异常")
    void publish_noMatch_skips() {
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenReturn(List.of());

        publisher.publish(WebhookScope.SLA_BREACH,
                WebhookEventContext.builder().eventType("WAIT").build());

        verify(webhookDispatcher, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("命中多个 webhook 时逐个分发，ctx.scope 以入参为准")
    void publish_matched_dispatchesEach() {
        WebhookConfigEntity a = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        WebhookConfigEntity b = WebhookConfigEntity.builder().id(2L).type("CUSTOM").build();
        when(webhookConfigMapper.selectEnabledByScope("SESSION_CLOSED"))
                .thenReturn(List.of(a, b));

        WebhookEventContext ctx = WebhookEventContext.builder().eventType("CLOSED").build();
        publisher.publish(WebhookScope.SESSION_CLOSED, ctx);

        verify(webhookDispatcher).dispatch(eq(a), any(WebhookEventContext.class));
        verify(webhookDispatcher).dispatch(eq(b), any(WebhookEventContext.class));
        // 入参 scope 覆盖 ctx 未设置值
        verify(webhookDispatcher, times(2))
                .dispatch(any(), argThat(c -> c.getScope() == WebhookScope.SESSION_CLOSED));
    }

    @Test
    @DisplayName("DB 查询异常时故障隔离：不抛异常、不分发（关键）")
    void publish_mapperThrows_isSwallowed() {
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenThrow(new RuntimeException("db down"));

        // 不抛异常
        publisher.publish(WebhookScope.SLA_BREACH,
                WebhookEventContext.builder().sessionId("sess-1").build());

        verify(webhookDispatcher, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("ctx 已显式设置 scope 时不被覆盖")
    void publish_explicitScope_keepsValue() {
        WebhookConfigEntity a = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenReturn(List.of(a));

        // 调用方显式设置了一个"错误"scope，publisher 不得覆盖
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.CSAT_RATED)
                .build();
        publisher.publish(WebhookScope.SLA_BREACH, ctx);

        verify(webhookDispatcher).dispatch(eq(a),
                argThat(c -> c.getScope() == WebhookScope.CSAT_RATED));
    }
}
```

- [ ] **Step 3: 运行测试**

Run（需 Task 5 的 dispatcher 新签名就绪后）: `mvn -pl ai-conversation/conversation-service test -Dtest=WebhookEventPublisherTest`
Expected: BUILD SUCCESS，4 tests passed

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookEventPublisher.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/WebhookEventPublisherTest.java
git commit -m "feat(conversation): WebhookEventPublisher 按 scope 匹配分发"
```

---

### Task 5: WebhookDispatcher + Sender 体系泛化（WebhookEventContext）

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/AbstractWebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/FeishuWebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/DingtalkWebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WecomWebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/CustomWebhookSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookTestSender.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookDispatcher.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/WebhookDispatcherTest.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/FeishuWebhookSenderTest.java`

**Interfaces:**
- Consumes: `WebhookEventContext`（Task 1）、`WebhookConfigEntity`（Task 3）
- Produces:
  - `void WebhookSender.send(WebhookConfigEntity config, WebhookEventContext ctx)`
  - `WebhookDispatcher.dispatch(WebhookConfigEntity webhook, WebhookEventContext ctx)`（@Async）
  - `AbstractWebhookSender.buildVariables(WebhookEventContext ctx)` → `Map<String,String>`（含 `sessionId`/`visitorName`/`eventType`/SLA 专属 `breachTypeLabel`/`targetSec`/`actualSec`/`policyName`/`stage` 等）

- [ ] **Step 1: WebhookSender 接口签名变更**

`WebhookSender.java` 中 `send` 方法参数 `SlaBreachContext ctx` 改为 `WebhookEventContext ctx`：

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;

/**
 * Webhook 发送器 SPI。
 * 各实现按 {@link #supportedType()} 声明支持的渠道类型，
 * 由 WebhookDispatcher 按配置路由调用。
 */
public interface WebhookSender {

    /** 支持的渠道类型：FEISHU | DINGTALK | WECOM | CUSTOM */
    String supportedType();

    /**
     * 发送一条事件通知。
     *
     * @param config Webhook 配置（含 URL/签名密钥/模板）
     * @param ctx    事件上下文（scope/eventType/payload）
     * @throws RuntimeException 发送失败时抛出，由调用方决定重试
     */
    void send(WebhookConfigEntity config, WebhookEventContext ctx);
}
```

- [ ] **Step 2: AbstractWebhookSender.buildVariables 泛化**

`buildVariables(SlaBreachContext ctx)` 改为 `buildVariables(WebhookEventContext ctx)`，SLA 专属变量从 `payload.breaches` 提取，其他字段从上下文直接提取：

```java
    /** 从 WebhookEventContext 构造模板变量 Map（SLA 违规取第一条违规信息） */
    protected Map<String, String> buildVariables(WebhookEventContext ctx) {
        Map<String, String> vars = new HashMap<>();
        vars.put("sessionId",   ctx.getSessionId()   != null ? ctx.getSessionId()   : "");
        vars.put("visitorName", ctx.getVisitorName() != null ? ctx.getVisitorName() : "未知访客");
        vars.put("eventType",   ctx.getEventType()   != null ? ctx.getEventType()   : "");
        if (ctx.getPayload() != null) {
            ctx.getPayload().forEach((k, v) ->
                    vars.put(k, v == null ? "" : String.valueOf(v)));
        }
        if (ctx.getScope() == WebhookScope.SLA_BREACH && ctx.getPayload() != null) {
            Object breachesObj = ctx.getPayload().get("breaches");
            if (breachesObj instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof SlaBreachEntity breach) {
                BreachType type = breach.getBreachType();
                String label = type == null ? "" : switch (type) {
                    case WAIT   -> "排队等待超时";
                    case FRT    -> "首响超时";
                    case HANDLE -> "处理超时";
                };
                vars.put("breachType",      type != null ? type.getValue() : "");
                vars.put("breachTypeLabel", label);
                vars.put("targetSec",       breach.getTargetSec()  != null ? String.valueOf(breach.getTargetSec())  : "");
                vars.put("actualSec",       breach.getActualSec()  != null ? String.valueOf(breach.getActualSec())  : "");
                vars.put("breachAt",        breach.getBreachAt()   != null ? breach.getBreachAt().toString()       : "");
                vars.put("stage",           breach.getStage()      != null ? breach.getStage().getValue()          : "");
            }
        }
        return vars;
    }
```

新增 imports：`com.aria.conversation.domain.model.WebhookScope`、`com.aria.conversation.domain.model.BreachType`、`com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity`、`java.util.HashMap`。

- [ ] **Step 2b: 新建 WebhookDefaultTemplate（按 scope 的共享默认模板提供者，消除 3 个 Sender 模板重复）**

新增文件 `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/WebhookDefaultTemplate.java`：

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 按 scope 分类的默认消息模板提供者。
 *
 * <p>各 Sender（Feishu/Dingtalk/Wecom）在无自定义 messageTemplate 时调用
 * {@link #render(String, Map)}，避免 3 个 Sender 各自内联 scope 分支导致模板重复。
 * 占位符语法统一为 {@code ${var}}（与 AbstractWebhookSender.renderTemplate 一致）。
 */
public final class WebhookDefaultTemplate {

    private WebhookDefaultTemplate() {}

    /** 返回 scope 的默认纯文本（SLA 违规文本兼容既有格式；各 Sender 负责包装成平台 JSON）。 */
    public static String text(WebhookScope scope, Map<String, String> vars) {
        return switch (scope) {
            case SLA_BREACH -> "⚠️ SLA %s 违规\n会话：%s\n访客：%s\n策略：%s\n目标：%ss｜实际：%ss"
                    .formatted(vars.getOrDefault("breachTypeLabel", ""),
                            vars.getOrDefault("sessionId", ""),
                            vars.getOrDefault("visitorName", "未知访客"),
                            vars.getOrDefault("policyName", ""),
                            vars.getOrDefault("targetSec", ""),
                            vars.getOrDefault("actualSec", ""));
            case SESSION_CREATED -> "【新会话】访客 %s 进入会话 %s"
                    .formatted(vars.getOrDefault("visitorName", "未知访客"),
                            vars.getOrDefault("sessionId", ""));
            case SESSION_TRANSFERRED -> "【转人工】访客 %s 转接会话 %s"
                    .formatted(vars.getOrDefault("visitorName", "未知访客"),
                            vars.getOrDefault("sessionId", ""));
            case SESSION_CLOSED -> "【会话关闭】会话 %s 已结束"
                    .formatted(vars.getOrDefault("sessionId", ""));
            case CSAT_RATED -> "【客户评价】会话 %s 评分 %s 星，评价：%s"
                    .formatted(vars.getOrDefault("sessionId", ""),
                            vars.getOrDefault("score", ""),
                            vars.getOrDefault("comment", ""));
        };
    }
}
```

> 说明：`text()` 返回纯文本，各 Sender（Feishu text / Dingtalk·Wecom markdown）将其包装进平台 JSON 结构。模板变量由 `AbstractWebhookSender.buildVariables(ctx)` 统一提供；自定义模板走 `renderTemplate(messageTemplate, vars)` 原链路（占位符 `${var}`）。

- [ ] **Step 3: FeishuWebhookSender 适配**

`send(WebhookConfigEntity config, WebhookEventContext ctx)` 签名变更；默认模板统一走 `WebhookDefaultTemplate.text()`，不再内联 scope 分支：

```java
    @Override
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        String body = buildRequestBody(config, ctx);
        String url  = config.getUrl();

        Map<String, String> headers = Map.of();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = sign(timestamp, config.getSecret());
            body = injectSignature(body, timestamp, sign);
        }
        doPost(url, headers, body);
    }

    /** 构造请求体（供测试调用） */
    String buildRequestBody(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);

        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            return renderTemplate(config.getMessageTemplate(), vars);
        }
        // 默认模板：WebhookDefaultTemplate 按 scope 提供纯文本，包装为飞书 text 消息
        return """
                {
                  "msg_type": "text",
                  "content": {
                    "text": "%s"
                  }
                }
                """.formatted(WebhookDefaultTemplate.text(ctx.getScope(), vars)
                        .replace("\\", "\\\\").replace("\"", "\\\""));
    }
```

新增 import：`com.aria.conversation.domain.model.WebhookScope`（`WebhookDefaultTemplate` 同包无需 import）。

- [ ] **Step 4: DingtalkWebhookSender 适配**

`send` 签名改 `WebhookEventContext ctx`；默认模板统一走 `WebhookDefaultTemplate.text()`（markdown 格式）：

```java
    @Override
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String text = WebhookDefaultTemplate.text(ctx.getScope(), vars);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            body = renderTemplate(config.getMessageTemplate(), vars);
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "title": "%s",
                        "text": "### %s"
                      }
                    }
                    """.formatted(
                    ctx.getScope() == WebhookScope.SLA_BREACH ? "SLA违规告警" : ctx.getScope(),
                    text.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""));
        }
        // URL 签名逻辑不变（timestamp + sign 参数）
        String url = config.getUrl();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign = sign(timestamp, config.getSecret());
            url += (url.contains("?") ? "&" : "?")
                    + "timestamp=" + timestamp
                    + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        }
        doPost(url, Map.of(), body);
    }
```

新增 import：`com.aria.conversation.domain.model.WebhookScope`。

- [ ] **Step 5: WecomWebhookSender 适配**

`send` 签名改 `WebhookEventContext ctx`；默认模板统一走 `WebhookDefaultTemplate.text()`：

```java
    @Override
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            body = renderTemplate(config.getMessageTemplate(), vars);
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "content": "## %s"
                      }
                    }
                    """.formatted(WebhookDefaultTemplate.text(ctx.getScope(), vars)
                            .replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""));
        }
        doPost(config.getUrl(), Map.of(), body);
    }
```

新增 import：`com.aria.conversation.domain.model.WebhookScope`。

- [ ] **Step 6: CustomWebhookSender 适配**

读取 `CustomWebhookSender.java` 现有实现，仅将 `send(WebhookConfigEntity, SlaBreachContext)` 参数改为 `WebhookEventContext`，方法体不变（CUSTOM 类型构造请求体逻辑与 SLA 上下文无关，直接复用 `buildVariables(ctx)`）。若方法体引用了 `SlaBreachContext` 类型，同步替换。

- [ ] **Step 6b: WebhookTestSender 适配（测试消息构造）**

`WebhookTestSender.sendTest` 中 `SlaBreachContext mockCtx = new SlaBreachContext(...)` 改为构造 SLA_BREACH 事件上下文：

```java
        SlaBreachEntity mockBreach = SlaBreachEntity.builder()
                .sessionId("test-session").breachType(BreachType.WAIT).stage(BreachStage.BREACH)
                .targetSec(120).actualSec(185).build();
        WebhookEventContext mockCtx = WebhookEventContext.builder()
                .scope(WebhookScope.SLA_BREACH)
                .eventType("WAIT")
                .sessionId("test-session")
                .visitorName("测试访客")
                .payload(Map.of(
                        "policyName", "测试策略",
                        "breaches", List.of(mockBreach),
                        "breachIds", List.of(1L)))
                .build();
```

`sender.send(config, mockCtx)` 调用不变。imports：删除 `SlaBreachContext`，新增 `com.aria.conversation.domain.model.WebhookScope`（`WebhookEventContext` 同包无需 import）。

- [ ] **Step 7: WebhookDispatcher 改造（去 SLA 特判，通用回调）**

`dispatch` 签名从 `(List<Long> webhookIds, SlaBreachContext ctx, List<Long> breachIds)` 改为 `(WebhookConfigEntity webhook, WebhookEventContext ctx)`；发送成功后通过 `ctx.getOnSuccess()` 回调（由事件发布方注入，dispatcher 不感知 scope）：

```java
    /**
     * 异步分发单条 Webhook 通知（webhookExecutor 线程池）。
     *
     * <p>通用分发器：不感知任何业务 scope。发送成功后调用
     * {@link WebhookEventContext#getOnSuccess()} 回调（如 SLA 违规回写 notified），
     * 失败不调用且不抛异常。
     *
     * @param webhook 目标 Webhook 配置（已启用）
     * @param ctx     事件上下文
     */
    @Async("webhookExecutor")
    public void dispatch(WebhookConfigEntity webhook, WebhookEventContext ctx) {
        if (webhook == null) return;
        WebhookSender sender = senders.get(webhook.getType());
        if (sender == null) {
            log.warn("[Webhook] 未找到类型 {} 的 Sender，跳过 id={}", webhook.getType(), webhook.getId());
            return;
        }
        try {
            sendWithRetry(sender, webhook, ctx);
            log.info("[Webhook] 推送成功 id={} type={} scope={} session={}",
                     webhook.getId(), webhook.getType(), ctx.getScope(), ctx.getSessionId());
            if (ctx.getOnSuccess() != null) {
                ctx.getOnSuccess().run();
            }
        } catch (Exception e) {
            log.error("[Webhook] 推送失败 id={} type={} scope={} session={}",
                      webhook.getId(), webhook.getType(), ctx.getScope(), ctx.getSessionId(), e);
        }
    }
```

改动要点：
- **删除** `markNotifiedIfSlaBreach` 方法与 `SlaBreachMapper` 依赖（SLA 回写逻辑由 `SlaBreachNotifier` 通过 `onSuccess` 回调注入，Task 6 实现）。
- 删除字段 `webhookConfigMapper`、`slaBreachMapper`；`WebhookDispatcher` 构造器改为 `(List<WebhookSender> senderList, @Value(...) long retryBaseMs)`。
- `sendWithRetry(WebhookSender sender, WebhookConfigEntity config, SlaBreachContext ctx)` 参数改 `WebhookEventContext ctx`。
- 删除不再使用的 imports（`SlaBreachMapper`、`WebhookConfigMapper`、`OffsetDateTime`）。

- [ ] **Step 8: 更新 WebhookDispatcherTest（通用回调验证）**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock WebhookSender feishuSender;

    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(feishuSender.supportedType()).thenReturn("FEISHU");
        dispatcher = new WebhookDispatcher(List.of(feishuSender), 0L);
        clearInvocations(feishuSender);
    }

    @Test
    @DisplayName("推送成功后调用 onSuccess 回调（通用，不区分 scope）")
    void dispatch_success_invokesOnSuccess() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        AtomicInteger calls = new AtomicInteger();
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SLA_BREACH)
                .eventType("WAIT")
                .sessionId("sess-1")
                .onSuccess(calls::incrementAndGet)
                .build();

        dispatcher.dispatch(config, ctx);

        verify(feishuSender).send(eq(config), any(WebhookEventContext.class));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("推送失败不调用回调且不抛异常")
    void dispatch_failure_skipsCallback() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(2L).type("FEISHU").build();
        AtomicInteger calls = new AtomicInteger();
        doThrow(new RuntimeException("network down"))
                .when(feishuSender).send(eq(config), any(WebhookEventContext.class));
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SESSION_CLOSED)
                .sessionId("sess-2")
                .onSuccess(calls::incrementAndGet)
                .build();

        dispatcher.dispatch(config, ctx); // 不抛异常

        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("未知类型 sender 跳过")
    void dispatch_unknownType_skips() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(3L).type("WHAT").build();

        dispatcher.dispatch(config,
                WebhookEventContext.builder().scope(WebhookScope.SLA_BREACH).build());

        verify(feishuSender, never()).send(any(), any());
    }

    @Test
    @DisplayName("webhook 为 null 时安全返回")
    void dispatch_nullWebhook_safe() {
        dispatcher.dispatch(null, WebhookEventContext.builder().build());
        verify(feishuSender, never()).send(any(), any());
    }
}
```

> 若原 `WebhookDispatcherTest` 还有其他用例（如重试、未知类型跳过），按新签名同步改写。重试用例：`doThrow` 连续两次 + 第三次成功，验证 `send` 被调用 3 次（`retryBaseMs=0` 无 sleep）。

- [ ] **Step 9: 更新 FeishuWebhookSenderTest**

将测试中 `send(config, slaBreachContext)` 的调用改为构造 `WebhookEventContext`（SLA_BREACH scope + payload.breaches），断言 `buildRequestBody` 输出的默认文本包含 `SLA 排队等待超时 违规` 等原有关键字。若原测试直接 `new SlaBreachContext(...)`，改为 `WebhookEventContextFactory.buildSlaBreach(session, policy, breaches)` 或直接 builder 构造。

- [ ] **Step 10: 运行全部 webhook 测试**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest='WebhookDispatcherTest,FeishuWebhookSenderTest,WebhookEventContextTest,WebhookEventPublisherTest'`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 11: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/ ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/webhook/
git commit -m "refactor(conversation): Webhook 分发链路泛化为 WebhookEventContext"
```

---

### Task 6: SlaBreachNotifier 改造 + SlaBreachActions 移除 webhookIds

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/scheduler/SlaBreachNotifier.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/SlaBreachActions.java`
- Delete: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/SlaBreachContext.java`（若 Task 1 未删）
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/scheduler/SlaBreachNotifierTest.java`

**Interfaces:**
- Consumes: `WebhookEventPublisher.publish(WebhookScope, WebhookEventContext)`（Task 4）、`WebhookEventContextFactory.buildSlaBreach(...)`（Task 1）、`WebhookScope.SLA_BREACH`（Task 1）
- Produces: `SlaBreachNotifier` 不再依赖 `WebhookDispatcher`/`SlaBreachContext`；`SlaBreachActions` 无 `webhookIds` 字段

- [ ] **Step 1: 编写失败测试 SlaBreachNotifierTest**

```java
package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.webhook.WebhookEventContext;
import com.aria.conversation.infrastructure.webhook.WebhookEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaBreachNotifierTest {

    @Mock RabbitTemplate            eventsRabbitTemplate;
    @Mock ApplicationEventPublisher springEventPublisher;
    @Mock SlaBreachRecorder         recorder;
    @Mock WebhookEventPublisher     webhookEventPublisher;

    @Test
    @DisplayName("违规通知发布 SLA_BREACH 事件")
    void notifyBatch_publishesSlaBreach() {
        SlaBreachNotifier notifier = new SlaBreachNotifier(
                "cs.conversation.events", eventsRabbitTemplate,
                springEventPublisher, recorder, webhookEventPublisher);

        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-1");
        session.setVisitorName("张三");

        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("默认 SLA");
        SlaBreachActions actions = new SlaBreachActions();
        policy.setActions(actions);

        SlaBreachEntity breach = SlaBreachEntity.builder().id(10L).build();
        breach.setBreachType(BreachType.WAIT);
        breach.setStage(BreachStage.WARNING);

        notifier.notifyBatch(List.of(breach), policy, session);

        verify(webhookEventPublisher).publish(
                eq(WebhookScope.SLA_BREACH), any(WebhookEventContext.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest=SlaBreachNotifierTest`
Expected: 编译失败（`SlaBreachNotifier` 构造器参数与现有签名不一致）

- [ ] **Step 3: 改造 SlaBreachNotifier**

构造器：移除 `WebhookDispatcher webhookDispatcher` 参数，替换为 `WebhookEventPublisher webhookEventPublisher`；`notifyBatch` 末尾删除 webhookIds 分支，改为发布事件：

```java
    public SlaBreachNotifier(
            @Value("${conversation.events.exchange}") String eventsExchange,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate eventsRabbitTemplate,
            ApplicationEventPublisher springEventPublisher,
            SlaBreachRecorder recorder,
            WebhookEventPublisher webhookEventPublisher) {
        this.eventsExchange = eventsExchange;
        this.eventsRabbitTemplate = eventsRabbitTemplate;
        this.springEventPublisher = springEventPublisher;
        this.recorder = recorder;
        this.webhookEventPublisher = webhookEventPublisher;
    }
```

`notifyBatch` 中删除原 webhook 分支（`List<Long> webhookIds = actions.getWebhookIds(); ... webhookDispatcher.dispatch(...)` 整段），替换为发布事件 + 注入成功回调（SLA 回写 `webhook_notified_at`，通用 dispatcher 不感知 SLA）：

```java
        // 通用 Webhook 推送：按 scope（SLA_BREACH）自动匹配订阅配置，无匹配时零开销
        WebhookEventContext ctx = WebhookEventContextFactory.buildSlaBreach(session, policy, newBreaches);
        // 推送成功后才回写 webhook_notified_at（onSuccess 回调由通用 dispatcher 统一调用）
        ctx.setOnSuccess(() -> recorder.markWebhookNotified(
                newBreaches.stream().map(SlaBreachEntity::getId).toList(),
                OffsetDateTime.now()));
        webhookEventPublisher.publish(WebhookScope.SLA_BREACH, ctx);
```

删除 imports：`WebhookDispatcher`、`SlaBreachContext`（若不再使用）、`Map`（若不再使用）；新增 imports：`com.aria.conversation.domain.model.WebhookScope`、`com.aria.conversation.infrastructure.webhook.WebhookEventContext`、`com.aria.conversation.infrastructure.webhook.WebhookEventContextFactory`、`com.aria.conversation.infrastructure.webhook.WebhookEventPublisher`、`com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity`、`java.time.OffsetDateTime`（若未引入）。若 `Map` 仍用于 SSE 事件构造则保留。

- [ ] **Step 3b: SlaBreachRecorder 新增 markWebhookNotified 批量方法**

在 `SlaBreachRecorder` 中新增（委托给既有 `SlaBreachMapper.updateWebhookNotifiedAt`）：

```java
    /**
     * 批量标记 Webhook 已通知时间。
     * 由 SLA 违规推送成功回调调用（WebhookDispatcher 不感知 SLA 语义）。
     *
     * @param breachIds 违规记录 ID 列表
     * @param at        推送成功时间
     */
    public void markWebhookNotified(List<Long> breachIds, OffsetDateTime at) {
        if (breachIds == null || breachIds.isEmpty()) return;
        slaBreachMapper.updateWebhookNotifiedAt(breachIds, at);
    }
```

- [ ] **Step 4: 移除 SlaBreachActions.webhookIds 字段**

```java
    // 删除以下字段
    /** 违规时推送的 Webhook 配置 ID 列表，空列表表示不推送 */
    private List<Long> webhookIds;
```

同时删除不再使用的 `import java.util.List;`（若其他字段不使用 List）。类注释更新说明：通知按 Webhook 配置的事件范围自动匹配，不再策略级绑定。

- [ ] **Step 5: 删除 SlaBreachContext**

```bash
rm ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/SlaBreachContext.java
```

- [ ] **Step 6: 运行测试**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest='SlaBreachNotifierTest,WebhookDispatcherTest,FeishuWebhookSenderTest,WebhookEventPublisherTest,SlaBreachRecorderTest,SlaBreachEvaluatorTest'`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/scheduler/SlaBreachNotifier.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/SlaBreachActions.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/scheduler/SlaBreachNotifierTest.java
git add -u ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/webhook/SlaBreachContext.java
git commit -m "refactor(conversation): SLA 通知改为 scope 自动匹配，移除策略级 webhookIds"
```

---

### Task 7: WebhookController / WebhookAppService 支持 scopes

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/WebhookController.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/WebhookAppService.java`

**Interfaces:**
- Consumes: `WebhookConfigEntity.scopes`（Task 3）、`WebhookScope` 枚举（Task 1）
- Produces: `WebhookReq.scopes`（List<String>，可空）；创建/更新请求体与响应均含 `scopes`

- [ ] **Step 1: WebhookReq 加 scopes 与校验**

在 `WebhookController.WebhookReq` 中 `messageTemplate` 字段后新增：

```java
        /** 订阅的事件范围列表（WebhookScope 枚举名），空数组=不订阅；未传默认 ["SLA_BREACH"] */
        private List<String> scopes;
```

在 `buildEntity` 中合入 scopes（未传时保留 DB 默认；更新时传空则清空订阅）：

```java
        e.setScopes(req.getScopes());
```

补充 import：`java.util.List`（Controller 已 import `java.util.List`，无需新增）。

- [ ] **Step 2: 枚举合法性校验（Service 层）**

在 `WebhookAppService.createWebhook` / `updateWebhook` 方法入口加校验（复用私有方法）：

```java
    private void validateScopes(List<String> scopes) {
        if (scopes == null) {
            return; // 未传：DB 默认 ["SLA_BREACH"]
        }
        // 重复校验
        if (new HashSet<>(scopes).size() != scopes.size()) {
            throw new BusinessException(INVALID_PARAM, "webhook 范围存在重复值");
        }
        for (String s : scopes) {
            try {
                WebhookScope.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(INVALID_PARAM, "非法的 webhook 范围: " + s);
            }
        }
    }
```

常量补充：`private static final int INVALID_PARAM = 40000;`

补充 import：`java.util.HashSet`、`com.aria.conversation.domain.model.WebhookScope`。

`createWebhook` 开头调用 `validateScopes(entity.getScopes())`；`updateWebhook` 开头调用 `validateScopes(update.getScopes())`。

- [ ] **Step 3: 编译验证**

Run: `mvn -pl ai-conversation/conversation-service -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/WebhookController.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/WebhookAppService.java
git commit -m "feat(conversation): Webhook 管理接口支持 scopes 事件范围配置"
```

---

### Task 8: 会话生命周期事件发布点

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorSessionService.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueEnqueueOfflineTest.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceGetAgentIdTest.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorSessionServiceTest.java`

**Interfaces:**
- Consumes: `WebhookEventPublisher.publish(WebhookScope, WebhookEventContext)`（Task 4）
- Produces: 会话创建/转人工/关闭时发布 `SESSION_CREATED` / `SESSION_TRANSFERRED` / `SESSION_CLOSED` 事件

- [ ] **Step 1: VisitorSessionService 新建会话发布 SESSION_CREATED**

在 `VisitorSessionService` 中注入 publisher（字段 + 构造器参数）：

```java
    private final WebhookEventPublisher webhookEventPublisher;
```

在 `getOrCreate` 方法新建会话分支（`log.info("[VisitorSession] 新建会话 ...")` 之后、`return new InitSessionResult(...)` 之前）插入：

```java
            // 通用 Webhook：新会话事件（仅首次创建时发布，恢复旧会话不重复）
            webhookEventPublisher.publish(WebhookScope.SESSION_CREATED,
                    WebhookEventContextFactory.buildSessionEvent(
                            WebhookScope.SESSION_CREATED,
                            WebhookEventTypes.SESSION_CREATED,
                            sessionId, name,
                            Map.of("channel", "AI_CHAT")));
```

- [ ] **Step 2: SessionQueueService.enqueue 发布转人工事件**

在 `SessionQueueService` 中注入 publisher（字段 + 构造器参数 `WebhookEventPublisher webhookEventPublisher`，注意构造器已有 11 个参数，新增为第 12 个）。

`enqueue` 方法在 `publishSessionStart(...)` 调用之后插入：

```java
        // 通用 Webhook：用户请求转人工（幂等：仅真正入队时发布）
        webhookEventPublisher.publish(WebhookScope.SESSION_TRANSFERRED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_TRANSFERRED,
                        WebhookEventTypes.SESSION_ENQUEUE,
                        sessionId, userName,
                        Map.of(
                                "transferReason", transferReason == null ? "" : transferReason,
                                "tag", tag == null ? "" : tag)));
```

- [ ] **Step 3: SessionQueueService.transfer 发布座席转接事件**

`transfer` 方法在 `publishSessionTransfer(...)` 调用之后插入：

```java
        // 通用 Webhook：座席间转接
        webhookEventPublisher.publish(WebhookScope.SESSION_TRANSFERRED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_TRANSFERRED,
                        WebhookEventTypes.SESSION_TRANSFER,
                        sessionId, null,
                        Map.of(
                                "fromAgentId", fromAgentId == null ? "" : fromAgentId,
                                "toAgentId", targetAgentId == null ? "" : targetAgentId)));
```

- [ ] **Step 4: SessionQueueService.close 发布会话关闭事件**

`close` 方法在 `publishSessionEnd(sessionId, closedBy)` 调用之后插入：

```java
        // 通用 Webhook：会话关闭
        webhookEventPublisher.publish(WebhookScope.SESSION_CLOSED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_CLOSED,
                        WebhookEventTypes.SESSION_CLOSED,
                        sessionId, null,
                        Map.of("closedBy", closedBy != null ? closedBy.name() : "")));
```

- [ ] **Step 5: 同步更新直接 new SessionQueueService 的测试**

`SessionQueueEnqueueOfflineTest` 与 `SessionQueueServiceGetAgentIdTest` 中构造 `new SessionQueueService(...)` 调用处，新增 `WebhookEventPublisher` mock 参数（`@Mock WebhookEventPublisher webhookEventPublisher`，传入构造器）。

- [ ] **Step 6: 同步更新 VisitorSessionServiceTest**

若该测试直接 new `VisitorSessionService`，在构造器参数中补 `@Mock WebhookEventPublisher`。

- [ ] **Step 7: 编译与测试**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest='SessionQueueEnqueueOfflineTest,SessionQueueServiceGetAgentIdTest,VisitorSessionServiceTest'`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 8: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/VisitorSessionService.java ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueEnqueueOfflineTest.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/SessionQueueServiceGetAgentIdTest.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/VisitorSessionServiceTest.java
git commit -m "feat(conversation): 会话创建/转人工/关闭发布 Webhook 事件"
```

---

### Task 9: CSAT_RATED 事件发布点

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/CsatService.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/CsatServiceTest.java`
- Test: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/CsatServicePendingTest.java`

**Interfaces:**
- Consumes: `WebhookEventPublisher.publish(WebhookScope, WebhookEventContext)`（Task 4）
- Produces: 评分提交成功发布 `CSAT_RATED` 事件

- [ ] **Step 1: CsatService 注入 publisher 并发布事件**

`CsatService` 字段与构造器（`@RequiredArgsConstructor` 自动生成构造器，只需加字段）：

```java
    private final WebhookEventPublisher webhookEventPublisher;
```

`rate` 方法在 `mapper.updateById(rating)` 之后、`log.info` 之前插入：

```java
        // 通用 Webhook：客户评价（异步分发，不影响评分主流程）
        webhookEventPublisher.publish(WebhookScope.CSAT_RATED,
                WebhookEventContextFactory.buildCsatRated(
                        rating.getSessionId(), csatId, score, comment,
                        rating.getChannel() == null ? "" : rating.getChannel().name()));
```

- [ ] **Step 2: 更新 CsatServiceTest / CsatServicePendingTest**

两个测试类中构造 `new CsatService(mapper)` 处补 `@Mock WebhookEventPublisher webhookEventPublisher` 参数（`new CsatService(mapper, webhookEventPublisher)`）；`CsatServiceTest` 中 rate 相关用例可加断言：`verify(webhookEventPublisher).publish(eq(WebhookScope.CSAT_RATED), any(WebhookEventContext.class))`。

- [ ] **Step 3: 运行测试**

Run: `mvn -pl ai-conversation/conversation-service test -Dtest='CsatServiceTest,CsatServicePendingTest'`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/CsatService.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/CsatServiceTest.java ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/CsatServicePendingTest.java
git commit -m "feat(conversation): CSAT 评价提交发布 Webhook 事件"
```

---

### Task 10: 前端 API 类型 + webhook.vue 事件范围配置

**Files（aria-frontend 仓库）：**
- Modify: `apps/src/api/webhook/index.ts`
- Modify: `apps/src/views/system/sla/webhook.vue`

**Interfaces:**
- Consumes: 后端 `GET/POST/PUT /api/v1/admin/sla/webhooks` 的 `scopes` 字段（Task 7）
- Produces: `WebhookVO.scopes: string[]`；表单「事件范围」多选；表格「事件范围」列

- [ ] **Step 1: WebhookVO 增加 scopes**

```ts
export interface WebhookVO {
  id: number | string;
  name: string;
  type: 'CUSTOM' | 'DINGTALK' | 'FEISHU' | 'WECOM';
  url: string;
  secret?: string;
  customHeaders?: Record<string, string>;
  messageTemplate?: string;
  isEnabled: number; // 1=启用, 0=禁用
  scopes: string[]; // 订阅的事件范围（WebhookScope 枚举名）
}
```

- [ ] **Step 2: webhook.vue 表单增加范围多选**

在 `<script setup>` 中：

```ts
import { CheckboxGroup, Checkbox } from 'ant-design-vue';

// 事件范围选项（值=后端枚举名，label=展示名）
const scopeOptions = [
  { value: 'SLA_BREACH', label: 'SLA违规告警' },
  { value: 'SESSION_CREATED', label: '新会话' },
  { value: 'SESSION_TRANSFERRED', label: '转人工' },
  { value: 'SESSION_CLOSED', label: '会话关闭' },
  { value: 'CSAT_RATED', label: '客户评价' },
];

interface FormState {
  name: string;
  type: 'CUSTOM' | 'DINGTALK' | 'FEISHU' | 'WECOM';
  url: string;
  secret: string;
  customHeadersJson: string;
  messageTemplate: string;
  isEnabled: boolean;
  scopes: string[]; // 新增
}

const emptyForm = (): FormState => ({
  name: '',
  type: 'FEISHU',
  url: '',
  secret: '',
  customHeadersJson: '{}',
  messageTemplate: '',
  isEnabled: true,
  scopes: ['SLA_BREACH'], // 默认只订阅 SLA 违规，与后端默认一致
});
```

`openEdit` 中回显：`scopes: row.scopes && row.scopes.length > 0 ? row.scopes : ['SLA_BREACH']`。

`buildPayload` 中提交：`scopes: form.scopes`。

`submit` 校验中增加：若 `form.scopes.length === 0`，`message.warning('未选择任何事件范围，该 Webhook 不会收到任何推送（如需保存请继续）')` **仅提示、不阻断**（与后端语义一致：空数组 = 不订阅任何事件，允许保存）。

模板表单中（"消息模板" FormItem 之前）插入：

```vue
<FormItem label="事件范围" required>
  <CheckboxGroup v-model:value="form.scopes" style="width: 100%">
    <Checkbox
      v-for="opt in scopeOptions"
      :key="opt.value"
      :value="opt.value"
      style="display: block; margin-bottom: 4px"
    >
      {{ opt.label }}
    </Checkbox>
  </CheckboxGroup>
  <div style="color: rgba(0,0,0,0.45); font-size: 12px; margin-top: 4px">
    选择该 Webhook 订阅的事件，未选择任何事件将不会收到推送
  </div>
</FormItem>
```

> ant-design-vue 版本差异：若项目使用 `Checkbox` 的 `value` 属性不被支持（vben-admin 通常用较新版本），改用 `Select mode="multiple"` 等价实现；以 `apps/src/views/system/sla/webhook.vue` 现有依赖为准。

- [ ] **Step 3: 表格增加「事件范围」列**

```ts
const scopeLabelMap: Record<string, string> = {
  SLA_BREACH: 'SLA违规',
  SESSION_CREATED: '新会话',
  SESSION_TRANSFERRED: '转人工',
  SESSION_CLOSED: '会话关闭',
  CSAT_RATED: '客户评价',
};

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '类型', key: 'type', width: 100 },
  { title: 'URL', key: 'url', ellipsis: true },
  { title: '事件范围', key: 'scopes', width: 200 },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 220 },
];
```

表格 bodyCell 增加 scopes 渲染分支：

```vue
<template v-else-if="column.key === 'scopes'">
  <Space wrap>
    <Tag
      v-for="s in (record as WebhookVO).scopes ?? []"
      :key="s"
      color="processing"
    >
      {{ scopeLabelMap[s] ?? s }}
    </Tag>
    <span v-if="!(record as WebhookVO).scopes?.length" style="color: rgba(0,0,0,0.45)"
      >未订阅</span
    >
  </Space>
</template>
```

- [ ] **Step 4: 前端构建验证**

Run: `cd /Users/lycodeing/WebstormProjects/aria-frontend && pnpm build`（或 `pnpm --filter @vben/... build`，按项目脚本）
Expected: 构建成功，无 TS 类型错误

- [ ] **Step 5: Commit（aria-frontend 仓库）**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend
git add apps/src/api/webhook/index.ts apps/src/views/system/sla/webhook.vue
git commit -m "feat(admin): Webhook 配置支持事件范围（scope）"
```

---

### Task 11: 前端 SLA 策略页清理 + 收尾检查

**Files（aria-frontend 仓库）：**
- Modify（按需）: `apps/src/views/system/sla/index.vue`

- [ ] **Step 1: 检查策略页是否有 webhook 绑定项**

Run: `grep -n "webhook\|Webhook" apps/src/views/system/sla/index.vue`
Expected: 若无输出，则前端无策略级 webhook 绑定 UI，本任务仅检查。
若有输出（如 `actions.webhookIds` 表单项），移除该项，并替换为静态提示：`违规通知按 Webhook 配置的事件范围自动匹配`。

- [ ] **Step 2: 确认路由/菜单已挂载 webhook 页**

Run: `grep -rn "webhook" apps/src/router apps/src/views/system/sla 2>/dev/null | head`
Expected: `system/sla/webhook.vue` 已在路由/菜单注册（如缺失，在 `system/sla` 路由配置中补一条 `webhook` 子路由）。

- [ ] **Step 3: Commit（aria-frontend 仓库，如有改动）**

```bash
git add apps/src/views/system/sla/index.vue
git commit -m "chore(admin): SLA 策略页移除 webhook 绑定说明"
```

---

### Task 12: 后端构建 + 全量测试 + 端到端验证

**Files:**
- 验证性任务，不新增代码

- [ ] **Step 1: 后端全量编译 + 测试**

Run: `cd /Users/lycodeing/IdeaProjects/aria-server && mvn -pl ai-conversation/conversation-service -am clean test`
Expected: BUILD SUCCESS，conversation-service 全部单测通过（含新增 `WebhookEventContextTest` / `WebhookEventPublisherTest` / `SlaBreachNotifierTest`；`-am` 连带构建 common 模块，`hPutIfAbsent` 符号正常解析）

- [ ] **Step 2: 重建并部署 conversation-service 镜像**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service -am clean package -DskipTests
cp ai-conversation/conversation-service/target/conversation-service-1.0.0-SNAPSHOT.jar deploy/docker-data/jars/aria-conversation-1.0.0-SNAPSHOT.jar
cd deploy && docker compose -f docker-compose-local.yml up -d --force-recreate conversation-service-1 conversation-service-2
```

- [ ] **Step 3: 端到端验证 SLA_BREACH 推送**

1. 通过管理后台（或 curl）创建 CUSTOM webhook：`POST /api/v1/admin/sla/webhooks`，body 含 `"scopes":["SLA_BREACH"]`、`"url":"https://webhook.site/<token>"`。
2. 新建访客会话 → 转人工进入 WAITING → 等待超过 WAIT 目标秒数（策略默认 120s）。
3. 验证：
   - `GET /api/v1/admin/sla/breaches?sessionId={id}` 出现 `breachType=WAIT` 新记录；
   - `webhook.site` 收到 `【SLA违规】...` 推送（默认模板或自定义模板）；
   - `cs_sla_breach.webhook_notified_at` 被回写（非 NULL）。

- [ ] **Step 4: 端到端验证会话/CSAT 事件**

1. 创建订阅 `SESSION_TRANSFERRED` + `SESSION_CLOSED` + `CSAT_RATED` 的 webhook。
2. 依次触发：发起转人工、关闭会话、提交 CSAT 评价。
3. 验证 `webhook.site` 收到对应事件推送。

- [ ] **Step 5: 回归验证（不配置 webhook 时 SLA 不受影响）**

1. 删除全部 webhook 配置（或禁用）。
2. 制造 WAIT 违规，确认 `cs_sla_breach` 记录正常、`alerted_at` 置位、`webhook_notified_at` 保持 NULL，日志无异常堆栈。

- [ ] **Step 6: 回归接口自动化用例**

Run: `cd /Users/lycodeing/IdeaProjects/aria-server/api-tests && .venv/bin/pytest tests/conversation -m "not slow" -q`
Expected: conversation 相关用例通过；`CONV-WH-001~008`（webhook CRUD 不传 scopes）仍通过（默认兼容）。

- [ ] **Step 7: 更新测试文档（如适用）**

在 `docs/testing/api-test-cases/conversation-test-cases.md` 的 CONV-WH 段落补充：创建请求可携带 `scopes`；未传默认 `["SLA_BREACH"]`；非法 scope 返回 400。

- [ ] **Step 8: Commit（后端仓库）**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
git add docs/testing/api-test-cases/conversation-test-cases.md
git commit -m "docs: 补充 Webhook scopes 配置测试用例说明"
```

---
