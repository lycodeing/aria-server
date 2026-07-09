# 后端 WebSocket 多会话统一连接架构改造设计文档

**日期**：2026-07-09  
**状态**：待实现（评审已通过，修复后版本）  
**关联前端设计**：`docs/superpowers/specs/2026-07-09-agent-ws-single-channel-design.md`  
**评审修复**：C-1 KICK 并发竞态、C-2 死代码清除范围、I-1 VisitorNotifier 拆分、I-2 MultiLoginMode 枚举、I-3 TYPING 跳过 Redis、I-4 分层修正

---

## 1. 背景与目标

### 1.1 现状问题

当前座席端 WebSocket 采用「一会话一连接」模式：

- 端点路径：`/ws/agent/{sessionId}`
- 座席同时处理 N 个会话，需建立 N 条 WS 连接
- `agentSessions` 注册表以 `sessionId` 为键，每个 sessionId 对应一个独立的 TCP 连接
- 浏览器对同一 Origin 的并发 WS 连接数有上限（通常 6-8 个），多会话场景下资源浪费严重

### 1.2 改造目标

将座席端改造为「一座席一连接，消息体内多路复用」模式：

- 新端点路径：`/ws/agent`（无路径参数）
- 每个座席建立且仅建立一条 WS 连接，所有会话消息通过同一通道收发
- 消息路由依赖消息体内的 `sessionId` 字段，而非连接本身
- 支持同一座席多端登录（BROADCAST / KICK 两种模式，由后端配置控制）

### 1.3 改造范围

- **硬切换**：不保留旧端点 `/ws/agent/{sessionId}`，前后端同步部署
- **不涉及**：访客端 `/ws/chat/{sessionId}` 不做任何改动
- **不涉及**：消息存储、MQ、SSE 等其他基础设施

### 1.4 消息协议约定（来自前端设计文档）

所有下行消息必须携带 `sessionId` 字段，前端用于分发到对应会话订阅：

```json
{ "type": "MESSAGE", "sessionId": "xxx", "role": "user", "content": "...", "seq": 1, "timestamp": 1234 }
{ "type": "TYPING",  "sessionId": "xxx", "timestamp": 1234 }
{ "type": "AGENT_JOINED", "sessionId": "xxx", "content": "人工客服已接入" }
{ "type": "CONNECTED" }
{ "type": "KICKED_OUT" }
```

`KICKED_OUT` 无需 `sessionId`，是连接级别的信令，前端收到后不触发重连，展示提示弹窗。

## 2. 架构总览

### 2.1 改造前后对比

**改造前：**

```
/ws/chat/{sessionId}  ──┐
                         ├──► ChatWebSocketHandler
/ws/agent/{sessionId} ──┘
                              agentSessions: ConcurrentHashMap<sessionId, WsSession>
                              visitorSessions: ConcurrentHashMap<sessionId, WsSession>

notifyAgent(sessionId, payload)
  → agentSessions.get(sessionId) → sendJson
```

**改造后：**

```
/ws/chat/{sessionId}  ──────► ChatWebSocketHandler（仅处理访客）
                                visitorSessions: ConcurrentHashMap<sessionId, WsSession>
                                notifyAgent(sessionId, payload)
                                  → Redis 查 agentId
                                  → AgentConnectionRegistry.broadcast(agentId, payload)

/ws/agent             ──────► AgentChannelWsHandler（新增，仅处理座席）
     ↑ AgentHandshakeInterceptor（已有，从 ?token= 解析 agentId 写入 attributes）
                                  ↓
                               AgentConnectionRegistry（新增 @Component）
                                 agentToSessions: Map<agentId, Set<WsSession>>
                                 sessionIdToAgentId: Map<wsSessionId, agentId>
                                 sendLocks: Map<wsSessionId, Object>
```

### 2.2 涉及改动清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `domain/MultiLoginMode.java` | 多登录模式枚举（领域层） |
| 新增 | `infrastructure/websocket/VisitorNotifier.java` | 访客推送接口（解耦 Handler 间依赖） |
| 新增 | `infrastructure/websocket/AgentConnectionRegistry.java` | 连接存储 + 推送组件 |
| 新增 | `infrastructure/websocket/AgentChannelWsHandler.java` | 座席专用 WS Handler |
| 修改 | `infrastructure/config/WebSocketConfig.java` | 注册新端点，移除旧座席端点 |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java` | 移除 agentSessions 及全部 agent 分支死代码，改造 notifyAgent，实现 VisitorNotifier |
| 修改 | `application/service/SessionQueueService.java` | 新增 `getAgentId(sessionId)` 方法 |
| 修改 | `resources/application.yml` | 新增 `agent.ws.multi-login-mode` 配置项 |
| 不动 | `infrastructure/websocket/AgentHandshakeInterceptor.java` | 已从 token 解析 agentId，兼容新端点 |

### 2.3 多登录模式配置

由后端 `application.yml` 控制，前端不传递此参数：

```yaml
agent:
  ws:
    multi-login-mode: BROADCAST   # 默认值；或 KICK
```

| 模式 | 行为 |
|------|------|
| `BROADCAST`（默认） | 多端并发在线，同一座席的所有连接均收到消息 |
| `KICK` | 新端连入时向旧端推 `KICKED_OUT` 信令，随后关闭旧连接 |

`MultiLoginMode` 定义为 `domain` 层枚举（业务策略，非基础设施配置）：

```java
// domain/MultiLoginMode.java
public enum MultiLoginMode {
    /** 多端并发，消息广播所有在线连接 */
    BROADCAST,
    /** 新端登录时踢出旧端 */
    KICK
}
```

`AgentChannelWsHandler` 注入方式：

```java
@Value("${agent.ws.multi-login-mode:BROADCAST}")
private MultiLoginMode multiLoginMode;
```

配置值拼写错误时 Spring 启动即失败，快速暴露配置问题。

## 3. AgentConnectionRegistry 设计

### 3.1 职责

纯粹的连接存储与推送组件，不感知任何业务逻辑（KICK/BROADCAST 决策由调用方实现）。

### 3.2 数据结构

```java
// 正向索引：agentId → 该座席所有在线 WS 连接（多端支持）
private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSession>> agentToSessions
    = new ConcurrentHashMap<>();

// 反向索引：wsSession.getId() → agentId（afterConnectionClosed 时 O(1) 清理）
private final ConcurrentHashMap<String, String> sessionIdToAgentId
    = new ConcurrentHashMap<>();

// 发送锁：wsSession.getId() → Object（串行化 sendMessage，防止并发帧损坏）
private final ConcurrentHashMap<String, Object> sendLocks
    = new ConcurrentHashMap<>();
```

### 3.3 对外方法

```java
/**
 * 注册新连接。agentId 从 WS session attributes 中读取（由 AgentHandshakeInterceptor 写入）。
 */
public void register(String agentId, WebSocketSession session);

/**
 * 注销连接。通过反向索引找到 agentId，无需调用方传入。
 * 连接关闭（afterConnectionClosed）和 transport error 时均调用此方法。
 */
public void unregister(WebSocketSession session);

/**
 * 向该座席所有在线连接广播消息（BROADCAST 模式 / 日常推送）。
 */
public void broadcast(String agentId, Object payload);

/**
 * 向该座席除 exclude 之外的所有连接广播消息（KICK 模式推 KICKED_OUT 信令用）。
 * 命名说明：broadcastToAllExcept 语义更清晰，简写为 broadcastExcept 与 closeAllExcept 保持对称。
 */
public void broadcastExcept(String agentId, WebSocketSession exclude, Object payload);

/**
 * 关闭该座席除 keep 之外的所有连接（KICK 模式踢出旧连接用）。
 * 调用前应先 broadcastExcept 推送 KICKED_OUT，让前端有机会感知。
 */
public void closeAllExcept(String agentId, WebSocketSession keep);
```

### 3.4 关键实现细节

#### 空 Set 的原子清理

`unregister` 时需避免 TOCTOU 竞态，用 `computeIfPresent` 原子判断是否置空：

```java
public void unregister(WebSocketSession session) {
    String agentId = sessionIdToAgentId.remove(session.getId());
    sendLocks.remove(session.getId());
    if (agentId == null) return;
    agentToSessions.computeIfPresent(agentId, (k, set) -> {
        set.remove(session);
        return set.isEmpty() ? null : set;  // 空则从 map 中移除 key
    });
}
```

#### KICK 模式并发安全（per-agentId 锁）

KICK 模式下 register→broadcastExcept→closeAllExcept 三步必须对同一 agentId 保持原子性。
若两个新连接同时接入同一座席，不加锁会导致互相踢对方，最终所有连接均被关闭。

解决方案：维护 per-agentId 粗粒度锁，仅在 KICK 分支加锁，BROADCAST 路径无锁：

```java
// AgentConnectionRegistry 内部
private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

private Object getAgentLock(String agentId) {
    return agentLocks.computeIfAbsent(agentId, k -> new Object());
}
```

调用方（`AgentChannelWsHandler`）在 KICK 模式下：

```java
// KICK 模式：register + broadcastExcept + closeAllExcept 三步原子执行
synchronized (registry.getAgentLock(agentId)) {
    registry.register(agentId, newSession);
    registry.broadcastExcept(agentId, newSession, Map.of("type", "KICKED_OUT"));
    registry.closeAllExcept(agentId, newSession);
}
// 锁外推送 CONNECTED（新连接已注册，此时无并发风险）
sendJson(newSession, Map.of("type", "CONNECTED"));
```

BROADCAST 模式只需 `register`，不涉及此锁。

#### sendJson 内部串行化

与现有 `ChatWebSocketHandler.sendJson` 保持同一模式：

```java
private void sendJson(WebSocketSession session, Object payload) {
    Object lock = sendLocks.computeIfAbsent(session.getId(), k -> new Object());
    synchronized (lock) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("[AgentRegistry] 发送失败 sessionId={} msg={}", session.getId(), e.getMessage());
            try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
        }
    }
}
```

> ⚠️ **实现约束**：`broadcast` 遍历 `CopyOnWriteArraySet` 时按插入顺序加锁，多线程遍历锁序一致，
> 不存在死锁。禁止将 `agentToSessions` 的 value 类型替换为无序集合（如 `HashSet`），
> 否则会引入锁序不确定导致的死锁风险。

发送失败时主动 `close(SERVER_ERROR)`，触发 `afterConnectionClosed` → 自动 `unregister`，不产生僵尸连接。

### 3.5 调用点汇总

| 调用位置 | 方法 |
|----------|------|
| `AgentChannelWsHandler.afterConnectionEstablished` | `register` |
| `AgentChannelWsHandler.afterConnectionClosed` | `unregister` |
| `AgentChannelWsHandler.handleTransportError` | `unregister` |
| `ChatWebSocketHandler.notifyAgent`（改造后） | `broadcast` |
| KICK 模式新连接接入时 | `broadcastExcept` → `closeAllExcept` |

## 4. AgentChannelWsHandler 设计

### 4.1 职责

座席专用 WS Handler，替代旧的 `ChatWebSocketHandler` 中处理 `/ws/agent/{sessionId}` 的逻辑。
注册到新端点 `/ws/agent`，负责：
- 连接建立/关闭时维护 `AgentConnectionRegistry`
- 按 multi-login-mode 执行 BROADCAST 或 KICK 逻辑
- 处理座席发送的消息（转发给访客）
- 向新连接推送 `CONNECTED` 确认信令

### 4.2 afterConnectionEstablished 流程

```
afterConnectionEstablished(newSession)
  ├─ 读取 agentId = attributes["agentId"]（由 AgentHandshakeInterceptor 写入）
  ├─ if (mode == KICK)
  │    └─ synchronized(registry.getAgentLock(agentId))    // ① KICK 模式加 per-agentId 锁
  │         ├─ registry.register(agentId, newSession)      // ② 先注册新连接
  │         ├─ registry.broadcastExcept(agentId, newSession,// ③ 推 KICKED_OUT 给旧端
  │         │       Map.of("type", "KICKED_OUT"))
  │         └─ registry.closeAllExcept(agentId, newSession) // ④ 关闭旧连接
  ├─ else (BROADCAST)
  │    └─ registry.register(agentId, newSession)           // 无锁，直接注册
  └─ sendJson(newSession, Map.of("type", "CONNECTED"))     // ⑤ 通知新端连接成功（锁外）
```

**KICK 模式三步为何必须加锁**：两个新连接同时接入同一座席时，三步非原子会导致双方互相踢对方，
最终所有连接均被关闭。per-agentId 锁将三步原子化，BROADCAST 路径完全不涉及此锁。

**步骤 ② 必须先于 ③④**：注册新连接后，窗口期内访客消息通过 `broadcast` 能推到新连接，不丢失。

### 4.3 handleTextMessage 流程

座席通过新连接发送消息，消息体格式：

```json
{ "type": "MESSAGE", "sessionId": "xxx", "content": "..." }
```

```
handleTextMessage(session, message)
  ├─ 消息长度校验（> 64KB → close NOT_ACCEPTABLE）
  ├─ 解析 JSON，提取 type / sessionId / content
  ├─ if type == TYPING → notifyVisitor(sessionId, TYPING 信令)，return
  └─ if type == MESSAGE
       ├─ historyRepository.appendAgentMessage(sessionId, content)
       ├─ notifyVisitor(sessionId, msg)
       └─ log.debug
```

`sessionId` 由消息体携带（新架构），不再依赖路径参数。

### 4.4 afterConnectionClosed / handleTransportError

两个生命周期回调均只需一步操作：

```java
registry.unregister(session);
log.info("[AgentWS] agent disconnected agentId={}", session.getAttributes().get("agentId"));
```

注意：与旧实现保持一致的语义——**座席断线不等于会话结束**，不触发任何 session 关闭逻辑。
会话关闭仍由座席主动调用 `POST /sessions/{id}/close` 接口触发。

### 4.5 鉴权失败处理

`AgentHandshakeInterceptor` 在 token 无效时返回 `false` 并设置 HTTP 401，握手被拒绝，
Handler 的 `afterConnectionEstablished` 不会被调用，无需额外处理。

### 4.6 类结构摘要

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChannelWsHandler extends TextWebSocketHandler {

    private static final String ATTR_AGENT_ID = "agentId";
    private static final int MAX_MESSAGE_BYTES = 65536;

    private final AgentConnectionRegistry registry;
    private final VisitorNotifier visitorNotifier;          // 接口，不依赖 ChatWebSocketHandler
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Value("${agent.ws.multi-login-mode:BROADCAST}")
    private MultiLoginMode multiLoginMode;

    // afterConnectionEstablished / handleTextMessage /
    // afterConnectionClosed / handleTransportError
}
```

**VisitorNotifier 接口**（新增，infrastructure/websocket 层）：

```java
public interface VisitorNotifier {
    void notifyVisitor(String sessionId, Object payload);
    void closeVisitorSessionNormal(String sessionId);
}
```

`ChatWebSocketHandler` 实现此接口，`AgentChannelWsHandler` 只依赖接口，两个 Handler 不再直接相互引用。
这是本次实现的**前置条件**，非可选优化。

## 5. ChatWebSocketHandler 改造

### 5.1 移除 agentSessions 及全部 agent 分支死代码

旧的 `agentSessions: ConcurrentHashMap<String, WebSocketSession>` 及其所有引用全部删除。
改造后 `ChatWebSocketHandler` 只处理访客端 `/ws/chat/{sessionId}`，以下代码均成为死代码，
**必须一并删除**（保留死代码存在安全风险：若路由配置错误导致 `/ws/agent` 意外命中此 Handler，
`handleAgentMessage` 会静默处理座席消息而不被发现）：

| 待删除位置 | 内容 |
|-----------|------|
| 字段声明 | `agentSessions: ConcurrentHashMap<String, WebSocketSession>` |
| `afterConnectionEstablished` else 分支 | `agentSessions.put(...)` + `notifyVisitor(AGENT_JOINED)` |
| `afterConnectionClosed` else 分支 | `agentSessions.remove(...)` |
| `handleTransportError` else 分支 | `agentSessions.remove(...)` |
| `sendLocks` agent 清理逻辑 | 由 `AgentConnectionRegistry` 接管 |
| `handleAgentMessage` 整个方法 | 座席消息改由 `AgentChannelWsHandler` 处理 |
| `VALID_ROLES` 集合 | 简化为 `Set.of("chat")`，移除 `"agent"` |
| `handleTextMessage` 中 role 判断 else 分支 | 调用 `handleAgentMessage` 的路径 |

`ChatWebSocketHandler` 改造后类职责单一，仅负责访客连接管理与消息转发。

### 5.2 改造 notifyAgent

**改造前：**

```java
public void notifyAgent(String sessionId, Object payload) {
    WebSocketSession as = agentSessions.get(sessionId);
    if (as != null && as.isOpen()) {
        sendJson(as, payload);
    }
}
```

**改造后：**

```java
public void notifyAgent(String sessionId, Object payload) {
    // TYPING 信号允许丢失，无需走 Redis 路由，直接跳过（避免高频击键产生大量 Redis 查询）
    if (payload instanceof Map<?,?> m && MSG_TYPE_TYPING.equals(m.get("type"))) {
        log.debug("[WS] notifyAgent 跳过 TYPING：sessionId={} 无需路由", sessionId);
        return;
    }

    // 通过应用层 Service 查询 sessionId 对应的 agentId（不直接依赖存储层）
    String agentId = sessionQueueService.getAgentId(sessionId);
    if (agentId == null) {
        // 会话仍处于 WAITING 状态（未分配座席）或已关闭，跳过推送
        log.warn("[WS] notifyAgent 跳过：sessionId={} 尚未分配座席或会话已关闭", sessionId);
        return;
    }

    agentConnectionRegistry.broadcast(agentId, payload);
}
```

**关于 TYPING 处理的说明**：TYPING 信号是 ephemeral 状态，本身允许丢失，旧架构中
`agentSessions.get(sessionId) == null` 时也是静默丢弃。新架构直接在入口 return，
既保持相同语义，又完全消除了 TYPING 路径的 Redis 访问。

### 5.3 新增依赖注入

在 `ChatWebSocketHandler` 中新增两个字段：

```java
private final SessionQueueService sessionQueueService;       // 应用层，不直接注入 Repository
private final AgentConnectionRegistry agentConnectionRegistry;
```

同时在 `SessionQueueService` 新增查询方法（应用层封装路由查询，Handler 不感知存储层）：

```java
/**
 * 查询会话当前负责的座席 ID。
 * 会话处于 WAITING 状态或不存在时返回 null。
 *
 * @param sessionId 会话 ID
 * @return agentId，无分配时返回 null
 */
public String getAgentId(String sessionId) {
    return queueRepository.findById(sessionId)
            .map(SessionQueueItem::agentId)
            .orElse(null);
}
```

通过 `@RequiredArgsConstructor` 自动注入，无需手动修改构造函数。

### 5.4 TYPING 转发路径变化

旧实现中访客发来 TYPING 时，`handleTextMessage` 调用 `notifyAgent`，内部尝试查 agentSessions 推送。

新架构调整：
- `handleTextMessage` 中访客 TYPING 信号依然调用 `notifyAgent(sessionId, typingMsg)`
- `notifyAgent` 内部**在入口处直接 return**（新增的 TYPING 提前判断），不走 Redis 查询
- 效果等同旧架构的静默丢弃，无行为变化，但消除了所有 TYPING 路径的 Redis 访问

### 5.5 消息推送路径全景（改造后）

```
访客发消息（MESSAGE）
  → ChatWebSocketHandler.handleTextMessage
      → handleVisitorMessage
          → historyRepository.append          // 写历史（不丢消息）
          → notifyAgent(sessionId, msg)
              → payload 不是 TYPING，继续
              → sessionQueueService.getAgentId(sessionId)  // 应用层查 Redis
              → AgentConnectionRegistry.broadcast(agentId, msg)
                  → 遍历 agentToSessions[agentId]（CopyOnWriteArraySet）
                  → synchronized sendJson(each session)

访客发 TYPING 信号
  → ChatWebSocketHandler.handleTextMessage
      → notifyAgent(sessionId, typingMsg)
          → payload 是 TYPING → log.debug → return（直接跳过，无 Redis 查询）

座席发消息（新架构）
  → AgentChannelWsHandler.handleTextMessage
      → historyRepository.appendAgentMessage  // 写历史
      → visitorNotifier.notifyVisitor(sessionId, msg)  // 通过接口调用
          → visitorSessions.get(sessionId)
          → sendJson(visitor session)
```

## 6. 配置与迁移

### 6.1 application.yml 新增配置

```yaml
agent:
  ws:
    multi-login-mode: BROADCAST   # BROADCAST（默认）| KICK
```

`AgentChannelWsHandler` 通过 `@Value("${agent.ws.multi-login-mode:BROADCAST}")` 注入 `MultiLoginMode` 枚举，
配置值拼写错误时 Spring 启动即失败，不会静默 fallback。

### 6.2 WebSocketConfig 改造

**移除旧座席端点注册：**

```java
// 删除：
registry.addHandler(chatWebSocketHandler, "/ws/agent/*")
        .addInterceptors(agentHandshakeInterceptor)
        .setAllowedOrigins(agentOrigins);
```

**新增座席专用 Handler 注册：**

```java
// 新增：
registry.addHandler(agentChannelWsHandler, "/ws/agent")
        .addInterceptors(agentHandshakeInterceptor)
        .setAllowedOrigins(agentOrigins);
```

访客端点 `/ws/chat/*` 不变。

### 6.3 AgentHandshakeInterceptor 兼容性

现有拦截器逻辑无需修改，已满足新端点要求：

- 从 `?token=xxx` 解析 agentId（新端点无路径参数，这一点天然兼容）
- 校验通过后写入 `attributes["agentId"]` 和 `attributes["token"]`
- 鉴权失败返回 HTTP 401

### 6.4 部署注意事项

由于采用硬切换策略，前后端需**同步部署**：

1. 后端先部署（新端点上线，旧端点下线）
2. 前端立即跟进部署（切换到新端点 `/ws/agent`）
3. 部署期间存在短暂的座席 WS 连接中断，座席刷新页面后自动重连新端点

**回滚预案**：若后端部署后发现问题，在前端尚未部署时可直接回滚后端。
若前后端均已部署，需前后端同步回滚。建议：
- 选择低峰期部署
- 部署前通知在线座席保存当前工作状态
- 准备好回滚脚本，确保能在 5 分钟内完成前后端双端回滚

### 6.5 测试要点

| 场景 | 验证内容 |
|------|---------|
| 单端登录 BROADCAST | 访客消息正常推送给座席 |
| 多端登录 BROADCAST | 两个浏览器标签页均收到消息 |
| 多端登录 KICK | 新端连入后旧端收到 KICKED_OUT，旧连接被关闭 |
| KICK 并发双登录 | 两个连接同时接入同一 agentId，结果只有最后一个存活，无双踢 |
| 座席发消息 | 访客正常收到 |
| TYPING 转发 | 访客输入中信号推到座席；WAITING 状态下 debug 日志不报 warn |
| 座席断线重连 | 重连后消息正常，历史可加载，会话不中断 |
| WAITING 会话 notifyAgent | warn 日志输出，不抛异常 |
| 鉴权失败 | 握手返回 401，Handler 不触发 |
| token 过期 | 握手返回 401，前端走 token 刷新流程 |
| 座席消息超过 64KB | close NOT_ACCEPTABLE，不影响其他连接 |
| Redis 中 agentId 为 null（WAITING）| notifyAgent warn 日志，不抛 NPE |
| multi-login-mode 配置拼写错误 | Spring 启动失败，不静默 fallback |

### 6.6 后续可选优化（非本次范围）

- `AgentConnectionRegistry` 加入连接数监控指标（Micrometer Gauge），便于观测多端登录分布
- 为 `agent.ws.multi-login-mode` 支持热更新（`@RefreshScope`），无需重启即可切换模式
- `SessionQueueService.getAgentId` 加本地 Caffeine 缓存，进一步降低 Redis 访问频率
