# 访客会话创建统一化改造设计

## 1. 背景与目标

### 背景

chat-widget（访客端聊天组件）目前没有统一的会话创建入口。访客打开聊天窗口时，后端并不感知；会话记录依赖两条相互独立的隐式路径被动创建：

- **路径一**：访客发送第一条消息时，`FaqChatAppService` 调用 `initAiChatSession()`，在 DB 插入一条 `AI_CHAT` 状态的会话记录（失败静默跳过）。
- **路径二**：访客点击转人工后，`SessionQueueService.enqueue()` 发布 MQ 事件 `SESSION_START`，消费者 `ConversationMessageConsumer` 异步落库，逻辑为"insert，若冲突则尝试将 `AI_CHAT` 升级为 `WAITING`"。

这种设计导致：

1. 会话创建逻辑分散，字段初始化不一致（`tag`、`transferReason` 等两处各自硬编码）。
2. 访客无法携带稳定的唯一标识，`cs_conversation` 表没有 `visitor_id`，历史会话只能按 `visitor_name` 查询（非唯一字段，存在 bug）。
3. `sessionId` 由前端自由传入或后端随机生成，同一访客多次访问产生多个无关联的会话。
4. MQ 消费端需要承担"创建或升级"的双重职责，消费逻辑复杂，难以维护。

### 目标

1. **统一入口**：新增 `POST /api/v1/chat/session/init` 作为唯一的会话创建/恢复入口，由 chat-widget 在打开窗口时调用。
2. **访客唯一标识**：前端在 `localStorage` 生成持久 UUID（`anonymousId`），通过 `X-Anonymous-Id` Header 传入；后端将其存储为 `visitor_id`，作为跨会话关联访客的唯一标识。
3. **补充设备信息**：`cs_conversation` 表新增 `visitor_ip`（客户端 IP）和 `visitor_device`（User-Agent），用于问题排查和统计。
4. **清理隐式路径**：移除 `FaqChatAppService` 中的 `initAiChatSession` 调用；简化 MQ 消费端 `SESSION_START` 的处理逻辑，从"insert-or-upgrade"改为纯 `UPDATE`。

### 非目标

- 不引入 `cs_visitor` 独立访客表（可作为后续演进）。
- 不改造已有的手机号认证流程（`VisitorAuthService`）。
- 不修改座席端（`SessionQueueController`）相关接口。

## 2. 现状分析与问题

### 2.1 现有会话创建路径

#### 路径一：首条消息触发（`initAiChatSession`）

```
前端发消息
  → ChatController.streamChat()
  → FaqChatAppService.stream()
  → SessionQueueService.initAiChatSession(sessionId)
  → ConversationPersistRepository.initAiChatSession(sessionId, now)
      → INSERT cs_conversation(session_id, visitor_name='访客', tag='AI 对话',
                               transfer_reason='', status='AI_CHAT', ...)
      → DuplicateKeyException → 静默忽略
```

- 每次收到消息都调用一次，依赖 DB 唯一键冲突做幂等。
- `visitorName` 硬编码为 `"访客"`，`tag` 硬编码为 `"AI 对话"`。
- `sessionId` 来源：前端传入（校验正则 `^[a-zA-Z0-9_\-]{1,64}$`）或后端随机生成（`guest-{UUID}`）。

#### 路径二：转人工触发（MQ `SESSION_START`）

```
前端点击转人工
  → ChatController.transfer()
  → FaqChatAppService.requestTransfer()
  → SessionQueueService.enqueue(sessionId, userName, transferReason, tag)
      → Redis: queueRepository.save(SessionQueueItem)
      → MQ(Direct): publishSessionStart → ConversationStreamEvent{type=SESSION_START}
          → ConversationMessageConsumer.handleSessionStart()
          → ConversationPersistRepository.startConversation(sessionId, ...)
              → INSERT ... status='WAITING'
              → DuplicateKeyException
                  → UPDATE status='WAITING' WHERE session_id=? AND status='AI_CHAT'
                  → 返回 0（已是 WAITING/ACTIVE）→ 静默忽略
```

- MQ 消费端承担"创建或升级"双重职责。
- `startConversation` 的 `tag` 默认值为 `"咨询"`，与路径一的 `"AI 对话"` 不一致。

### 2.2 核心问题清单

| # | 问题 | 影响 |
|---|------|------|
| 1 | 无访客唯一标识 | 同一访客多次访问无法关联历史会话 |
| 2 | `visitorName` 非唯一，历史查询按名字查 | `VisitorHistoryService` 存在 bug：同名访客数据混淆 |
| 3 | 会话创建分散在两条路径 | 字段初始化不一致，状态机靠 DB 异常驱动，难以理解和维护 |
| 4 | `sessionId` 前端可自由生成 | 前端不传时后端随机生成，同一访客每次对话是孤立会话 |
| 5 | MQ 消费端"insert-or-upgrade"逻辑复杂 | SESSION_START 消费失败重试可能造成状态错误 |
| 6 | `cs_conversation` 缺少 IP 和设备字段 | 无法追踪访客来源，问题排查困难 |

### 2.3 相关文件位置

| 文件 | 职责 |
|------|------|
| `interfaces/rest/ChatController.java` | chat-widget 入口，`resolveSessionId` 生成/校验 sessionId |
| `application/service/FaqChatAppService.java` | AI 对话逻辑，调用 `initAiChatSession`（待移除） |
| `application/service/SessionQueueService.java` | 转人工入队，发布 MQ 事件 |
| `infrastructure/mq/ConversationMessageConsumer.java` | MQ 消费端，处理 `SESSION_START` |
| `infrastructure/persistence/ConversationPersistRepository.java` | DB 操作，`initAiChatSession` 和 `startConversation` |
| `infrastructure/persistence/entity/ConversationEntity.java` | DB 实体映射 |

## 3. 设计决策

### 3.1 访客唯一标识：前端 localStorage 生成 UUID

**决策**：chat-widget 首次加载时，在 `localStorage` 中生成一个 UUID v4 作为 `anonymousId`，后续每次请求通过 `X-Anonymous-Id` Header 传入，后端存储为 `visitor_id`。

**对比方案：**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| 纯匿名指纹（IP + UA 哈希） | 零前端改动 | IP 切换（WiFi/4G）或 UA 变更即失效，识别率低 | 不采用 |
| 前端 localStorage UUID（本方案） | 准确率高，跨 session 关联；前端实现简单 | 换设备/无痕窗口无法关联 | **采用** |
| 集成手机号认证 | 跨设备关联，准确率最高 | 强制验证码，摩擦大；非本次改造范围 | 作为后续演进 |

**前端实现约定：**

```javascript
// chat-widget 初始化时
function getOrCreateAnonymousId() {
  const key = 'aria_visitor_id';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID(); // 标准 UUID v4
    localStorage.setItem(key, id);
  }
  return id;
}
// 每次请求带上 Header
headers['X-Anonymous-Id'] = getOrCreateAnonymousId();
```

**后端校验规则**：`^[a-zA-Z0-9_\-]{8,64}$`，不满足返回 400。

---

### 3.2 会话创建时机：打开窗口时

**决策**：chat-widget 展开（或首次可见）时，立即调用 `POST /api/v1/chat/session/init`。

**理由：**
- 会话状态早于第一条消息存在，支持排队位次通知、欢迎语等场景。
- 转人工时会话一定已存在，MQ 消费端只需做 `UPDATE`，无需"insert-or-upgrade"。
- 刷新恢复场景统一走此接口，逻辑清晰。

---

### 3.3 已有会话恢复策略

**决策**：同一 `anonymousId` 打开窗口时，若存在 `status IN (AI_CHAT, WAITING, ACTIVE)` 的会话则恢复，否则新建。

- 已关闭（`CLOSED`）的会话不参与恢复，作为历史记录保留。
- 返回字段 `isNew` 告知前端是恢复还是新建，前端据此决定是否发欢迎语。

---

### 3.4 并发安全：Redis 分布式锁

**决策**：`getOrCreate` 操作使用 Redis 分布式锁（key = `visitor:init:{anonymousId}`，TTL 3s），防止同一访客并发打开多个窗口时重复创建会话。

**理由**：`visitor_id` 允许多条历史会话记录（每次关闭后重新开），不能用 DB 唯一索引表达"同时只能有一条非 CLOSED 记录"这个约束，必须用应用层锁。

---

### 3.5 MQ `SESSION_START` 处理简化

**决策**：`ConversationMessageConsumer` 处理 `SESSION_START` 时，从"insert-or-upgrade"改为纯 `UPDATE`。

**理由**：新设计下，转人工时会话一定已存在（init 接口已创建），`SESSION_START` 只需将 `AI_CHAT → WAITING`，insert 分支永远不会走到，保留只会增加复杂度和潜在风险。

返回 0 行（会话不存在或状态已超过 WAITING）时记录 `warn` 日志但不抛异常，避免 MQ 重试造成状态错乱。

## 4. 数据库变更

### 4.1 `cs_conversation` 表新增字段

```sql
ALTER TABLE cs_conversation.cs_conversation
    ADD COLUMN visitor_id     VARCHAR(64)  DEFAULT NULL,
    ADD COLUMN visitor_ip     VARCHAR(45)  DEFAULT NULL,
    ADD COLUMN visitor_device VARCHAR(500) DEFAULT NULL;

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_id
    IS '访客唯一标识，前端 localStorage 生成的 anonymousId';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_ip
    IS '访客 IP，取 X-Forwarded-For 首个地址或直连 RemoteAddr，支持 IPv4/IPv6';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_device
    IS '访客设备信息，原始 User-Agent 字符串';
```

三列均允许 `NULL`：
- 历史数据不受影响。
- `visitor_ip` 和 `visitor_device` 在网关剥离 Header 等异常情况下允许为空。

### 4.2 新增索引

```sql
-- 按 visitor_id 查询活跃会话（getOrCreate 的核心查询路径）
CREATE INDEX idx_cs_conv_visitor_id
    ON cs_conversation.cs_conversation (visitor_id, status)
    WHERE visitor_id IS NOT NULL;
```

- 部分索引（`WHERE visitor_id IS NOT NULL`）跳过所有历史数据，索引体积小，构建快。
- 覆盖 `(visitor_id, status)` 两列，支持 `WHERE visitor_id = ? AND status != 'CLOSED'` 的过滤，无需回表即可判断是否存在活跃会话。

### 4.3 字段设计说明

| 字段 | 类型 | 长度选择理由 |
|------|------|-------------|
| `visitor_id` | VARCHAR(64) | UUID v4 为 36 字符（带连字符），64 留有余量 |
| `visitor_ip` | VARCHAR(45) | IPv6 最长 39 字符（含冒号），45 覆盖所有格式 |
| `visitor_device` | VARCHAR(500) | 常见 User-Agent 在 100-300 字符，500 足够覆盖极端情况 |

### 4.4 `ConversationEntity` 对应字段

```java
// ConversationEntity.java 新增三个字段
@TableField("visitor_id")
private String visitorId;

@TableField("visitor_ip")
private String visitorIp;

@TableField("visitor_device")
private String visitorDevice;
```

### 4.5 SQL 文件更新

同步更新 `docs/sql/conversation-service-schema.sql` 中 `cs_conversation` 表的 DDL，保持文档与实际库结构一致。

## 5. API 契约

### 5.1 新增接口：`POST /api/v1/chat/session/init`

**用途**：chat-widget 打开时调用，有活跃会话就恢复，没有就新建。这是唯一的会话创建入口。

**请求**

```
POST /api/v1/chat/session/init
Content-Type: application/json
X-Anonymous-Id: f47ac10b-58cc-4372-a567-0e02b2c3d479   （必传，前端 localStorage UUID）
User-Agent: Mozilla/5.0 ...                              （后端自动采集，存 visitor_device）
X-Forwarded-For: 1.2.3.4                                （后端自动采集，存 visitor_ip）
```

请求体（可选）：

```json
{
  "visitorName": "张三"
}
```

- `visitorName`：可选，默认 `"访客"`。用于会话列表展示，不作为唯一标识。

**响应**

```json
{
  "code": 0,
  "data": {
    "sessionId": "guest-c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8",
    "status": "AI_CHAT",
    "isNew": true
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话 ID，后续所有请求均需携带 |
| `status` | String | 当前会话状态：`AI_CHAT` / `WAITING` / `ACTIVE` |
| `isNew` | Boolean | `true`=新建会话，`false`=恢复已有会话 |

前端根据 `status` 决定初始 UI：
- `AI_CHAT`：展示 AI 对话界面
- `WAITING`：展示排队等待界面（显示位次）
- `ACTIVE`：展示人工对话界面（直接恢复与座席的对话）

**错误响应**

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| 400 | 40001 | `X-Anonymous-Id` 缺失或格式非法（不符合 `^[a-zA-Z0-9_\-]{8,64}$`） |
| 503 | 50301 | Redis 锁获取超时（并发冲突，前端可重试） |

**访问控制**：`@CrossOrigin(origins = "*")`，chat-widget 嵌入第三方页面需要跨域。

---

### 5.2 现有接口调整

#### `POST /api/v1/chat/stream`

| 项 | 改造前 | 改造后 |
|----|--------|--------|
| `sessionId` | 可选，不传则后端随机生成 `guest-{UUID}` | **必传**，不传或格式非法返回 `error` SSE 事件后 `[DONE]` |
| 会话创建 | 首条消息触发 `initAiChatSession` | 不再创建，会话必须已存在 |

调整 `ChatController.resolveSessionId`：

```java
// 改造后：sessionId 为必传，null 或格式非法均返回 null（调用方返回 400/error）
private String resolveSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
        return null;
    }
    return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
}
```

#### `GET /api/v1/chat/state`

保留不变。短期内与 `init` 接口并存，前端逐步迁移后可废弃（不在本次范围内）。

#### `POST /api/v1/chat/transfer`

不变。`sessionId` 已是必传 `@NotBlank`，会话在 init 时已创建。

---

### 5.3 新增 Controller 类

新建 `ChatSessionController`，与 `ChatController` 并列，职责单一：

```
ChatSessionController @ /api/v1/chat/session
  └── POST /init  →  VisitorSessionService.getOrCreate(anonymousId, visitorName, ip, device)
```

不在 `ChatController` 中追加接口，避免该类继续膨胀。

## 6. 服务层改造

### 6.1 新增 `VisitorSessionService`

新建 `application/service/VisitorSessionService.java`，承载 `getOrCreate` 核心逻辑。

**返回值定义：**

```java
public record InitSessionResult(
    String sessionId,
    SessionStatus status,
    boolean isNew
) {}
```

**核心流程：**

```java
public InitSessionResult getOrCreate(String anonymousId,
                                     String visitorName,
                                     String visitorIp,
                                     String visitorDevice) {
    // 1. 参数校验
    validateAnonymousId(anonymousId); // 正则 ^[a-zA-Z0-9_\-]{8,64}$

    // 2. 获取 Redis 分布式锁，防并发重复创建
    String lockKey = "visitor:init:" + anonymousId;
    RLock lock = redissonClient.getLock(lockKey);
    lock.lock(3, TimeUnit.SECONDS);
    try {
        // 3. 查询活跃会话（非 CLOSED）
        Optional<ConversationEntity> active =
            persistRepository.findActiveByVisitorId(anonymousId);

        if (active.isPresent()) {
            ConversationEntity e = active.get();
            return new InitSessionResult(e.getSessionId(), e.getStatus(), false);
        }

        // 4. 新建会话
        String sessionId = GUEST_SESSION_PREFIX + UUID.randomUUID()
                               .toString().replace("-", "");
        String name = (visitorName != null && !visitorName.isBlank())
                       ? visitorName : "访客";
        persistRepository.createAiChatSession(
            sessionId, anonymousId, name, visitorIp, visitorDevice, OffsetDateTime.now()
        );
        log.info("[VisitorSession] 新建会话 anonymousId={} sessionId={}", anonymousId, sessionId);
        return new InitSessionResult(sessionId, SessionStatus.AI_CHAT, true);

    } finally {
        lock.unlock();
    }
}
```

**依赖：**
- `ConversationPersistRepository`（查询 + 创建）
- `RedissonClient`（分布式锁，项目已引入）

---

### 6.2 `ConversationPersistRepository` 新增/调整方法

#### 新增：`findActiveByVisitorId`

```java
/**
 * 按 visitor_id 查询最近一条非 CLOSED 会话。
 * 利用 idx_cs_conv_visitor_id 索引，避免全表扫描。
 */
public Optional<ConversationEntity> findActiveByVisitorId(String visitorId) {
    return Optional.ofNullable(
        conversationMapper.selectOne(
            Wrappers.lambdaQuery(ConversationEntity.class)
                .eq(ConversationEntity::getVisitorId, visitorId)
                .ne(ConversationEntity::getStatus, SessionStatus.CLOSED.getValue())
                .orderByDesc(ConversationEntity::getStartedAt)
                .last("LIMIT 1")
        )
    );
}
```

#### 新增：`createAiChatSession`

取代原有的 `initAiChatSession`，新增 `visitorId`、`visitorIp`、`visitorDevice` 三个参数。

```java
/**
 * 创建 AI_CHAT 状态的新会话。
 * 调用方已通过分布式锁保证不重复，此处不做 DuplicateKeyException 兜底。
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
```

#### 调整：`startConversation` → `upgradeToWaiting`

原方法（insert-or-upgrade）改为纯 UPDATE：

```java
/**
 * 转人工：将 AI_CHAT 状态的会话升级为 WAITING。
 * 新设计下会话一定已存在，无需 insert 兜底。
 * 返回影响行数：0 表示会话不存在或已超过 WAITING 状态（记 warn 日志）。
 */
public int upgradeToWaiting(String sessionId, String visitorName,
                             String transferReason, String tag,
                             OffsetDateTime now) {
    int rows = conversationMapper.update(
        Wrappers.lambdaUpdate(ConversationEntity.class)
            .set(ConversationEntity::getStatus, SessionStatus.WAITING.getValue())
            .set(ConversationEntity::getVisitorName, visitorName)
            .set(ConversationEntity::getTransferReason, transferReason)
            .set(ConversationEntity::getTag, tag != null && !tag.isBlank() ? tag : "咨询")
            .set(ConversationEntity::getStartedAt, now)
            .eq(ConversationEntity::getSessionId, sessionId)
            .eq(ConversationEntity::getStatus, SessionStatus.AI_CHAT.getValue())
    );
    if (rows == 0) {
        log.warn("[Persist] upgradeToWaiting 影响 0 行，sessionId={} 可能不存在或已超过 WAITING 状态",
                 sessionId);
    }
    return rows;
}
```

---

### 6.3 `ConversationMessageConsumer` 调整

`handleSessionStart` 改为调用新方法：

```java
// 改造前
private void handleSessionStart(Map<String, Object> payload, String sessionId) {
    persistRepository.startConversation(
        sessionId,
        str(payload, FIELD_VISITOR_NAME),
        str(payload, FIELD_TRANSFER_REASON),
        str(payload, FIELD_TAG),
        toOffsetDateTime(longVal(payload, FIELD_TIMESTAMP))
    );
}

// 改造后
private void handleSessionStart(Map<String, Object> payload, String sessionId) {
    persistRepository.upgradeToWaiting(
        sessionId,
        str(payload, FIELD_VISITOR_NAME),
        str(payload, FIELD_TRANSFER_REASON),
        str(payload, FIELD_TAG),
        toOffsetDateTime(longVal(payload, FIELD_TIMESTAMP))
    );
}
```

---

### 6.4 `VisitorHistoryService` 修复

```java
// 改造前（按 visitorName 查，非唯一字段，存在 bug）
List<ConversationEntity> history = conversationMapper.selectByVisitorName(visitorName);

// 改造后（按 visitor_id 查，通过请求 Header X-Anonymous-Id 读取）
List<ConversationEntity> history = conversationMapper.selectByVisitorId(visitorId);
```

`visitorId` 从 Controller 层读取 `X-Anonymous-Id` Header 向下传递。`ConversationMapper` 新增对应查询方法。

## 7. 移除隐式创建路径

### 7.1 移除 `FaqChatAppService` 中的 `initAiChatSession` 调用

**改造前：**

```java
// FaqChatAppService.stream() 方法内
sessionQueueService.initAiChatSession(sessionId); // 每次收到消息都调用
```

**改造后：**

删除该调用。消息到达时若 sessionId 对应的会话不存在，抛出业务异常 `SESSION_NOT_FOUND`，由 Controller 返回错误 SSE 事件：

```java
// FaqChatAppService.stream() 方法内（替换原 initAiChatSession 调用）
if (!persistRepository.existsBySessionId(sessionId)) {
    return Flux.just(ChatEvent.error("会话不存在，请刷新页面重试", objectMapper));
}
```

新增 `ConversationPersistRepository.existsBySessionId()`：

```java
public boolean existsBySessionId(String sessionId) {
    return conversationMapper.exists(
        Wrappers.lambdaQuery(ConversationEntity.class)
            .eq(ConversationEntity::getSessionId, sessionId)
            .ne(ConversationEntity::getStatus, SessionStatus.CLOSED.getValue())
    );
}
```

---

### 7.2 删除 `initAiChatSession` 相关代码

| 类 | 操作 |
|----|------|
| `SessionQueueService.initAiChatSession()` | 整个方法删除（行 196-198） |
| `ConversationPersistRepository.initAiChatSession()` | 整个方法删除（行 128-145） |

---

### 7.3 `ChatController.resolveSessionId` 去掉自动生成

```java
// 改造前：sessionId == null 时生成 guest-UUID
private String resolveSessionId(String sessionId) {
    if (sessionId == null) {
        return GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
    return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
}

// 改造后：sessionId 为必传，null 或不合规均返回 null（调用方返回 error 事件）
private String resolveSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
        return null;
    }
    return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
}
```

`GUEST_SESSION_PREFIX` 常量和 UUID 生成逻辑移至 `VisitorSessionService`（新建会话时使用）。

---

### 7.4 `startConversation` 方法删除

`ConversationPersistRepository.startConversation()` 原有的 insert-or-upgrade 方法在 7.3 已被 `upgradeToWaiting` 取代，整个方法可删除。

---

### 7.5 变更清单汇总

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `FaqChatAppService.java` | 修改 | 删除 `initAiChatSession` 调用，添加 `existsBySessionId` 校验 |
| `SessionQueueService.java` | 修改 | 删除 `initAiChatSession` 方法 |
| `ConversationPersistRepository.java` | 修改 | 删除 `initAiChatSession`、`startConversation`；新增 `findActiveByVisitorId`、`createAiChatSession`、`upgradeToWaiting`、`existsBySessionId` |
| `ConversationMessageConsumer.java` | 修改 | `handleSessionStart` 调用 `upgradeToWaiting` 替换 `startConversation` |
| `ChatController.java` | 修改 | `resolveSessionId` 去掉自动生成逻辑 |
| `ConversationEntity.java` | 修改 | 新增 `visitorId`、`visitorIp`、`visitorDevice` 字段 |
| `VisitorSessionService.java` | **新增** | 核心 `getOrCreate` 逻辑 |
| `ChatSessionController.java` | **新增** | `POST /api/v1/chat/session/init` 入口 |
| `VisitorHistoryService.java` | 修改 | 按 `visitor_id` 查历史，替换 `visitorName` |
| `ConversationMapper.java` | 修改 | 新增 `selectByVisitorId` 查询方法 |
| `docs/sql/conversation-service-schema.sql` | 修改 | 同步新增三列 DDL |

## 8. 完整调用链对比

### 8.1 改造前

```
┌─────────────────────────────────────────────────────────────────────┐
│ 访客打开窗口                                                          │
│   → 无任何后端调用，会话尚未存在                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 访客发送第一条消息                                                    │
│   → POST /api/v1/chat/stream  （sessionId 可不传，后端随机生成）      │
│   → FaqChatAppService.stream()                                       │
│       → SessionQueueService.initAiChatSession(sessionId)  ← 隐式创建 │
│           → ConversationPersistRepository.initAiChatSession()        │
│               → INSERT status='AI_CHAT'                              │
│               → DuplicateKeyException → 静默忽略                     │
│       → AI 推理流 ...                                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 访客点击转人工                                                        │
│   → POST /api/v1/chat/transfer                                       │
│   → SessionQueueService.enqueue()                                    │
│       → Redis: save SessionQueueItem                                 │
│       → MQ(Direct): SESSION_START 事件                               │
│           → ConversationMessageConsumer.handleSessionStart()         │
│           → ConversationPersistRepository.startConversation()        │
│               → INSERT status='WAITING'                              │
│               → DuplicateKeyException                                │
│                   → UPDATE AI_CHAT → WAITING  ← 隐式升级             │
│                   → 返回 0 → 静默忽略                                 │
└─────────────────────────────────────────────────────────────────────┘
```

**问题**：两条路径独立，字段初始化不一致，状态转换靠异常驱动，无访客唯一标识。

---

### 8.2 改造后

```
┌─────────────────────────────────────────────────────────────────────┐
│ 访客打开聊天窗口                                                      │
│   → POST /api/v1/chat/session/init                                   │
│       Header: X-Anonymous-Id: {localStorage UUID}                    │
│   → ChatSessionController                                            │
│   → VisitorSessionService.getOrCreate(anonymousId, ...)              │
│       → Redis 分布式锁 visitor:init:{anonymousId}                    │
│       → DB: SELECT WHERE visitor_id=? AND status != 'CLOSED'         │
│         ├── 有活跃会话 → 返回 {sessionId, status, isNew=false}       │
│         └── 无活跃会话                                               │
│               → INSERT status='AI_CHAT'（含 visitor_id/ip/device）   │
│               → 返回 {sessionId, status=AI_CHAT, isNew=true}         │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 访客发送消息（sessionId 已由 init 接口返回，前端必传）                │
│   → POST /api/v1/chat/stream  （sessionId 必传）                     │
│   → FaqChatAppService.stream()                                       │
│       → existsBySessionId(sessionId) 校验（会话必须存在）            │
│       → AI 推理流 ...                                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 访客点击转人工                                                        │
│   → POST /api/v1/chat/transfer                                       │
│   → SessionQueueService.enqueue()                                    │
│       → Redis: save SessionQueueItem                                 │
│       → MQ(Direct): SESSION_START 事件                               │
│           → ConversationMessageConsumer.handleSessionStart()         │
│           → ConversationPersistRepository.upgradeToWaiting()         │
│               → UPDATE AI_CHAT → WAITING  （纯 UPDATE，无 insert）   │
│               → rows=0 → warn 日志（不抛异常，不重试）               │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 8.3 状态机变化

```
改造前（两条路径分别维护状态）：

  initAiChatSession → [AI_CHAT]
                           │
  startConversation →  [WAITING]  （insert-or-upgrade）
                           │
  activateConversation → [ACTIVE]
                           │
  closeConversation →  [CLOSED]


改造后（统一入口，单一职责）：

  createAiChatSession → [AI_CHAT]   ← VisitorSessionService（init 接口）
                            │
  upgradeToWaiting →   [WAITING]    ← MQ SESSION_START（纯 UPDATE）
                            │
  activateConversation → [ACTIVE]   ← MQ SESSION_ACCEPT（不变）
                            │
  closeConversation →  [CLOSED]     ← MQ SESSION_END（不变）
```

每个状态转换有且只有一个代码路径，职责清晰，不再依赖异常驱动。

## 9. 迁移策略与兼容性

### 9.1 数据库迁移

新增列均为 `DEFAULT NULL`，存量数据无需回填，`ALTER TABLE` 在 PostgreSQL 上为元数据操作，不锁表，可在业务低峰期执行。

```sql
-- 执行顺序
-- 1. 加列（无锁，毫秒级）
ALTER TABLE cs_conversation.cs_conversation
    ADD COLUMN visitor_id     VARCHAR(64)  DEFAULT NULL,
    ADD COLUMN visitor_ip     VARCHAR(45)  DEFAULT NULL,
    ADD COLUMN visitor_device VARCHAR(500) DEFAULT NULL;

-- 2. 建索引（CONCURRENTLY，不阻塞读写）
CREATE INDEX CONCURRENTLY idx_cs_conv_visitor_id
    ON cs_conversation.cs_conversation (visitor_id, status)
    WHERE visitor_id IS NOT NULL;
```

### 9.2 存量会话兼容

- 存量会话的 `visitor_id` 为 `NULL`，`findActiveByVisitorId` 查询不会命中，不影响历史数据。
- 存量会话不影响新访客的 `getOrCreate` 逻辑。
- `VisitorHistoryService` 改为按 `visitor_id` 查询后，存量会话（`visitor_id = NULL`）不会出现在历史列表中——这是预期行为，因为存量会话无法关联到任何 `anonymousId`。

### 9.3 前后端协同上线顺序

**推荐滚动上线策略：**

```
Step 1：后端先上线（兼容旧前端）
  - 新增 POST /session/init 接口（旧前端不调用，无影响）
  - POST /stream 暂时保留 sessionId 可选（兼容旧前端继续使用随机生成）
  - 新增三列，MQ 消费端改为 upgradeToWaiting

Step 2：前端上线（chat-widget 更新）
  - 打开窗口时调用 /session/init
  - 后续请求携带 sessionId
  - 不再依赖后端随机生成 sessionId

Step 3：清理（前端全量上线后，观察期结束）
  - 移除 ChatController.resolveSessionId 的自动生成逻辑（sessionId 改为必传）
  - 移除 FaqChatAppService 中的 existsBySessionId 兜底（可选，保留也无害）
```

### 9.4 `X-Anonymous-Id` 缺失的降级处理

Step 1 期间，旧版前端不传 `X-Anonymous-Id`，`/session/init` 接口尚未被调用。此时新旧两套路径并存：

- 旧前端：走原有 `/stream` 自动生成 sessionId 路径（Step 1 暂保留）
- 新前端：走 `/session/init` 统一入口

Step 3 完成后旧路径全部关闭。

### 9.5 回滚方案

| 回滚场景 | 操作 |
|----------|------|
| `upgradeToWaiting` 出现大量 warn（rows=0） | 检查前端是否正常调用 init 接口；临时在消费端加 insert 兜底，等前端修复后移除 |
| Redis 锁获取超时（503） | 检查 Redis 连接；降级为不加锁（接受极低概率的重复会话，用 DB 唯一约束兜底） |
| 新列导致 SQL 问题 | 三列均可 NULL，直接 DROP COLUMN 回滚，不影响存量数据 |

### 9.6 监控建议

| 指标 | 告警条件 | 说明 |
|------|----------|------|
| `upgradeToWaiting` rows=0 warn 日志 | 5 分钟内 > 10 次 | 说明有转人工但会话不存在，前端可能没调 init |
| Redis 锁等待超时 | 任意出现 | init 接口并发异常，需排查 Redis |
| `visitor_id` 为 NULL 的新会话占比 | > 5%（上线后 1 小时） | 前端未正确传入 `X-Anonymous-Id` |
| init 接口 `isNew=false` 占比 | 参考值，无告警 | 衡量"会话恢复"场景的实际使用率 |
