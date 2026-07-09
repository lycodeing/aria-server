# 后端 WebSocket 多会话统一连接架构改造设计文档

**日期**：2026-07-09  
**状态**：待实现  
**关联前端设计**：`docs/superpowers/specs/2026-07-09-agent-ws-single-channel-design.md`

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
| 新增 | `infrastructure/websocket/AgentConnectionRegistry.java` | 连接存储 + 推送组件 |
| 新增 | `infrastructure/websocket/AgentChannelWsHandler.java` | 座席专用 WS Handler |
| 修改 | `infrastructure/config/WebSocketConfig.java` | 注册新端点，移除旧座席端点 |
| 修改 | `infrastructure/websocket/ChatWebSocketHandler.java` | 移除 agentSessions，改造 notifyAgent |
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
  ├─ registry.register(agentId, newSession)           // ① 先注册新连接
  ├─ if (mode == KICK)
  │    ├─ registry.broadcastExcept(agentId, newSession,   // ② 推 KICKED_OUT 给旧端
  │    │       Map.of("type", "KICKED_OUT"))
  │    └─ registry.closeAllExcept(agentId, newSession)    // ③ 关闭旧连接
  └─ sendJson(newSession, Map.of("type", "CONNECTED"))    // ④ 通知新端连接成功
```

**步骤顺序不能颠倒**：① 必须先于 ②③，目的是保证从旧连接关闭到新连接建立的窗口期内，
访客发来的消息通过 `AgentConnectionRegistry.broadcast` 能推到新连接，不丢失。

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
    private final ChatWebSocketHandler chatHandler;        // 复用 notifyVisitor
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Value("${agent.ws.multi-login-mode:BROADCAST}")
    private String multiLoginMode;

    // afterConnectionEstablished / handleTextMessage /
    // afterConnectionClosed / handleTransportError
}
```

`ChatWebSocketHandler` 注入是为了复用 `notifyVisitor`，避免重复实现访客推送逻辑。
后续若拆分重构，可将 `notifyVisitor` 提取到独立 `VisitorNotifier` 组件。

## 5. ChatWebSocketHandler 改造

### 5.1 移除 agentSessions

旧的 `agentSessions: ConcurrentHashMap<String, WebSocketSession>` 及其所有引用全部删除：

- `afterConnectionEstablished` 中的 `agentSessions.put(...)` → 删除
- `afterConnectionClosed` 中的 `agentSessions.remove(...)` → 删除
- `handleTransportError` 中的 `agentSessions.remove(...)` → 删除
- `sendLocks` 中针对 agent session 的清理 → 删除（由 `AgentConnectionRegistry` 接管）

`ChatWebSocketHandler` 改造后只处理访客端（`/ws/chat/{sessionId}`），
`agentSessions` 相关字段和分支代码全部清除，类职责更单一。

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
    // 通过 Redis 查询 sessionId 对应的 agentId（SessionQueueItem 已有该字段）
    String agentId = sessionQueueRepository.findById(sessionId)
            .map(SessionQueueItem::agentId)
            .orElse(null);

    if (agentId == null) {
        // 会话仍处于 WAITING 状态（未分配座席）或已关闭，跳过推送
        // TYPING 信号用 debug 级别，业务消息用 warn
        if (payload instanceof Map<?,?> m && "TYPING".equals(m.get("type"))) {
            log.debug("[WS] notifyAgent 跳过 TYPING：sessionId={} 尚未分配座席", sessionId);
        } else {
            log.warn("[WS] notifyAgent 跳过：sessionId={} 尚未分配座席或会话已关闭", sessionId);
        }
        return;
    }

    agentConnectionRegistry.broadcast(agentId, payload);
}
```

### 5.3 新增依赖注入

在 `ChatWebSocketHandler` 中新增两个字段：

```java
private final SessionQueueRepository sessionQueueRepository;
private final AgentConnectionRegistry agentConnectionRegistry;
```

通过 `@RequiredArgsConstructor` 自动注入，无需手动修改构造函数。

### 5.4 TYPING 转发路径变化

旧实现（`handleTextMessage` 第 138-148 行）中，访客发来 TYPING 信号时：

```java
notifyAgent(sessionId, Map.of("type", MSG_TYPE_TYPING, "sessionId", sessionId, "timestamp", ts));
```

改造后调用链不变，只是 `notifyAgent` 内部从查内存改为查 Redis + 广播。调用方代码无需修改。

### 5.5 消息推送路径全景（改造后）

```
访客发消息
  → ChatWebSocketHandler.handleTextMessage
      → handleVisitorMessage
          → historyRepository.append          // 写历史（不丢消息）
          → notifyAgent(sessionId, msg)
              → Redis findById(sessionId).agentId
              → AgentConnectionRegistry.broadcast(agentId, msg)
                  → 遍历 agentToSessions[agentId]
                  → synchronized sendJson(each session)

座席发消息（新架构）
  → AgentChannelWsHandler.handleTextMessage
      → historyRepository.appendAgentMessage  // 写历史
      → chatHandler.notifyVisitor(sessionId, msg)
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

`AgentChannelWsHandler` 通过 `@Value("${agent.ws.multi-login-mode:BROADCAST}")` 读取，
默认值为 `BROADCAST`，不配置时行为与多数现有系统一致（多端并发在线）。

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

建议：
- 选择低峰期部署
- 部署前通知在线座席保存当前工作状态

### 6.5 测试要点

| 场景 | 验证内容 |
|------|---------|
| 单端登录 BROADCAST | 访客消息正常推送给座席 |
| 多端登录 BROADCAST | 两个浏览器标签页均收到消息 |
| 多端登录 KICK | 新端连入后旧端收到 KICKED_OUT，旧连接被关闭 |
| 座席发消息 | 访客正常收到 |
| TYPING 转发 | 访客输入中信号推到座席 |
| 座席断线重连 | 重连后消息正常，历史可加载，会话不中断 |
| WAITING 会话 notifyAgent | warn 日志输出，不抛异常 |
| 鉴权失败 | 握手返回 401，Handler 不触发 |
| token 过期 | 握手返回 401，前端走 token 刷新流程 |

### 6.6 后续可选优化（非本次范围）

- 将 `notifyVisitor` 提取为独立 `VisitorNotifier` 组件，解除 `AgentChannelWsHandler` 对 `ChatWebSocketHandler` 的直接依赖
- `AgentConnectionRegistry` 加入连接数监控指标（Micrometer Gauge），便于观测多端登录分布
- 为 `agent.ws.multi-login-mode` 支持热更新（`@RefreshScope`），无需重启即可切换模式
