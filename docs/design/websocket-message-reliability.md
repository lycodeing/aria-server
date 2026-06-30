# WebSocket 消息可靠性设计

> **状态**：设计中 / 待评审
> **优先级**：P0（下一迭代）
> **作者**：架构组
> **关联代码**：`ChatWebSocketHandler` / `ConversationHistoryRepository` / `ChatController.history`
> **日期**：2026-06-30

## 目录

1. [问题背景](#1-问题背景)
2. [现状分析](#2-现状分析)
3. [设计目标](#3-设计目标)
4. [方案对比](#4-方案对比)
5. [最终方案：seq 单调序号 + sinceSeq 增量同步](#5-最终方案seq-单调序号--sinceseq-增量同步)
6. [实施清单](#6-实施清单)
7. [边界场景](#7-边界场景)
   - 7.5 [双端支持（访客端 + 座席端）](#75-双端支持访客端--座席端)
8. [前后端协议契约](#8-前后端协议契约)
9. [迁移与回滚策略](#9-迁移与回滚策略)
10. [监控与运维](#10-监控与运维)

---

## 1. 问题背景

代码评审（commit `a6556c3` 之前）发现 `ChatWebSocketHandler.sendJson()` 存在消息丢失风险：

```java
private void sendJson(WebSocketSession session, Object payload) {
    if (!session.isOpen()) {
        return;  // ← 离线时静默丢弃
    }
    try {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    } catch (IOException e) {
        log.warn("[WS] send failed sessionId={}", ...);  // ← 失败只打日志
    }
}
```

**典型丢失场景**：

| # | 场景 | 当前行为 | 业务影响 |
|---|------|---------|---------|
| 1 | WS 发送瞬时 IOException | log.warn 后丢失 | 用户错过一条座席回复 |
| 2 | `session.isOpen()=true` 但 TCP 半关 | 同上 | 消息进 TCP buffer 但未真正送达 |
| 3 | 接收方完全离线（map 中无 session） | 直接 return | 后端不感知未读 |
| 4 | 客户端关闭浏览器 N 小时后重连 | history 全量返回 | 流量浪费，无法精准续传 |

## 2. 现状分析

### 2.1 数据持久化层（已可靠）

```
访客发消息 → historyRepository.append()
              ├─ Redis List (chat:session:{id}, TTL 24h)   ← 热数据
              └─ RabbitMQ → ConversationMessageConsumer → PostgreSQL  ← 冷数据，永久
```

**关键事实**：所有消息已通过 RabbitMQ 双写到 PostgreSQL `cs_conversation_message` 表，**数据层从未真正丢失**，丢的只是"实时推送的那一次机会"。

### 2.2 实时推送层（不可靠）

- `notifyVisitor` / `notifyAgent` 在 `agentSessions.get` 返回 null 时直接 return
- `sendJson` 抛 IOException 时仅 log.warn
- 客户端虽有 WS 重连（前端已实现指数退避 1s/3s/8s），但**重连成功后没有方式知道"我离线期间漏了哪些消息"**

### 2.3 已有的部分补偿

| 机制 | 覆盖能力 | 缺陷 |
|------|---------|------|
| `GET /api/v1/chat/history` | 拉全量历史（截断 20 轮） | 每次重连都全量重拉，无法增量；20 轮可能不够 |
| Redis List 24h TTL | 热数据快速访问 | 24h 后失效，跨设备登录访问不到 |
| DB 永久存储 | 数据不丢 | 接口未暴露 since 参数 |

## 3. 设计目标

1. **不丢消息**：客户端任意时刻断开重连，能恢复全部漏读消息（无时长上限）
2. **增量同步**：重连不拉全量，只拉 lastSeq 之后的新消息
3. **客户端去重**：网络抖动可能产生重复推送，客户端能识别并跳过
4. **跨设备/跨节点**：客户端在不同设备登录同一会话，所有设备都能拉到完整历史
5. **零侵入式接入**：现有前端 WS 重连逻辑（chat-widget/index.vue）无需大改，只新增 `sinceSeq` 一个参数
6. **降级友好**：Redis 不可用时仍能从 DB 兜底（已有 isActiveInDb 兜底模式）

## 4. 方案对比

| 方案 | 实现复杂度 | 实时性 | 长期离线覆盖 | 跨设备 | 客户端改造 | 推荐 |
|------|-----------|--------|------------|--------|----------|------|
| A. 客户端 ACK + 服务端重试队列 | 高 | 强 | ✅ | ❌（按设备绑定） | 双向协议 | ❌ |
| B. Outbox（Redis ZSet）24h 暂存 | 中 | 中 | ❌（24h 截断） | ❌ | 仅需去重 | ❌ |
| **C. seq 单调序号 + sinceSeq 增量同步** | 低 | 中（重连补） | ✅ | ✅ | 仅新增 1 个参数 | ✅ |
| D. 客户端定期轮询 history | 低 | 弱 | ✅ | ✅ | 无 | ❌（流量浪费） |

**为什么选 C 不选 B**：
- DB 已经永久持久化，消息从未真正丢失，Outbox 是冗余
- Outbox 24h 截断不能覆盖"用户休假一周后重连"
- 跨设备登录时 Outbox 仅在原节点存在，无法共享

**为什么选 C 不选 A**：
- ACK 重试机制复杂度高，需双向协议、超时控制、序列号窗口
- 用户视角"离线再上线"是一次性事件，重连时一次 HTTP 请求拉增量足够
- 已有前端指数退避重连机制，天然适配增量同步

## 5. 最终方案：seq 单调序号 + sinceSeq 增量同步

### 5.1 核心原理

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. 后端为每条消息分配一个 session 内单调递增的 seq                       │
│     - 访客发消息、座席发消息、AI 回复都各自占用一个 seq                   │
│     - 写入路径：append() 中通过 Redis INCR chat:seq:{sessionId} 生成    │
│                                                                       │
│  2. WS 推送 payload 携带 seq 字段                                       │
│                                                                       │
│  3. 客户端维护 lastSeq（localStorage 持久化，按 sessionId 隔离）          │
│                                                                       │
│  4. WS 重连后立即调 GET /api/v1/chat/history?sinceSeq=N                 │
│     - 服务端返回所有 seq > N 的消息                                      │
│     - 客户端按 seq 排序渲染，更新 lastSeq                                │
│                                                                       │
│  5. 之后的实时消息按现有 WS 推送路径流转                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 seq 生成策略

**方案 C-1（推荐）：Redis INCR 单调序号**

```java
// ConversationHistoryRepository.append()
long seq = redis.execute(new DefaultRedisScript<>(
    "local v = redis.call('INCR', KEYS[1]) " +
    "if v == 1 then redis.call('EXPIRE', KEYS[1], 86400) end " +
    "return v", Long.class),
    Collections.singletonList("chat:seq:" + sessionId));
```

- 优势：纯 session 维度，单一 sessionId 内严格单调递增
- 缺陷：Redis 重启后从 1 重新计数 → 客户端 lastSeq=100 时拉不到 1~99 的"重置后新消息"
- 兜底：seq 同时持久化到 DB（`cs_conversation_message.seq` 列），Redis 失效时改用 DB `MAX(seq) + 1`

**方案 C-2（备选）：DB BIGSERIAL**

直接复用 `cs_conversation_message.id`（BIGSERIAL）：
- 优势：DB 单调递增保证强，跨 Redis 重启不漂移
- 缺陷：写入路径增加 DB 等待（破坏现有"先写 Redis 立即推送 → MQ 异步持久化"的设计）

**最终选择 C-1**：保留现有写入路径，Redis 失效后用 DB max + 1 兜底恢复，可接受偶发的少量重复。

### 5.3 数据流时序图

```
┌──────────┐      ┌──────────────────────┐      ┌──────────┐      ┌────────┐
│ Visitor  │      │ ChatWebSocketHandler │      │  Redis   │      │  DB    │
└────┬─────┘      └──────────┬───────────┘      └────┬─────┘      └───┬────┘
     │  send message         │                       │                │
     │──────────────────────►│                       │                │
     │                       │ append():             │                │
     │                       │  1. INCR chat:seq → N │                │
     │                       │──────────────────────►│                │
     │                       │                       │                │
     │                       │  2. RPUSH list (含seq)│                │
     │                       │──────────────────────►│                │
     │                       │                       │                │
     │                       │  3. MQ publish (含seq)                  │
     │                       │ ────────────────► RabbitMQ ───► Consumer
     │                       │                       │                │ INSERT seq=N
     │                       │                       │                │◄─────
     │                       │                       │                │
     │                       │  4. WS push to peer (含 seq)            │
     │                       │ ─────────────────────►peer
     │
     │  (网络抖动断开)
     │
     │  WS 重连 onopen       │                       │                │
     │──────────────────────►│                       │                │
     │                       │                       │                │
     │  GET /history?sinceSeq=N                       │                │
     │──────────────────────►│                       │                │
     │                       │ SELECT WHERE seq > N  │                │
     │                       │──────────────────────────────────────────►
     │                       │◄──────────────────────────── messages (含seq)
     │                       │                       │                │
     │◄──────────────────────│  返回增量消息           │                │
     │                       │                       │                │
     │  渲染 + 更新 lastSeq    │                       │                │
```

## 6. 实施清单

### 6.1 数据库变更（init-all.sql）

```sql
ALTER TABLE cs_conversation.cs_conversation_message
    ADD COLUMN IF NOT EXISTS seq BIGINT;

-- 按 sessionId + seq 索引，支持增量查询
CREATE INDEX IF NOT EXISTS idx_cs_msg_session_seq
    ON cs_conversation.cs_conversation_message (session_id, seq);

-- 同步更新 docs/sql/init-all.sql 中的表定义
```

### 6.2 后端改造

**`ConversationHistoryRepository`**

```java
private static final String SEQ_KEY_PREFIX = "chat:seq:";
private static final RedisScript<Long> SEQ_INCR_SCRIPT = ...;  // INCR + EXPIRE

public long nextSeq(String sessionId) {
    return cache.executeLua(SEQ_INCR_SCRIPT, ...);  // 复用 RedisLockHelper.executeLua
}

public void append(String sessionId, String role, String content) {
    long seq = nextSeq(sessionId);
    writeToListWithTrim(sessionId, role, content, seq);  // List 元素改为 [role, content, seq] 三元组
    publishMessageEvent(sessionId, role, content, seq);  // MQ payload 加 seq 字段
}
```

**`ConversationMessageEntity`** 增加 `seq` 字段：
```java
private Long seq;  // 由 publisher 传入，consumer 写入 DB
```

**`ConversationMessageConsumer`** 从 payload 读 seq 并写入：
```java
entity.setSeq(longVal(payload, ConversationStreamEvent.FIELD_SEQ));
```

**`ConversationStreamEvent`** 增加 `FIELD_SEQ = "seq"` 常量。

**`ChatController.history`** 增加 `sinceSeq` 参数：
```java
@GetMapping("/history")
public R<List<Map<String, Object>>> history(
        @RequestParam String sessionId,
        @RequestParam(required = false, defaultValue = "0") long sinceSeq) {
    return R.ok(chatService.getHistorySince(sessionId, sinceSeq));
}
```

**`ChatAppService.getHistorySince`** 新增实现：
- 优先从 Redis List 取（解析含 seq 的三元组，filter seq > sinceSeq）
- Redis 缺失时回 DB：`SELECT * FROM cs_conversation_message WHERE session_id=? AND seq > ? ORDER BY seq ASC`

**`ChatWebSocketHandler.handleTextMessage`** WS 推送 payload 加 seq：
```java
Map<String, Object> msg = Map.of(
    "type", MSG_TYPE_MESSAGE, "sessionId", sessionId,
    "role", ..., "content", content,
    "seq", seq, "timestamp", ts
);
```

**`ChatWebSocketHandler.sendJson`** 失败时主动关闭 session：
```java
} catch (IOException e) {
    log.warn("[WS] send failed sessionId={}, closing session for client reconnect", sessionId, e);
    try {
        session.close(CloseStatus.SERVER_ERROR);
    } catch (IOException ignored) {}
}
```
触发前端 onclose → 指数退避重连 → 重连后调 history with sinceSeq。

### 6.3 前端改造（vue-vben-admin）

**`api/session/index.ts`**

```typescript
export async function getHistorySinceApi(
    sessionId: string,
    sinceSeq: number,
): Promise<ChatMessage[]> {
    return agentClient.get('/chat-api/chat/history', {
        params: { sessionId, sinceSeq },
    });
}
```

**`chat-widget/index.vue`** 维护 lastSeq：

```typescript
const lastSeq = ref<number>(
    Number(sessionStorage.getItem(`chat:lastSeq:${sessionId}`) ?? '0')
);

// 收到 WS 消息时
function onWsMessage(msg: WsChatMessage) {
    if (msg.seq && msg.seq > lastSeq.value) {
        lastSeq.value = msg.seq;
        sessionStorage.setItem(`chat:lastSeq:${sessionId}`, String(msg.seq));
    }
    // 渲染消息（用 seq 做 key 防重复）
}

// WS onopen 钩子：重连后拉增量
ws.addEventListener('open', async () => {
    const messages = await getHistorySinceApi(sessionId, lastSeq.value);
    messages.forEach(onWsMessage);
});
```

## 7. 边界场景

| # | 场景 | 处理方案 |
|---|------|---------|
| 1 | 客户端首次连接（sessionStorage 无 lastSeq） | `sinceSeq=0`，拉取全量历史 |
| 2 | Redis `chat:seq:{sessionId}` 失效（24h TTL 过期或重启） | 写入路径用 `SELECT MAX(seq) FROM cs_conversation_message WHERE session_id=?` 重建初始值；客户端 lastSeq 仍指向 DB 中的真实 seq，不影响增量同步 |
| 3 | 客户端跨设备登录（A 设备 lastSeq=50, B 设备首次登录） | B 设备 sinceSeq=0 拉全量，A 设备 sinceSeq=50 拉增量；两边渲染独立 |
| 4 | 重连期间 Redis 已被驱逐（List 失效） | history 接口回 DB 查询，业务无感知 |
| 5 | 消息重复推送（WS 抖动后立即 redeliver） | 客户端用 `seq` 作为 React/Vue 渲染 key，重复 seq 自动去重 |
| 6 | 客户端 lastSeq 大于服务端实际 max seq（理论不会发生） | history 返回空数组，客户端保持现状 |
| 7 | DB seq 列出现 NULL（迁移初期遗留数据） | history 接口 WHERE 子句加 `seq IS NOT NULL`；迁移脚本回填历史 seq |
| 8 | 同一 sessionId 并发写消息（多座席场景） | INCR 原子性保证 seq 不冲突，写入顺序由 Redis 单线程仲裁 |
| 9 | 客户端长时间离线（>30 天） | DB 永久存储，仍可拉到完整增量；可选优化：超过 N 天给客户端返回 truncated 标志 |
| 10 | seq 溢出（BIGINT 上限 9223372036854775807） | 单 session 每秒 10 万消息持续写入约需 290 万年，实际不会触发 |

## 7.5 双端支持（访客端 + 座席端）

本方案**对两个端点对称生效**，但客户端实现细节存在差异。下表给出完整对照。

### 7.5.1 后端处理对称性

| 维度 | 访客端 `/ws/chat/{sessionId}` | 座席端 `/ws/agent/{sessionId}` |
|------|------------------------------|-------------------------------|
| Handler | `ChatWebSocketHandler` | `ChatWebSocketHandler`（同一类） |
| seq 生成 | `nextSeq(sessionId)` | `nextSeq(sessionId)` — **共享同一个 seq 序列** |
| Redis key | `chat:seq:{sessionId}` | `chat:seq:{sessionId}` — **同一个 key** |
| 写入路径 | `historyRepository.append(sessionId, "user", content)` | `historyRepository.appendAgentMessage(sessionId, content)` |
| WS payload | 含 `seq` 字段 | 含 `seq` 字段 |
| history 接口 | `GET /chat-api/chat/history?sessionId=X&sinceSeq=N` | 同一个接口，agent token 鉴权 |
| 鉴权 | 公开（chat-widget 嵌入第三方站点） | Bearer Token（座席登录后） |

**关键设计**：访客和座席的消息共享同一个 `sessionId` 内的 seq 序列。例如：

```
访客发 "你好"      → seq=1
座席发 "您好"      → seq=2
访客发 "请问退款"   → seq=3
AI 转人工提示      → seq=4
座席发 "正在查询"   → seq=5
```

无论谁断线重连，传 `sinceSeq=2` 都能拉到 3、4、5 三条，完全感知不到对面是访客还是座席的消息。

### 7.5.2 双端写入路径的 seq 一致性

```
访客发消息：
  handleTextMessage (role=chat)
    → historyRepository.append(sid, "user", content)
        → long seq = nextSeq(sid)              ← INCR chat:seq:{sid}
        → Redis List RPUSH [user, content, seq]
        → MQ publish (含 seq)
    → 推送给座席 WS (payload 含 seq)

座席发消息：
  handleTextMessage (role=agent)
    → historyRepository.appendAgentMessage(sid, content)
        → long seq = nextSeq(sid)              ← 同一个 INCR 序列
        → Redis List RPUSH [assistant, content, seq]
        → MQ publish (含 seq, role=agent → DB)
    → 推送给访客 WS (payload 含 seq)
```

两端写入共用同一个 `nextSeq()` 方法，由 Redis INCR 原子性保证不冲突。

### 7.5.3 双端前端实现差异

#### 访客端（chat-widget/index.vue）

**特点**：单会话、单 sessionId、移动端友好

```typescript
// 访客只关心当前会话，lastSeq 单一值
const lastSeq = ref<number>(
    Number(sessionStorage.getItem(`chat:lastSeq:${sessionId}`) ?? '0')
);

ws.addEventListener('open', async () => {
    if (lastSeq.value > 0) {
        const missing = await getHistorySinceApi(sessionId, lastSeq.value);
        missing.forEach(applyMessage);  // 渲染到当前对话窗口
    }
});

ws.addEventListener('message', e => {
    const msg = JSON.parse(e.data) as WsChatMessage;
    if (msg.seq && msg.seq > lastSeq.value) {
        lastSeq.value = msg.seq;
        sessionStorage.setItem(`chat:lastSeq:${sessionId}`, String(msg.seq));
    }
    applyMessage(msg);
});
```

#### 座席端（agent/index.vue）

**特点**：多会话并发、每会话独立 lastSeq、需要按 sessionId 路由消息

```typescript
// 座席同时管理多个会话，按 sessionId 隔离 lastSeq
const lastSeqMap = new Map<string, number>();

function getLastSeq(sid: string): number {
    if (!lastSeqMap.has(sid)) {
        const stored = localStorage.getItem(`agent:lastSeq:${sid}`) ?? '0';
        lastSeqMap.set(sid, Number(stored));
    }
    return lastSeqMap.get(sid)!;
}

function updateLastSeq(sid: string, seq: number) {
    if (seq > getLastSeq(sid)) {
        lastSeqMap.set(sid, seq);
        localStorage.setItem(`agent:lastSeq:${sid}`, String(seq));
    }
}

// useAgentWebSocket composable 中，每个会话 WS 独立处理
function connectSession(sid: string) {
    const ws = connectAgentWs(sid, (msg) => {
        if (msg.seq) updateLastSeq(sid, msg.seq);
        appendToSessionMsgs(sid, msg);  // 推送到对应 session 的 msgs 列表
    });
    ws.addEventListener('open', async () => {
        const sinceSeq = getLastSeq(sid);
        if (sinceSeq > 0) {
            const missing = await getHistorySinceApi(sid, sinceSeq);
            missing.forEach(m => appendToSessionMsgs(sid, m));
        }
    });
}
```

#### 关键差异点

| 维度 | 访客端 | 座席端 |
|------|--------|--------|
| lastSeq 存储 | `sessionStorage`（单 sessionId 单值） | `localStorage`（多 sessionId 多值，按 sid 隔离） |
| 持久化时长 | tab 关闭即失效 | 跨浏览器重启保留 |
| 重连触发 | 单 WS onopen | 每个会话 WS 独立 onopen |
| 增量拉取并发 | 1 次/sessionId | N 次（N = 同时接入的会话数）|
| 拉取后路由 | 直接渲染到当前窗口 | 按 sessionId 路由到对应 session 的 msgs 数组 |

### 7.5.4 跨端场景验证

**场景 A：访客离线，座席继续回复**

```
T1 访客在线：lastSeq=10
T2 访客网络中断
T3 座席发消息 → seq=11, 12, 13（推送到访客 WS 失败，仅写 Redis + DB）
T4 访客重连
T5 访客调 history?sinceSeq=10 → 拉到 11, 12, 13 ✅
```

**场景 B：座席切换设备，访客发消息**

```
T1 座席 A 设备登录 sessionId=X, lastSeq=20
T2 座席关闭 A 设备浏览器
T3 访客发消息 seq=21, 22
T4 座席登录 B 设备 → localStorage 中无 sessionId=X 的 lastSeq → 默认 0
T5 history?sinceSeq=0 → 拉到全量历史 ✅（B 设备首次接入）
```

**场景 C：座席同时接 5 个会话，其中 sid=A 网络抖动**

```
T1 座席接入 sid=A,B,C,D,E
T2 sid=A 的 WS 抖动重连
T3 访客 A 在抖动期间发了 3 条消息 → seq=51, 52, 53
T4 sid=A 的 ws.onopen → 调 history?sessionId=A&sinceSeq=50
T5 返回 51, 52, 53，按 sid=A 路由到对应 session.msgs ✅
T6 sid=B,C,D,E 不受影响，各自独立维护 lastSeq
```

### 7.5.5 双端联调测试用例（必备）

| # | 用例 | 预期 |
|---|------|------|
| 1 | 访客发 10 条消息，关闭浏览器 5 分钟，重新打开 | 看到全部 10 条 |
| 2 | 座席接入 3 个会话，刷新页面 | 3 个会话历史完整恢复 |
| 3 | 访客离线 25 小时（超过 Redis TTL）后重连 | 从 DB 拉到全量历史 |
| 4 | 访客和座席同时在线，并发发送 100 条消息 | 双方都看到 100 条，seq 严格递增无丢失 |
| 5 | 座席 A 转交给座席 B，B 接入后调 history | B 看到完整历史（含转交前的对话）|
| 6 | 网络模拟：WS 发 IOException 100 次 | 客户端通过重连补齐，最终消息数一致 |

### 7.5.6 监控双端独立指标

| 指标 | 访客端 | 座席端 |
|------|--------|--------|
| WS 连接数 | `ws.visitor.active` | `ws.agent.active` |
| 重连补发消息数 | `chat.history.since_seq.visitor.avg` | `chat.history.since_seq.agent.avg` |
| sendJson 失败率 | `ws.send_failed.visitor` | `ws.send_failed.agent` |
| 全量重拉（lastSeq=0）次数 | 偏高提示 sessionStorage 异常 | 偏高提示跨设备登录频繁 |

---

## 8. 前后端协议契约

### 8.1 WS 推送消息格式（向前兼容）

```json
{
  "type": "MESSAGE",
  "sessionId": "guest-abc123",
  "role": "agent",
  "content": "您好，正在为您查询",
  "seq": 42,                // ← 新增字段（旧客户端忽略不会出错）
  "timestamp": 1735776000
}
```

### 8.2 HTTP 接口

```
GET /api/v1/chat/history?sessionId={sessionId}&sinceSeq={sinceSeq}

参数：
  sessionId  required   会话 ID
  sinceSeq   optional   仅返回 seq > sinceSeq 的消息，缺省=0（全量）

响应：
{
  "code": 0,
  "data": [
    { "role": "user",      "content": "你好",     "seq": 41, "timestamp": ... },
    { "role": "agent",     "content": "您好",     "seq": 42, "timestamp": ... }
  ]
}
```

### 8.3 sessionStorage Key 约定

```
key:   chat:lastSeq:{sessionId}
value: 字符串形式的 long 值，如 "42"
```

按 sessionId 隔离，多 tab 共享同一 sessionId 时通过 `storage` 事件感知更新。

## 9. 迁移与回滚策略

### 9.1 上线步骤

1. **DB 迁移**：执行 `ALTER TABLE` 加 seq 列 + 索引（NULL 允许，无业务影响）
2. **后端发布**：新版本写入时填充 seq 字段，history 接口支持 sinceSeq 参数（兼容缺省）
3. **前端发布**：新版本 WS 消息读 seq 字段，重连后调 history with sinceSeq

**关键设计**：每一步都向前兼容，可独立部署
- 旧客户端 + 新后端：客户端忽略 seq 字段，行为与旧版一致
- 新客户端 + 旧后端：seq 始终为 undefined，lastSeq 维持 0，每次重连全量拉取（退化为旧行为）

### 9.2 回滚策略

- **后端回滚**：直接部署旧版本，新客户端的 sinceSeq 参数被忽略不影响
- **DB 回滚**：seq 列保留（无副作用），未来重新启用方案 C 时数据连续
- **前端回滚**：清除 sessionStorage 中的 lastSeq 即可

### 9.3 历史数据回填（可选）

```sql
-- 给历史消息按 created_at 升序赋 seq（每个 session 独立编号）
UPDATE cs_conversation.cs_conversation_message m
SET seq = sub.row_num
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS row_num
    FROM cs_conversation.cs_conversation_message
    WHERE seq IS NULL
) sub
WHERE m.id = sub.id;
```

回填后初始化 Redis seq key：
```bash
# 对每个活跃 session 设置 chat:seq:{sessionId} = MAX(seq)
```

## 10. 监控与运维

### 10.1 关键指标

| 指标 | 阈值 | 含义 |
|------|------|------|
| `chat.history.since_seq.calls_per_min` | 监控趋势 | 反映 WS 重连频率，骤增提示网络问题 |
| `chat.history.since_seq.avg_messages_returned` | < 50 | 单次重连补发消息数，过大说明客户端长期离线 |
| `chat.seq.redis_miss_rate` | < 1% | Redis seq key 失效率，过高说明 TTL 配置不当 |
| `ws.send_failed_count` | 监控趋势 | sendJson IOException 频率 |

### 10.2 日志规约

- 写入路径 INFO：`[Seq] generated sessionId={} seq={}`
- 增量查询 DEBUG：`[History] sessionId={} sinceSeq={} returned={}`
- Redis seq 缺失回退 DB WARN：`[Seq] Redis miss, rebuild from DB sessionId={} maxSeq={}`
- WS 发送失败 WARN：`[WS] send failed, closing session sessionId={} reason={}`

### 10.3 性能预期

- 写入额外开销：每条消息多 1 次 Redis INCR（< 1ms）
- 重连开销：单次 history 查询，覆盖索引 `(session_id, seq)`，< 5ms
- 客户端额外内存：1 个 long（8 bytes）/ sessionId

## 附录 A：相关代码位置

- `ChatWebSocketHandler` — `ai-conversation/conversation-service/.../websocket/`
- `ConversationHistoryRepository` — `ai-conversation/conversation-service/.../repository/`
- `ChatController` — `ai-conversation/conversation-service/.../rest/`
- `ConversationStreamEvent` — `ai-conversation/conversation-service/.../mq/`
- 前端 `chat-widget/index.vue` — `apps/web-antd/src/views/chat-widget/`
- 前端 `api/session/index.ts` — `apps/web-antd/src/api/session/`

## 附录 B：FAQ

**Q1：为什么不用 RabbitMQ 的 message-id 而要新增 seq？**
A：RabbitMQ message-id 是 broker 全局唯一 UUID，无法在 session 内单调递增；客户端需要的是"按 session 排序的有序号"，便于做 sinceSeq 增量查询。

**Q2：客户端 sessionStorage 被清空怎么办？**
A：lastSeq 重置为 0，下次重连拉全量。可接受"偶发的一次全量重拉"。

**Q3：seq 是否需要在 Redis List 元素中存储？**
A：需要。Redis List 是热数据快速通道，从 Redis 取 since 增量比从 DB 取快得多。List 元素改为 `[role, content, seq]` 三元组（旧版二元组迁移由代码同时处理即可）。

**Q4：如果 RabbitMQ 消息延迟导致 DB 中 seq 不连续怎么办？**
A：seq 由发送时刻生成，写入 Redis List 是同步的。DB seq 不连续仅意味着对应 MQ 消息还在传输中，几秒内会补齐。客户端按 seq 排序渲染时不会因连续性问题崩溃。

**Q5：是否需要客户端主动 ACK 每条消息？**
A：当前方案不需要。客户端只在重连时通过 sinceSeq 隐式 ACK（"我已经收到 ≤lastSeq 的所有消息"），简化协议。如果未来需要"已读回执"再扩展。

