# WebSocket 集群多机部署技术方案

**日期**：2026-07-09  
**状态**：设计评审中  
**作者**：架构组

---

## 1. 背景与问题分析

### 1.1 现状

当前 `conversation-service` 以单实例运行，WebSocket 连接的访客和座席均落在同一 JVM 进程内：

```
访客 ──WS──▶ 单节点 conversation-service ◀──WS── 座席
              ├─ visitorSessions: Map<sessionId, WsSession>
              └─ agentToSessions: Map<agentId, Set<WsSession>>
```

消息路由逻辑：
- `ChatWebSocketHandler.notifyAgent` → `agentConnectionRegistry.broadcast(agentId, msg)` → 本地 Map 查找 WsSession → `session.sendMessage()`
- `ChatWebSocketHandler.notifyVisitor` → `visitorSessions.get(sessionId)` → `session.sendMessage()`

### 1.2 多实例部署后的核心矛盾

当 `conversation-service` 水平扩展为多 Pod 时，访客和座席经过负载均衡各自连到任意 Pod，连接对象（`WebSocketSession`）仅存在于建立连接的那个 Pod 的 JVM 内存中，其他 Pod 无法访问。

```
                      负载均衡器
                    /            \
访客 ──WS──▶ Pod A (8082)    Pod B (8082) ◀──WS── 座席
              visitorSessions              agentToSessions
              sess-001 → ✓                agent-001 → ✓

访客在 Pod A 发消息：
  Pod A.notifyAgent("sess-001", msg)
    → getAgentId("sess-001") → "agent-001"   ✓ Redis 查到
    → agentRegistry.broadcast("agent-001")
    → 本地 agentToSessions["agent-001"] = 空  ✗ 消息丢失！
```

### 1.3 问题清单

| 问题 | 影响 | 触发条件 |
|------|------|---------|
| 访客消息无法推送给座席 | 消息丢失 | 访客和座席在不同 Pod |
| 座席回复无法推送给访客 | 消息丢失 | 同上 |
| KICK 模式单 Pod 有效 | 多端登录控制失效 | 两次连接落在不同 Pod |
| AgentOnlineRegistry 计数不准 | Pod 崩溃后在线状态错误 | Pod 异常退出 |
| Snowflake WORKER_ID 冲突 | ID 重复 | 多 Pod 且未设置 WORKER_ID |
| PersistHandler 分布式锁 TTL 过短 | 数据损坏 | 大文档写入超 5 分钟 |

### 1.4 不受影响的部分

以下组件在多实例场景下**无需改动**，已正确实现：

- `SessionEventSubscriber`：使用 RabbitMQ `exclusive + autoDelete` 匿名队列，每 Pod 独立接收 Fanout 事件，SSE 推送天然支持多 Pod
- `SessionQueueRepository`：CAS Lua 脚本操作 Redis Hash，原子性由 Redis 单线程保证，多 Pod 安全
- `ConversationHistoryRepository`：Lua INCR 脚本，seq 生成跨 Pod 正确
- `cs.conversation.persist` 队列：共享队列竞争消费，DB 持久化天然负载均衡

## 2. 整体架构设计

### 2.1 设计目标

- 访客和座席 WebSocket 连接可落在任意 Pod，消息正确路由投递
- Pod 崩溃时不丢消息、不留脏数据，客户端重连后自动恢复
- 复用现有 RabbitMQ 基础设施，不引入新中间件
- 对现有业务逻辑（会话管理、历史记录、SSE）零改动

### 2.2 核心思路

```
每个 Pod 启动时，RabbitMQ 自动分配一个唯一的匿名队列（如 amq.gen-abc123）
这个队列名 = Pod 的唯一身份标识（podId）

连接建立时：将 agentId/sessionId → podId 写入 Redis（带 TTL）
消息推送时：先查 Redis 确定目标 Pod，再决定本地推送还是跨 Pod 投递
Pod 停止时：RabbitMQ auto-delete 队列自动消失，Redis TTL 自动过期
```

### 2.3 改造后架构图

```
                         负载均衡器
                        /           \
访客 ──WS──▶ Pod A                  Pod B ◀──WS── 座席
              │                          │
              │  ① 写 presence          │  ① 写 presence
              ▼                          ▼
           Redis                      Redis
    ws:visitor:pod:sess-001 = amq.gen-AAA   (Pod A 队列)
    ws:agent:pods:agent-001 = amq.gen-BBB   (Pod B 队列)

访客在 Pod A 发消息给座席：
  ② Pod A.notifyAgent("sess-001", msg)
     → WsMessageRouter.route("agent-001", msg)
     → WsPresenceRegistry.getAgentPod("agent-001") → "amq.gen-BBB"
     → amq.gen-BBB ≠ 本机队列 → 发到 ws.delivery exchange
              │
              ▼  RabbitMQ Direct Exchange (ws.delivery)
  ③ Pod B 的 WsDeliveryConsumer 收到消息
     → agentConnectionRegistry.broadcast("agent-001", msg)
     → 本地 WsSession.sendMessage()  ✓
```

### 2.4 新增组件总览

| 组件 | 类型 | 职责 |
|------|------|------|
| `PodIdentity` | `@Component` | 持有当前 Pod 的 RabbitMQ 队列名（podId） |
| `WsPresenceRegistry` | `@Component` | Redis 操作封装：维护 agentId/sessionId → podId 映射 |
| `WsMessageRouter` | `@Component` | 路由决策：本机直推 or MQ 跨 Pod 投递 |
| `WsDeliveryConsumer` | `@Component` | 消费本 Pod 专属 MQ 队列，执行本地推送 |
| `WsDeliveryCommand` | record | MQ 消息体：投递目标 + payload |

### 2.5 改造组件总览

| 组件 | 改动摘要 |
|------|---------|
| `AgentConnectionRegistry` | `register/unregister` 同步写/删 Redis presence |
| `ChatWebSocketHandler` | 访客连接维护 presence；`notifyVisitor` 走 `WsMessageRouter` |
| `AgentChannelWsHandler` | KICK 锁换 Redisson `RLock`；KICK 广播走 `WsMessageRouter` |
| `ChatWebSocketHandler.notifyAgent` | 走 `WsMessageRouter` 路由，不直接 broadcast |
| `PersistHandler` | 分布式锁换 Redisson `RLock`（修复 TTL 数据安全）|
| `DocExpiryScheduler` | 分布式锁换 Redisson `RLock` |
| `AgentOnlineRegistry` | 新增 podId 字段，崩溃恢复时按 Pod 清理 |
| `IdGenerator` | 多 Pod 时强制要求 `WORKER_ID` 环境变量 |

## 3. Pod 身份标识方案

### 3.1 设计思路

Pod 身份标识（podId）需要满足：
- 每个 Pod 唯一，不依赖外部配置（无需运维手动设置）
- Pod 停止后标识自动失效，不留脏数据
- 其他 Pod 能通过标识向目标 Pod 投递消息

**复用 RabbitMQ 匿名队列名作为 podId**，与 `SessionEventSubscriber` 的 SSE 广播使用相同机制。

RabbitMQ 为每个 `exclusive + autoDelete` 队列分配形如 `amq.gen-xxxxxxxxxxxxxxxx` 的唯一名称，Pod 断开连接时队列自动删除。

### 3.2 PodIdentity 组件

```java
// infrastructure/ws/cluster/PodIdentity.java
@Slf4j
@Component
public class PodIdentity implements InitializingBean {

    private final RabbitAdmin rabbitAdmin;
    private volatile String podId;

    public PodIdentity(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public void afterPropertiesSet() {
        // 声明匿名 exclusive+autoDelete 队列，队列名即 podId
        Queue queue = new AnonymousQueue();
        rabbitAdmin.declareQueue(queue);
        this.podId = queue.getName();   // e.g. "spring.gen-abc123" 或 "amq.gen-xxx"
        log.info("[Cluster] Pod 身份初始化完成 podId={}", podId);
    }

    /** 当前 Pod 的唯一标识（RabbitMQ 队列名） */
    public String get() {
        return podId;
    }

    /** 是否是本 Pod 的队列 */
    public boolean isLocal(String targetPodId) {
        return podId.equals(targetPodId);
    }
}
```

### 3.3 WS 投递队列声明

`PodIdentity` 初始化完成后，`WsDeliveryConsumer` 使用这个 podId 绑定到 `ws.delivery` Direct Exchange，用于接收发给本 Pod 的跨 Pod WS 投递消息：

```
Exchange：ws.delivery（Direct，durable=true）
Queue：   {podId}（exclusive=true，autoDelete=true）
RoutingKey：{podId}
```

这样发送方只需：
```java
rabbitTemplate.convertAndSend("ws.delivery", targetPodId, deliveryCommand);
```

### 3.4 WORKER_ID 多 Pod 配置要求

`IdGenerator` 当前在未设置 `WORKER_ID` 环境变量时回退到 `PID % 1024`，多 Pod 下可能产生 Snowflake ID 碰撞。

**部署要求**：每个 Pod 必须通过环境变量注入唯一的 `WORKER_ID`（0–1023）：

```yaml
# Kubernetes Deployment
env:
  - name: WORKER_ID
    valueFrom:
      fieldRef:
        fieldPath: metadata.annotations['pod-worker-id']
```

或通过 StatefulSet 序号自动派生：
```yaml
  - name: WORKER_ID
    value: "$(POD_INDEX)"   # StatefulSet 自动注入 POD_INDEX
```

Docker Compose 单机部署时固定设置 `WORKER_ID=0` 即可（单实例无碰撞风险）。

## 4. Presence 注册表设计

### 4.1 Redis 数据结构

```
# 座席 presence：agentId → podId（String，带 TTL）
ws:agent:pod:{agentId}    →  "amq.gen-BBB"    TTL: 90s

# 访客 presence：sessionId → podId（String，带 TTL）
ws:visitor:pod:{sessionId} →  "amq.gen-AAA"   TTL: 90s

# TTL 心跳：连接存活期间每 30s 刷新一次 TTL
# 连接断开时主动删除 key
# Pod 崩溃时 TTL 自动过期（最多 90s 残留）
```

说明：
- 使用 `String` 而非 `Set`，因为新架构下每个 agentId 只有一条「当前活跃」连接（KICK 模式）或同一 Pod 上的多条连接（BROADCAST 模式）
- BROADCAST 模式下同一座席多端可能在不同 Pod，使用 `Set<String>` 存储多个 podId

```
# BROADCAST 模式（座席多端跨 Pod）
ws:agent:pods:{agentId}   →  Set{"amq.gen-AAA", "amq.gen-BBB"}  TTL: 90s
```

### 4.2 WsPresenceRegistry 组件

```java
// infrastructure/ws/cluster/WsPresenceRegistry.java
@Component
@RequiredArgsConstructor
public class WsPresenceRegistry {

    private static final String VISITOR_KEY  = "ws:visitor:pod:";
    private static final String AGENT_KEY    = "ws:agent:pods:";
    private static final Duration TTL        = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;

    /** 访客连接建立：记录 sessionId → podId */
    public void registerVisitor(String sessionId, String podId) {
        redis.opsForValue().set(VISITOR_KEY + sessionId, podId, TTL);
    }

    /** 访客连接断开：删除 presence */
    public void unregisterVisitor(String sessionId) {
        redis.delete(VISITOR_KEY + sessionId);
    }

    /** 查询访客所在 podId */
    public String getVisitorPod(String sessionId) {
        return redis.opsForValue().get(VISITOR_KEY + sessionId);
    }

    /** 座席连接建立：将 podId 加入 agentId 的 podId 集合 */
    public void registerAgent(String agentId, String podId) {
        redis.opsForSet().add(AGENT_KEY + agentId, podId);
        redis.expire(AGENT_KEY + agentId, TTL);
    }

    /** 座席连接断开：从集合中移除 podId（本 Pod 无此 agentId 其他连接时） */
    public void unregisterAgent(String agentId, String podId) {
        redis.opsForSet().remove(AGENT_KEY + agentId, podId);
        // 集合为空时自动过期，无需手动删除
    }

    /** 查询座席所在的所有 podId */
    public Set<String> getAgentPods(String agentId) {
        Set<String> members = redis.opsForSet().members(AGENT_KEY + agentId);
        return members != null ? members : Collections.emptySet();
    }

    /** 刷新 TTL（心跳调用，防止 presence 过期） */
    public void refreshVisitor(String sessionId) {
        redis.expire(VISITOR_KEY + sessionId, TTL);
    }

    public void refreshAgent(String agentId) {
        redis.expire(AGENT_KEY + agentId, TTL);
    }
}
```

### 4.3 TTL 心跳刷新

WS 连接建立后启动心跳任务，每 30s 刷新一次 TTL，确保 presence 不过期：

```java
// AgentConnectionRegistry.register() 中启动心跳
ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
    () -> presenceRegistry.refreshAgent(agentId),
    30, 30, TimeUnit.SECONDS);
// 存入 heartbeatMap<wsSessionId, ScheduledFuture>
// unregister() 时 cancel
```

### 4.4 崩溃恢复流程

```
Pod B 崩溃：
  1. RabbitMQ 检测到连接断开 → 删除 amq.gen-BBB 队列
  2. Redis ws:agent:pods:agent-001 中的 "amq.gen-BBB" 在 TTL 到期前仍存在
  3. 其他 Pod 尝试向 amq.gen-BBB 发消息 → broker 找不到队列 → 消息被丢弃（auto-delete）

座席客户端重连：
  1. 重连到任意 Pod（如 Pod C）
  2. Pod C 执行 presenceRegistry.registerAgent("agent-001", "amq.gen-CCC")
  3. Redis 集合更新，后续消息正确路由到 Pod C

访客侧同理：重连时新 Pod 覆盖旧 presence key。
```

**TTL 窗口期（90s）内的消息处理**：
- 访客消息先写入 `ConversationHistoryRepository`（Redis List + DB），不丢失
- WS 实时推送失败（队列不存在）是可接受的降级：座席重连后通过 `sinceSeq` 拉取增量补齐

## 5. 跨 Pod 路由与消费

### 5.1 WsDeliveryCommand（MQ 消息体）

```java
// infrastructure/ws/cluster/WsDeliveryCommand.java
/**
 * 跨 Pod WS 投递命令，序列化为 JSON 后通过 RabbitMQ 传递。
 *
 * @param targetType  投递目标类型：AGENT 或 VISITOR
 * @param targetId    投递目标 ID（agentId 或 sessionId）
 * @param payload     原始消息对象（已是类型化消息 record，如 WsChatMessage）
 */
public record WsDeliveryCommand(
        TargetType targetType,
        String targetId,
        Object payload
) {
    public enum TargetType { AGENT, VISITOR }

    public static WsDeliveryCommand toAgent(String agentId, Object payload) {
        return new WsDeliveryCommand(TargetType.AGENT, agentId, payload);
    }

    public static WsDeliveryCommand toVisitor(String sessionId, Object payload) {
        return new WsDeliveryCommand(TargetType.VISITOR, sessionId, payload);
    }
}
```

### 5.2 WsMessageRouter（路由决策）

```java
// infrastructure/ws/cluster/WsMessageRouter.java
@Slf4j
@Component
@RequiredArgsConstructor
public class WsMessageRouter {

    private static final String WS_DELIVERY_EXCHANGE = "ws.delivery";

    private final PodIdentity podIdentity;
    private final WsPresenceRegistry presenceRegistry;
    private final AgentConnectionRegistry agentRegistry;
    private final ChatWebSocketHandler chatHandler;   // 通过 VisitorNotifier 接口
    private final RabbitTemplate rabbitTemplate;

    /**
     * 向座席推送消息。
     * 若座席 WS 在本 Pod，直接推送；否则发 MQ 到目标 Pod 队列。
     */
    public void sendToAgent(String agentId, Object payload) {
        Set<String> pods = presenceRegistry.getAgentPods(agentId);
        if (pods.isEmpty()) {
            log.warn("[WsRouter] 座席不在线 agentId={}", agentId);
            return;
        }
        for (String pod : pods) {
            if (podIdentity.isLocal(pod)) {
                agentRegistry.broadcast(agentId, payload);
            } else {
                deliver(pod, WsDeliveryCommand.toAgent(agentId, payload));
            }
        }
    }

    /**
     * 向访客推送消息。
     * 若访客 WS 在本 Pod，直接推送；否则发 MQ 到目标 Pod 队列。
     */
    public void sendToVisitor(String sessionId, Object payload) {
        String pod = presenceRegistry.getVisitorPod(sessionId);
        if (pod == null) {
            log.warn("[WsRouter] 访客不在线 sessionId={}", sessionId);
            return;
        }
        if (podIdentity.isLocal(pod)) {
            chatHandler.notifyVisitor(sessionId, payload);
        } else {
            deliver(pod, WsDeliveryCommand.toVisitor(sessionId, payload));
        }
    }

    /** 发到目标 Pod 的 RabbitMQ 队列（Direct Exchange，routingKey = podId） */
    private void deliver(String targetPod, WsDeliveryCommand cmd) {
        try {
            rabbitTemplate.convertAndSend(WS_DELIVERY_EXCHANGE, targetPod, cmd);
            log.debug("[WsRouter] 跨 Pod 投递 targetPod={} type={} id={}",
                    targetPod, cmd.targetType(), cmd.targetId());
        } catch (Exception e) {
            log.warn("[WsRouter] MQ 投递失败 targetPod={} msg={}", targetPod, e.getMessage());
        }
    }
}
```

### 5.3 WsDeliveryConsumer（本 Pod 消费）

```java
// infrastructure/ws/cluster/WsDeliveryConsumer.java
@Slf4j
@Component
@RequiredArgsConstructor
public class WsDeliveryConsumer implements InitializingBean {

    private static final String WS_DELIVERY_EXCHANGE = "ws.delivery";

    private final PodIdentity podIdentity;
    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier visitorNotifier;
    private final RabbitAdmin rabbitAdmin;
    private final SimpleMessageListenerContainer container;

    @Override
    public void afterPropertiesSet() {
        // podId 初始化后才能注册队列，PodIdentity 的 afterPropertiesSet 先执行
        String podId = podIdentity.get();

        // 将本 Pod 专属队列绑定到 ws.delivery Direct Exchange
        Binding binding = BindingBuilder
                .bind(new Queue(podId, false, true, true))  // exclusive, autoDelete
                .to(new DirectExchange(WS_DELIVERY_EXCHANGE))
                .with(podId);
        rabbitAdmin.declareBinding(binding);

        // 动态注册监听器
        container.addQueueNames(podId);
        log.info("[WsDelivery] 本 Pod 投递队列已绑定 podId={}", podId);
    }

    /** 处理跨 Pod 投递的 WS 消息 */
    public void onDelivery(WsDeliveryCommand cmd) {
        switch (cmd.targetType()) {
            case AGENT   -> agentRegistry.broadcast(cmd.targetId(), cmd.payload());
            case VISITOR -> visitorNotifier.notifyVisitor(cmd.targetId(), cmd.payload());
        }
        log.debug("[WsDelivery] 本地推送完成 type={} id={}", cmd.targetType(), cmd.targetId());
    }
}
```

### 5.4 RabbitMQ 配置补充

```java
// infrastructure/config/RabbitMQConfig.java 新增
@Bean
public DirectExchange wsDeliveryExchange() {
    // durable=true 保证 broker 重启后 exchange 仍存在
    // 队列是 autoDelete，broker 重启时会重新声明
    return new DirectExchange("ws.delivery", true, false);
}
```

### 5.5 调用链路全景（改造后）

```
访客在 Pod A 发消息 → 座席 WS 在 Pod B

Pod A.ChatWebSocketHandler.handleVisitorMessage
  → historyRepository.append(sessionId, content)  // 写历史，不丢消息
  → notifyAgent(sessionId, WsChatMessage.fromVisitor(...))
      → WsMessageRouter.sendToAgent("agent-001", msg)
          → presenceRegistry.getAgentPods("agent-001") → {"amq.gen-BBB"}
          → amq.gen-BBB ≠ 本机 → rabbitTemplate.send("ws.delivery", "amq.gen-BBB", cmd)
                                          │
                    RabbitMQ Direct ──────┘
                                          │
Pod B.WsDeliveryConsumer.onDelivery(cmd) ◀┘
  → agentRegistry.broadcast("agent-001", msg)
  → WsSession.sendMessage()  ✓
```

## 6. KICK 模式分布式改造

### 6.1 单机 KICK 的问题

当前 KICK 模式使用 `synchronized(registry.getAgentLock(agentId))` 本地锁，三步操作在单 Pod 内是原子的：

```java
synchronized (registry.getAgentLock(agentId)) {
    registry.register(agentId, newSession);
    registry.broadcastExcept(agentId, newSession, WsKickedOutMessage.INSTANCE);
    registry.closeAllExcept(agentId, newSession);
}
```

**多 Pod 场景的问题**：

```
Pod A 收到 agent-001 的新连接（conn-1）
Pod B 收到 agent-001 的新连接（conn-2）

时序：
  Pod A: synchronized(localLock) { register(conn-1); kick旧连接; }
  Pod B: synchronized(localLock) { register(conn-2); kick旧连接; }

结果：
  - Pod A 踢了 Pod A 上的旧连接 ✓
  - Pod B 踢了 Pod B 上的旧连接 ✓
  - 但 Pod A 上的 conn-1 不知道 Pod B 上的 conn-2 存在，反之亦然
  - 最终：agent-001 同时有 conn-1（Pod A）和 conn-2（Pod B）在线 ✗
```

### 6.2 引入 Redisson RLock

```java
// AgentChannelWsHandler.afterConnectionEstablished (KICK 模式)
// 改造前
synchronized (registry.getAgentLock(agentId)) {
    registry.register(agentId, session);
    registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
    registry.closeAllExcept(agentId, session);
}

// 改造后
RLock lock = redissonClient.getLock("ws:kick:agent:" + agentId);
boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);  // 等待3s，持有10s
if (!acquired) {
    log.warn("[AgentWS] KICK 锁获取超时，拒绝连接 agentId={}", agentId);
    session.close(CloseStatus.SERVICE_OVERLOAD);
    return;
}
try {
    // ① 注册新连接到本 Pod
    registry.register(agentId, session);
    presenceRegistry.registerAgent(agentId, podIdentity.get());

    // ② 向所有 Pod 上的旧连接推送 KICKED_OUT（含本 Pod 和跨 Pod）
    Set<String> allPods = presenceRegistry.getAgentPods(agentId);
    for (String pod : allPods) {
        if (podIdentity.isLocal(pod)) {
            registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
        } else {
            router.sendKickToPod(pod, agentId, session.getId());
        }
    }

    // ③ 关闭本 Pod 上的旧连接
    registry.closeAllExcept(agentId, session);
    // 跨 Pod 旧连接由各 Pod 的 WsDeliveryConsumer 处理 KICK 命令后关闭

} finally {
    lock.unlock();
}
```

### 6.3 跨 Pod KICK 命令

扩展 `WsDeliveryCommand` 支持 KICK 操作：

```java
public record WsDeliveryCommand(
        TargetType targetType,
        String targetId,
        Object payload,
        String excludeWsSessionId   // KICK 时排除的新连接 wsSessionId（可为 null）
) {
    public enum TargetType { AGENT, VISITOR, KICK_AGENT }

    public static WsDeliveryCommand kickAgent(String agentId, String excludeWsSessionId) {
        return new WsDeliveryCommand(TargetType.KICK_AGENT, agentId, null, excludeWsSessionId);
    }
}
```

`WsDeliveryConsumer.onDelivery` 处理 KICK：

```java
case KICK_AGENT -> {
    // 向旧连接推 KICKED_OUT（排除新连接）
    registry.broadcastExcept(cmd.targetId(), findLocalSession(cmd.excludeWsSessionId()),
            WsKickedOutMessage.INSTANCE);
    // 关闭旧连接
    registry.closeAllExcept(cmd.targetId(), findLocalSession(cmd.excludeWsSessionId()));
}
```

### 6.4 KICK 模式时序图（多 Pod）

```
          Redisson Redis          Pod A              Pod B
               │                   │                  │
 agent-001 新连接到 Pod A           │                  │
               │                   │                  │
         tryLock("ws:kick:agent-001")                 │
         ◀─────────────────────────┤                  │
         acquired ─────────────────▶                  │
               │            register(conn-new)         │
               │      presenceRegistry.register        │
               │        → Redis: agent-001={A,B}       │
               │                   │                  │
               │         向 Pod B 发 KICK 命令          │
               │         ──────────────────────────▶  │
               │                   │      Pod B 推 KICKED_OUT 给旧连接
               │                   │      Pod B 关闭旧连接
               │         本 Pod 关闭旧连接              │
               │                   │                  │
         unlock ◀──────────────────┤                  │
               │                   │                  │
```

### 6.5 BROADCAST 模式不受影响

BROADCAST 模式无 KICK 逻辑，`register` 和 `presence.registerAgent` 直接执行，无需 Redisson 锁。多 Pod 下多端在线是预期行为，`WsMessageRouter.sendToAgent` 会遍历所有 pods 广播。

## 7. Redisson 锁替换

### 7.1 引入 Redisson 依赖

在 `ai-conversation/conversation-service/pom.xml` 和 `ai-knowledge/knowledge-service/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

Redisson Spring Boot Starter 会自动读取 `spring.data.redis` 配置，无需额外配置文件。

### 7.2 PersistHandler — 修复 TTL 数据安全问题（高优先级）

**问题**：当前 SETNX 锁 TTL=5 分钟，大文档向量写入若超过 5 分钟，锁过期，另一个消费者开始并发 `deleteByDocId + saveAll`，两个消费者同时写同一文档数据，导致 chunk 数据损坏。

**改造前**：
```java
boolean locked = lockHelper.tryLock(lockKey, owner, LOCK_TTL);  // TTL=5min，到期自动释放
try {
    chunkRepository.deleteByDocId(docId);
    chunkRepository.saveAll(ctx.getChunks());
} finally {
    lockHelper.unlock(lockKey, owner);
}
```

**改造后**：
```java
RLock lock = redissonClient.getLock("lock:ingest:persist:" + docId);
// watchdog 模式：不设 leaseTime，Redisson 每 10s 自动续期，持有锁直到主动释放
boolean acquired = lock.tryLock(10, TimeUnit.SECONDS);
if (!acquired) {
    log.warn("[PersistHandler] 获取锁失败，跳过重复消费 docId={}", docId);
    return;
}
try {
    chunkRepository.deleteByDocId(docId);
    chunkRepository.saveAll(ctx.getChunks());
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

关键点：`tryLock(waitTime)` 不传 `leaseTime` 时启用 watchdog，每 `lockWatchdogTimeout/3`（默认 10s）自动续期，直到 `unlock()` 主动释放，彻底消除 TTL 过期竞态。

### 7.3 DocExpiryScheduler — 防重复执行

**改造前**：
```java
if (!lockHelper.tryLock(LOCK_KEY, owner, LOCK_TTL)) { return; }
try {
    docExpiryService.deprecateExpired(LocalDate.now());
} finally {
    lockHelper.unlock(LOCK_KEY, owner);
}
```

**改造后**：
```java
RLock lock = redissonClient.getLock("lock:scheduler:doc-expiry");
boolean acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);  // 不等待，持有 10 分钟
if (!acquired) {
    log.debug("[DocExpiry] 其他节点正在执行，跳过 date={}", LocalDate.now());
    return;
}
try {
    docExpiryService.deprecateExpired(LocalDate.now());
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

此处使用固定 `leaseTime=10min`（非 watchdog），因为定时任务有明确的预期执行时间上界，不需要 watchdog 续期。

### 7.4 AgentOnlineRegistry — 引用计数按 Pod 隔离

**现状问题**：`deregister(agentId)` 对全局引用计数 `-1`，Pod 崩溃时无法执行，计数残留：

```
Pod A 崩溃前：agent-001 count=2（Pod A 有 2 个 SSE 连接）
Pod A 崩溃后：count 仍是 2，agent-001 错误显示在线
仅 12h TTL 兜底，期间座席界面状态错误
```

**改造方案**：新增按 Pod 注册的字段，重启时清理本 Pod 贡献的计数：

```
# 新增 Redis key
agent:online:pod:{podId}   →  Set{ agentId1, agentId2, ... }  TTL: 90s（心跳刷新）
```

Pod 启动时：扫描上次本 podId 的遗留记录（如果有），执行清理。
Pod 正常关闭时：`@PreDestroy` 清理所有本 Pod 的 agent presence。

```java
@PreDestroy
public void cleanup() {
    String podId = podIdentity.get();
    Set<String> myAgents = redis.opsForSet().members("agent:online:pod:" + podId);
    if (myAgents != null) {
        myAgents.forEach(agentId -> agentRegistry.deregister(agentId));
    }
    log.info("[AgentOnline] Pod 下线清理完成 podId={} count={}", podId,
            myAgents != null ? myAgents.size() : 0);
}
```

### 7.5 不替换的锁

| 位置 | 原因 |
|------|------|
| `AgentConnectionRegistry.sendJson` synchronized | 保护本地 TCP socket 写帧，分布式锁只增加延迟 |
| `ChatWebSocketHandler.sendJson` synchronized | 同上，访客侧 |
| `IdGenerator` static synchronized | 单机 Snowflake 时序，跨 Pod 通过 WORKER_ID 分区 |
| `SessionQueueRepository` Lua CAS | 已是 Redis 原子操作，无需额外锁 |
| `ConversationHistoryRepository` Lua INCR | 同上 |

## 8. 文件改动清单

### 8.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| `infrastructure/ws/cluster/PodIdentity.java` | Pod 身份标识，持有 RabbitMQ 匿名队列名 |
| `infrastructure/ws/cluster/WsPresenceRegistry.java` | Redis presence 注册表封装 |
| `infrastructure/ws/cluster/WsDeliveryCommand.java` | MQ 投递命令 record |
| `infrastructure/ws/cluster/WsMessageRouter.java` | 跨 Pod 路由决策组件 |
| `infrastructure/ws/cluster/WsDeliveryConsumer.java` | 本 Pod 专属队列消费者 |

### 8.2 改造文件

**AgentConnectionRegistry.java**

```diff
+ private final WsPresenceRegistry presenceRegistry;
+ private final PodIdentity podIdentity;
+ private final ScheduledExecutorService heartbeatScheduler;
+ private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats;

  public void register(String agentId, WebSocketSession session) {
      agentToSessions.computeIfAbsent(agentId, k -> new CopyOnWriteArraySet<>()).add(session);
      sessionIdToAgentId.put(session.getId(), agentId);
+     presenceRegistry.registerAgent(agentId, podIdentity.get());
+     // 启动 presence 心跳刷新（30s 间隔）
+     scheduleHeartbeat(agentId, session.getId());
  }

  public void unregister(WebSocketSession session) {
      String agentId = sessionIdToAgentId.remove(session.getId());
      ...
+     // 若本 Pod 上该 agentId 无其他连接，从 presence 移除本 Pod
+     if (noMoreLocalSessions(agentId)) {
+         presenceRegistry.unregisterAgent(agentId, podIdentity.get());
+     }
+     cancelHeartbeat(session.getId());
  }
```

**AgentChannelWsHandler.java**

```diff
+ private final RedissonClient redissonClient;
+ private final WsPresenceRegistry presenceRegistry;
+ private final PodIdentity podIdentity;
+ private final WsMessageRouter router;

  // KICK 模式：本地 synchronized 换 Redisson RLock
- synchronized (registry.getAgentLock(agentId)) {
-     registry.register(agentId, session);
-     registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
-     registry.closeAllExcept(agentId, session);
- }
+ RLock lock = redissonClient.getLock("ws:kick:agent:" + agentId);
+ boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
+ if (!acquired) { session.close(CloseStatus.SERVICE_OVERLOAD); return; }
+ try {
+     registry.register(agentId, session);
+     presenceRegistry.registerAgent(agentId, podIdentity.get());
+     kickAllExcept(agentId, session);   // 本 Pod + 跨 Pod
+ } finally { lock.unlock(); }

  // 移除 agentLocks map（不再需要）
- private final ConcurrentHashMap<String, Object> agentLocks;
- public Object getAgentLock(String agentId) { ... }
```

**ChatWebSocketHandler.java**

```diff
+ private final WsPresenceRegistry presenceRegistry;
+ private final PodIdentity podIdentity;
+ private final WsMessageRouter router;

  // 访客连接建立：新增 presence 注册
  if (PATH_SEGMENT_CHAT.equals(role)) {
      visitorSessions.put(sessionId, session);
+     presenceRegistry.registerVisitor(sessionId, podIdentity.get());
      sendJson(session, WsConnectedMessage.forVisitor(sessionId));
  }

  // 访客断开：清理 presence
  if (PATH_SEGMENT_CHAT.equals(role)) {
      visitorSessions.remove(sessionId);
+     presenceRegistry.unregisterVisitor(sessionId);
  }

  // notifyAgent：走 WsMessageRouter
  public void notifyAgent(String sessionId, Object payload) {
      if (payload instanceof WsTypingMessage) { return; }
      String agentId = sessionQueueService.getAgentId(sessionId);
      if (agentId == null) { log.warn(...); return; }
-     agentConnectionRegistry.broadcast(agentId, payload);
+     router.sendToAgent(agentId, payload);
  }

  // notifyVisitor：走 WsMessageRouter（跨 Pod 访客推送）
  @Override
  public void notifyVisitor(String sessionId, Object payload) {
-     WebSocketSession vs = visitorSessions.get(sessionId);
-     if (vs == null || !vs.isOpen()) { log.warn(...); return; }
-     sendJson(vs, payload);
+     router.sendToVisitor(sessionId, payload);
  }
```

**RabbitMQConfig.java**

```diff
+ @Bean
+ public DirectExchange wsDeliveryExchange() {
+     return new DirectExchange("ws.delivery", true, false);
+ }
```

**PersistHandler.java**（knowledge-service）

```diff
- boolean locked = lockHelper.tryLock(lockKey, owner, LOCK_TTL);
+ RLock lock = redissonClient.getLock("lock:ingest:persist:" + docId);
+ boolean acquired = lock.tryLock(10, TimeUnit.SECONDS);  // watchdog 模式
```

**DocExpiryScheduler.java**（knowledge-service）

```diff
- if (!lockHelper.tryLock(LOCK_KEY, owner, LOCK_TTL)) { return; }
+ RLock lock = redissonClient.getLock("lock:scheduler:doc-expiry");
+ boolean acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
```

### 8.3 不变文件

- `AgentHandshakeInterceptor.java` — 鉴权逻辑不变
- `WebSocketConfig.java` — 端点注册不变
- `SessionEventSubscriber.java` — SSE fanout 天然支持多 Pod，不变
- `SessionQueueRepository.java` — Lua CAS 已是分布式安全，不变
- `ConversationHistoryRepository.java` — Lua INCR 已是分布式安全，不变
- `application.yml` — 新增 `spring.data.redis` 配置供 Redisson 读取（已有）

### 8.4 Redis Key 汇总

| Key 模式 | 类型 | TTL | 含义 |
|---------|------|-----|------|
| `ws:visitor:pod:{sessionId}` | String | 90s | 访客所在 podId |
| `ws:agent:pods:{agentId}` | Set | 90s | 座席所在 podId 集合 |
| `ws:kick:agent:{agentId}` | Redisson RLock | 10s（watchdog续期） | KICK 模式分布式锁 |
| `lock:ingest:persist:{docId}` | Redisson RLock | watchdog | PersistHandler 幂等锁 |
| `lock:scheduler:doc-expiry` | Redisson RLock | 10min | 定时任务排它锁 |

## 9. 部署配置

### 9.1 Docker Compose（单机，兼容现有）

```yaml
# deploy/docker-compose.yml 新增环境变量
conversation-service:
  environment:
    - WORKER_ID=0          # 单机固定为 0，多机时需要不同值
    # Redis 和 RabbitMQ 配置已有，Redisson 自动读取 spring.data.redis
```

### 9.2 Kubernetes 多 Pod 部署

```yaml
# k8s/conversation-service-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: conversation-service
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: conversation-service
          image: conversation-service:latest
          env:
            # Pod 序号作为 WORKER_ID（需配合 StatefulSet 或 Downward API）
            - name: POD_INDEX
              valueFrom:
                fieldRef:
                  fieldPath: metadata.labels['apps.kubernetes.io/pod-index']
            - name: WORKER_ID
              value: "$(POD_INDEX)"
            # 其余环境变量通过 ConfigMap/Secret 注入
            - name: REDIS_HOST
              valueFrom:
                configMapKeyRef:
                  name: app-config
                  key: redis.host
          ports:
            - containerPort: 8082
          # 优雅关闭：让 Pod 有时间完成 @PreDestroy 清理
          lifecycle:
            preStop:
              exec:
                command: ["sleep", "10"]
          terminationGracePeriodSeconds: 30
```

### 9.3 负载均衡配置

WebSocket 连接建立后是长连接，无需 sticky session（与 HTTP 会话不同）。负载均衡器对 WebSocket 升级握手（HTTP Upgrade）做标准转发即可：

```nginx
# nginx 配置
upstream conversation_ws {
    server pod-a:8082;
    server pod-b:8082;
    server pod-c:8082;
    # 不需要 ip_hash，无需粘性会话
}

server {
    location /ws/ {
        proxy_pass http://conversation_ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;   # WS 长连接超时设长
        proxy_send_timeout 3600s;
    }
}
```

### 9.4 优雅关闭流程

```
收到 SIGTERM（K8s rolling update 或手动停止）：

1. Spring 触发 @PreDestroy（10s 内完成）
   ├─ AgentOnlineRegistry.cleanup()：从 Redis 清除本 Pod 的 agent online 记录
   ├─ WsPresenceRegistry：Redis TTL 自动处理，无需手动清理
   └─ WsDeliveryConsumer：RabbitMQ auto-delete 队列连接断开后自动删除

2. 新连接不再路由到本 Pod（K8s 从 Service endpoints 摘除）

3. 现有 WS 连接：
   ├─ 访客/座席客户端感知到断线后自动重连到其他 Pod
   └─ 重连后重新写入 presence，消息路由恢复

4. Pod 停止
   └─ RabbitMQ ws.delivery/{podId} 队列自动删除
```

### 9.5 监控建议

```yaml
# 建议监控的 Redis key 指标
ws:visitor:pod:*    # 在线访客数（key count）
ws:agent:pods:*     # 在线座席数（key count）

# 建议监控的 RabbitMQ 队列指标
ws.delivery.*       # 各 Pod 投递队列的消息积压
cs.conversation.persist  # 持久化队列消息积压
```

### 9.6 改造阶段建议

| 阶段 | 内容 | 依赖 | 风险 |
|------|------|------|------|
| **阶段 1**（独立，随时可做） | Redisson 替换 `PersistHandler` 和 `DocExpiryScheduler` | 引入 Redisson 依赖 | 低，修复存量 bug |
| **阶段 2**（多机前置） | `PodIdentity` + `WsPresenceRegistry` + presence 维护逻辑 | 阶段 1 完成 | 中，新增 Redis key 无副作用 |
| **阶段 3**（多机核心） | `WsMessageRouter` + `WsDeliveryConsumer` + `WsDeliveryCommand` + RabbitMQ exchange | 阶段 2 完成 | 高，消息路由全量切换 |
| **阶段 4**（KICK 多机） | KICK 模式换 Redisson RLock + 跨 Pod KICK 命令 | 阶段 3 完成 | 中，仅影响 KICK 模式用户 |

阶段 1 和 2 可以在生产单机环境下先上线（功能等价，无破坏性），阶段 3 上线时启动多 Pod 即可验证跨机路由。
