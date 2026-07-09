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
| `ChatWebSocketHandler` | 访客连接维护 presence；`notifyVisitor` **保持本地直推不变**（作为 `VisitorNotifier` 接口实现，不走路由层，防无限递归） |
| `AgentChannelWsHandler` | KICK 锁换 Redisson `RLock`；座席回复/TYPING 改调 `router.sendToVisitor()`（**跨 Pod 访客推送的入口**） |
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

    /** 座席连接建立：将 podId 加入 agentId 的 podId 集合
     *
     * <p>⚠️ 原子性说明：SADD + EXPIRE 是两条独立 Redis 命令，非原子操作。
     * 若 Pod 在两步之间崩溃，或 EXPIRE 因网络抖动失败，key 将永久没有 TTL 导致内存泄漏。
     * 实现时应使用 Lua 脚本合并两步操作：
     * <pre>
     * local added = redis.call('SADD', KEYS[1], ARGV[1])
     * redis.call('EXPIRE', KEYS[1], ARGV[2])
     * return added
     * </pre>
     * 或使用 {@code StringRedisTemplate.executePipelined()} 流水线发送（减少网络往返，
     * 但严格来说非原子；Lua 脚本是唯一真正原子的方案）。
     */
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

- 访客消息先写入 `ConversationHistoryRepository`（Redis List + DB），**消息数据不丢失**
- WS 实时推送失败（队列不存在被 broker 静默丢弃）是一种降级：座席重连后通过 `sinceSeq` 拉取增量补齐
- 座席浏览器感知到 WS 断线后（约 30-60s）会自动重连，重连后拉取历史补全消息

**影响时间线**：
```
Pod B 崩溃
  │
  ├── 0s：崩溃，RabbitMQ 队列自动删除
  ├── 0-60s：浏览器 TCP 超时 / WS ping-pong 失败，座席感知断线
  │          期间其他 Pod 往 amq.gen-BBB 发消息 → 静默丢弃（实时推送中断）
  │          但所有访客消息已写入历史存储，数据完整
  ├── 60s：座席浏览器自动重连到存活 Pod
  └── 重连后：携带 sinceSeq，拉取窗口期内所有未推送消息 → 补全 ✓
```

### 4.5 推荐优化：mandatory flag 即时清理死 Pod（暂不实现）

上述方案最长有 90s 的实时推送中断窗口。可通过 RabbitMQ `mandatory` 标志将窗口压缩到接近 0：

```java
// WsMessageRouter.deliver() 的进阶版本（供参考，暂不实现）
rabbitTemplate.setMandatory(true);
rabbitTemplate.setReturnsCallback(returned -> {
    // 消息无法路由 = 目标队列（Pod）已不存在
    String deadPodId = returned.getRoutingKey();
    log.warn("[WsRouter] 投递失败，Pod 已下线，立即清理 presence podId={}", deadPodId);

    // 立即从所有 presence key 中清除死 Pod
    presenceRegistry.removeStalePod(deadPodId);
    // 之后的消息不再路由到该 Pod，推送中断窗口压缩到第一条失败时触发
});
```

`WsPresenceRegistry.removeStalePod(deadPodId)` 实现：
```java
public void removeStalePod(String deadPodId) {
    // 1. 扫描所有座席 presence，移除死 Pod
    //    SCAN ws:agent:pods:* → SREM deadPodId
    // 2. 扫描所有访客 presence，删除值为死 Pod 的 key
    //    SCAN ws:visitor:pod:* → GET value == deadPodId → DEL
}
```

**为何暂不实现**：
- `SCAN` 操作在连接数大时有性能代价，需要评估实际规模
- mandatory 模式下需要保证 `ConfirmCallback` 和 `ReturnsCallback` 的消息顺序处理
- 当前单机部署无此需求，在多机上线压测后再决定是否引入

**当前代码中已在 `deliver()` 方法注释中标注此 TODO**（见 §5.2）。

## 5. 跨 Pod 路由与消费

### 5.1 WsDeliveryCommand（MQ 消息体）

```java
// infrastructure/ws/cluster/WsDeliveryCommand.java
/**
 * 跨 Pod WS 投递命令，序列化为 JSON 后通过 RabbitMQ 传递。
 *
 * <p>payload 序列化说明：{@code Object payload} 经 Jackson 反序列化后类型擦除为 {@link java.util.LinkedHashMap}，
 * 无法还原为原始 record 类型。因此使用 {@code wsMessageType + payloadJson} 两字段替代：
 * 发送端预序列化，消费端按 {@link WsMessageType} 枚举 switch 还原，彻底消除类型擦除问题。
 *
 * <p>使用枚举而非完整类名的原因：
 * <ul>
 *   <li>重构安全：包名变更不影响已在途的 MQ 消息</li>
 *   <li>类型安全：消费端 switch 为编译期穷举，新增消息类型时有编译提示</li>
 *   <li>安全防护：枚举白名单，不存在 Class.forName 加载任意类的风险</li>
 * </ul>
 *
 * @param targetType          投递目标类型：AGENT / VISITOR / KICK_AGENT
 * @param targetId            投递目标 ID（agentId 或 sessionId）
 * @param wsMessageType       消息类型枚举（用于消费端 switch 还原，KICK_AGENT 时为 null）
 * @param payloadJson         payload 的 JSON 字符串（消费端按 wsMessageType 还原，KICK_AGENT 时为 null）
 * @param excludeWsSessionId  KICK_AGENT 时记录新连接的 wsSessionId（仅供日志，可为 null）
 */
public record WsDeliveryCommand(
        TargetType targetType,
        String targetId,
        WsMessageType wsMessageType,
        String payloadJson,
        String excludeWsSessionId
) {
    public enum TargetType { AGENT, VISITOR, KICK_AGENT }

    /**
     * 向座席投递消息。
     * 序列化失败时抛 {@link IllegalStateException}（编程错误，不应让调用方处理受检异常）。
     */
    public static WsDeliveryCommand toAgent(String agentId, Object payload, ObjectMapper objectMapper) {
        return build(TargetType.AGENT, agentId, payload, objectMapper);
    }

    /**
     * 向访客投递消息。
     */
    public static WsDeliveryCommand toVisitor(String sessionId, Object payload, ObjectMapper objectMapper) {
        return build(TargetType.VISITOR, sessionId, payload, objectMapper);
    }

    /**
     * KICK 命令：通知目标 Pod 关闭该 agentId 的所有旧连接。
     * excludeWsSessionId 为新连接的 wsSessionId，仅供日志追踪，消费端无需使用。
     */
    public static WsDeliveryCommand kickAgent(String agentId, String excludeWsSessionId) {
        return new WsDeliveryCommand(TargetType.KICK_AGENT, agentId, null, null, excludeWsSessionId);
    }

    private static WsDeliveryCommand build(TargetType type, String targetId,
                                           Object payload, ObjectMapper objectMapper) {
        WsMessageType msgType = extractMessageType(payload);
        try {
            return new WsDeliveryCommand(type, targetId, msgType,
                    objectMapper.writeValueAsString(payload), null);
        } catch (JsonProcessingException e) {
            // 自定义 record 序列化失败属于编程错误，包装为非受检异常
            throw new IllegalStateException("WS payload 序列化失败: "
                    + payload.getClass().getSimpleName(), e);
        }
    }

    /** 从 payload 对象提取对应的 WsMessageType 枚举。
     *
     * <p>注意：{@link WsMessageType#KICKED_OUT} 不在此 switch 中，这是故意的。
     * KICK 消息走的是 {@link WsDeliveryCommand.TargetType#KICK_AGENT} 路径，
     * 消费端收到 KICK_AGENT 命令后直接本地构造 {@link WsKickedOutMessage#INSTANCE} 广播，
     * 不需要通过 payloadJson 序列化/反序列化传递。
     * {@code KICKED_OUT} 枚举值只用于前端消息协议（前端识别消息类型），
     * 不会出现在 {@code WsDeliveryCommand.wsMessageType} 字段中。
     */
    private static WsMessageType extractMessageType(Object payload) {
        return switch (payload) {
            case WsChatMessage      ignored -> WsMessageType.MESSAGE;
            case WsTypingMessage    ignored -> WsMessageType.TYPING;
            case WsConnectedMessage ignored -> WsMessageType.CONNECTED;
            // 注：WsConnectedMessage 在当前实现中仅在本地 registry.sendToSession() 发出，
            // 不会走 sendToVisitor/sendToAgent 路由，因此此分支在正常运行时不会触发。
            // 保留是为了完整性：若未来出现 CONNECTED 需要跨 Pod 投递的场景，此处已覆盖。
            case WsErrorMessage     ignored -> WsMessageType.ERROR;
            // WsKickedOutMessage 不走此路径，由 KICK_AGENT targetType 直接处理
            default -> throw new IllegalArgumentException(
                    "不支持跨 Pod 投递的消息类型: " + payload.getClass().getSimpleName());
        };
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
    /**
     * 注入 VisitorNotifier 接口（实现类为 ChatWebSocketHandler），不直接注入实现类。
     * 原因：ChatWebSocketHandler 同时注入 WsMessageRouter（用于 notifyAgent 路由），
     * 若此处注入实现类会造成 Spring 构造器循环依赖（BeanCurrentlyInCreationException）。
     * 通过接口注入可让 Spring 用代理打破循环。
     *
     * 本地路径调用 visitorNotifier.notifyVisitor()（即 ChatWebSocketHandler 的本地 socket 写入），
     * 不会再次走路由层，不存在递归。
     */
    private final VisitorNotifier visitorNotifier;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 向座席推送消息。
     * 若座席 WS 在本 Pod，直接推送；否则发 MQ 到目标 Pod 队列。
     *
     * 说明：pods 是 Pod 集合（不是连接集合）。
     * 同一座席多个浏览器标签可能分布在不同 Pod：
     *   - 同一 Pod 上的多个标签：agentRegistry.broadcast 一次性推给所有本地连接
     *   - 不同 Pod 上的连接：各发一条 MQ 消息，目标 Pod 收到后再做本地 broadcast
     * KICK 模式下 pods 集合始终只有 1 个元素（Redisson 分布式锁保证）
     */
    public void sendToAgent(String agentId, Object payload) {
        Set<String> pods = presenceRegistry.getAgentPods(agentId);
        if (pods.isEmpty()) {
            log.warn("[WsRouter] 座席不在线 agentId={}", agentId);
            return;
        }
        for (String pod : pods) {
            if (podIdentity.isLocal(pod)) {
                // 本 Pod：直接 broadcast，推给本地所有连接（可能多个标签）
                agentRegistry.broadcast(agentId, payload);
            } else {
                // 跨 Pod：序列化 payload 发 MQ，目标 Pod 收到后再做本地 broadcast
                // toAgent() 内部序列化失败时抛 IllegalStateException（编程错误），不需要 try-catch
                deliver(pod, WsDeliveryCommand.toAgent(agentId, payload, objectMapper));
            }
        }
    }

    /**
     * 向访客推送消息。
     * 若访客 WS 在本 Pod，直接推送；否则发 MQ 到目标 Pod 队列。
     *
     * 说明：访客 presence 是 String（单 Pod），不是 Set。
     * 访客重连时 visitorSessions.put 会覆盖旧连接并执行 closeStaleSession，
     * 始终只有一个活跃连接，因此 presence 只需记录单个 podId。
     *
     * 本地路径调用 visitorNotifier.notifyVisitor()（ChatWebSocketHandler 的本地 socket 写入），
     * 不经过路由层，不存在递归问题。
     */
    public void sendToVisitor(String sessionId, Object payload) {
        String pod = presenceRegistry.getVisitorPod(sessionId);
        if (pod == null) {
            log.warn("[WsRouter] 访客不在线 sessionId={}", sessionId);
            return;
        }
        if (podIdentity.isLocal(pod)) {
            // 直接调用本地推送（visitorNotifier.notifyVisitor = ChatWebSocketHandler 的本地 socket 写入）
            visitorNotifier.notifyVisitor(sessionId, payload);
        } else {
            // toVisitor() 内部序列化失败时抛 IllegalStateException（编程错误），不需要 try-catch
            deliver(pod, WsDeliveryCommand.toVisitor(sessionId, payload, objectMapper));
        }
    }

    /** 发到目标 Pod 的 RabbitMQ 队列（Direct Exchange，routingKey = podId） */
    private void deliver(String targetPod, WsDeliveryCommand cmd) {
        try {
            rabbitTemplate.convertAndSend(WS_DELIVERY_EXCHANGE, targetPod, cmd);
            log.debug("[WsRouter] 跨 Pod 投递 targetPod={} type={} id={}",
                    targetPod, cmd.targetType(), cmd.targetId());
            // TODO: 可通过 rabbitTemplate.setMandatory(true) + ReturnsCallback 即时感知
            //       目标队列不存在（Pod 已崩溃），在回调中调用 presenceRegistry.removeStalePod(targetPod)
            //       可将推送中断窗口从 TTL 90s 压缩到接近 0（第一条失败时立即清理）。
            //       当前暂不实现：SCAN 有性能代价，单机部署无需此优化，多机压测后评估。
            //       详见设计文档 §4.5。
        } catch (Exception e) {
            log.warn("[WsRouter] MQ 投递失败 targetPod={} msg={}", targetPod, e.getMessage());
        }
    }

    /**
     * 向目标 Pod 发送 KICK 命令。
     * KICK 模式下由 {@link com.aria.conversation.infrastructure.websocket.AgentChannelWsHandler} 调用，
     * 通知目标 Pod 关闭该 agentId 的所有旧连接。
     *
     * @param targetPod         目标 Pod 的 podId（RabbitMQ 队列名）
     * @param agentId           被踢座席 ID
     * @param newSessionWsId    新连接的 wsSessionId（仅供目标 Pod 日志追踪，不影响逻辑）
     */
    public void sendKick(String targetPod, String agentId, String newSessionWsId) {
        deliver(targetPod, WsDeliveryCommand.kickAgent(agentId, newSessionWsId));
    }
}
```

### 5.3 WsDeliveryConsumer（本 Pod 消费）

与 `SessionEventSubscriber` 保持一致的风格：**静态拓扑（Exchange）在 `RabbitMQConfig` 声明为 `@Bean`，动态部分（匿名队列 + routing key）在 `@RabbitListener` 注解里声明**。

```java
// infrastructure/ws/cluster/WsDeliveryConsumer.java
/**
 * WS 跨 Pod 投递消费者。
 *
 * <p>监听本 Pod 专属的匿名队列（exclusive + autoDelete），
 * routing key = podId（UUID），由 {@link WsMessageRouter} 根据 Redis presence 精确路由。
 *
 * <p>队列声明规则（与 SessionEventSubscriber.onSessionEvent 同款模式）：
 * <ul>
 *   <li>Exchange（ws.delivery Direct）由 @RabbitListener 注解自动声明（durable=true），
 *       无需在 RabbitMQConfig 中额外新增 @Bean</li>
 *   <li>队列动态（匿名），每 Pod 启动时由 Spring AMQP 自动创建，exclusive + autoDelete，
 *       Pod 停止时队列自动删除，不积压离线消息</li>
 *   <li>routing key 通过 SpEL #{@podIdentity.get()} 运行时求值，绑定到本 Pod 的 UUID</li>
 * </ul>
 *
 * <p>Pod 崩溃场景：队列随连接断开自动删除，其他 Pod 向此 podId 发的消息
 * 因找不到队列而被 broker 静默丢弃（实时推送中断）。
 * 消息数据已写入 ConversationHistoryRepository，客户端重连后通过 sinceSeq 补全。
 * 如需即时感知 Pod 下线，可在 RabbitTemplate 启用 mandatory+ReturnsCallback（见设计文档 §4.5）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsDeliveryConsumer {

    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier visitorNotifier;
    private final ObjectMapper objectMapper;   // 用于还原 payloadJson → 原始类型

    /**
     * 接收跨 Pod 投递的 WS 消息，执行本地推送。
     *
     * <p>routing key = #{@podIdentity.get()}（SpEL，运行时求值为本 Pod 的队列名），
     * 确保只有发给本 Pod 的消息才路由到此队列。
     *
     * <p>队列名称说明：{@code name = "#{@podIdentity.get()}"} 使 @RabbitListener 监听
     * {@link PodIdentity} 已声明的同一个队列（不额外创建新队列）。
     * 若省略 name 而使用匿名 @Queue，Spring AMQP 会创建第二个独立匿名队列，
     * routing key 虽然仍能匹配（Direct Exchange 按 binding key 路由，不按队列名），
     * 但 PodIdentity 声明的队列会成为无消费者的浪费资源，且 Pod 停止时
     * 只有 WsDeliveryConsumer 的队列会随连接断开自动删除，语义不清晰。
     *
     * <p>payload 还原：{@link WsDeliveryCommand} 以 {@code wsMessageType + payloadJson} 形式传递，
     * 消费端按 {@link WsMessageType} 枚举 switch 还原为具体 record 类型，
     * 避免 Jackson 反序列化时 {@code Object} 类型擦除为 {@link java.util.LinkedHashMap}。
     */
    @RabbitListener(bindings = @QueueBinding(
            value    = @Queue(name = "#{@podIdentity.get()}", exclusive = "true", autoDelete = "true"),
            exchange = @Exchange(value = "ws.delivery", type = "direct", durable = "true"),
            key      = "#{@podIdentity.get()}"
    ))
    public void onDelivery(WsDeliveryCommand cmd) {
        try {
            switch (cmd.targetType()) {
                case AGENT -> {
                    Object payload = restorePayload(cmd);
                    agentRegistry.broadcast(cmd.targetId(), payload);
                }
                case VISITOR -> {
                    Object payload = restorePayload(cmd);
                    visitorNotifier.notifyVisitor(cmd.targetId(), payload);
                }
                case KICK_AGENT -> {
                    // 远端 Pod 收到 KICK 命令时，本 Pod 上该 agentId 的所有连接均为旧连接
                    // （新连接在发出 KICK 命令的 Pod 上），无需 exclude，全部推 KICKED_OUT 并关闭
                    log.info("[WsDelivery] 收到 KICK 命令，关闭本 Pod 旧连接 agentId={} srcWsId={}",
                            cmd.targetId(), cmd.excludeWsSessionId());
                    agentRegistry.broadcast(cmd.targetId(), WsKickedOutMessage.INSTANCE);
                    agentRegistry.closeAll(cmd.targetId());
                }
                default -> log.warn("[WsDelivery] 未知 targetType={}", cmd.targetType());
            }
        } catch (Exception e) {
            log.error("[WsDelivery] 消息处理异常 type={} id={}", cmd.targetType(), cmd.targetId(), e);
        }
        log.debug("[WsDelivery] 本地推送完成 type={} id={}", cmd.targetType(), cmd.targetId());
    }

    /**
     * 按 {@link WsMessageType} 枚举将 payloadJson 还原为原始消息对象。
     * 使用枚举 switch 替代 Class.forName()，重构安全且消除任意类加载安全风险。
     */
    private Object restorePayload(WsDeliveryCommand cmd) throws JsonProcessingException {
        return switch (cmd.wsMessageType()) {
            case MESSAGE   -> objectMapper.readValue(cmd.payloadJson(), WsChatMessage.class);
            case TYPING    -> objectMapper.readValue(cmd.payloadJson(), WsTypingMessage.class);
            case CONNECTED -> objectMapper.readValue(cmd.payloadJson(), WsConnectedMessage.class);
            case ERROR     -> objectMapper.readValue(cmd.payloadJson(), WsErrorMessage.class);
            default -> throw new IllegalArgumentException(
                    "不支持跨 Pod 投递的消息类型: " + cmd.wsMessageType());
        };
    }
}
```

### 5.4 RabbitMQ 配置说明

`@RabbitListener` 里的 `@Exchange(value = "ws.delivery", type = "direct", durable = "true")` 启动时会自动声明 Exchange，**`RabbitMQConfig` 无需改动**，与 `SessionEventSubscriber` 处理 Fanout Exchange 的方式完全一致。

所有资源均由 `@RabbitListener` 注解一次完成：

| 资源 | 声明方式 |
|------|---------|
| `ws.delivery` Exchange | `@Exchange(durable = "true")` 自动声明，无需 `@Bean` |
| 匿名队列 | `@Queue(exclusive = "true", autoDelete = "true")` 自动创建 |
| 绑定（routing key = podId） | `key = "#{@podIdentity.get()}"` SpEL 运行时求值 |

### 5.5 调用链路全景（改造后）

```
访客在 Pod A 发消息 → 座席 WS 在 Pod B

Pod A.ChatWebSocketHandler.handleVisitorMessage
  → historyRepository.append(sessionId, content)  // 写历史，不丢消息
  → notifyAgent(sessionId, WsChatMessage.fromVisitor(...))
      → WsMessageRouter.sendToAgent("agent-001", msg)
          → presenceRegistry.getAgentPods("agent-001") → {"spring.gen-b3f2c1"}
          → spring.gen-b3f2c1 ≠ 本机 → rabbitTemplate.send("ws.delivery", "spring.gen-b3f2c1", cmd)
          // podId 为 Spring AMQP AnonymousQueue 生成的队列名（格式：spring.gen-{base64}）
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
    // registry.register() 内部已调用 presenceRegistry.registerAgent()，无需再次显式调用
    registry.register(agentId, session);

    // ② 向所有 Pod 上的旧连接推送 KICKED_OUT（含本 Pod 和跨 Pod）
    Set<String> allPods = presenceRegistry.getAgentPods(agentId);
    for (String pod : allPods) {
        if (podIdentity.isLocal(pod)) {
            registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
        } else {
            // 向目标 Pod 发 KICK 命令，目标 Pod 负责关闭本地所有旧连接
            router.sendKick(pod, agentId, session.getId());
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

`WsDeliveryCommand.kickAgent()` 只需携带 `agentId` 和 `excludeWsSessionId`（仅用于日志，不影响逻辑）：

```java
public static WsDeliveryCommand kickAgent(String agentId, String excludeWsSessionId) {
    return new WsDeliveryCommand(TargetType.KICK_AGENT, agentId, null, null, excludeWsSessionId);
}
```

`WsDeliveryConsumer.onDelivery` 处理 `KICK_AGENT`（已在 §5.3 中定义）：

```java
case KICK_AGENT -> {
    // 远端 Pod 收到 KICK 命令时，本 Pod 上该 agentId 的所有连接均为旧连接（新连接在另一个 Pod），
    // 无需 exclude，向所有旧连接推 KICKED_OUT 后全部关闭
    log.info("[WsDelivery] 收到 KICK 命令 agentId={} srcWsId={}", cmd.targetId(), cmd.excludeWsSessionId());
    agentRegistry.broadcast(cmd.targetId(), WsKickedOutMessage.INSTANCE);
    agentRegistry.closeAll(cmd.targetId());   // 关闭本 Pod 上该 agentId 的所有连接
}
```

> **为何不需要 excludeWsSessionId**：
> 新连接在 Pod A 上，KICK 命令发到 Pod B。Pod B 上只有旧连接，不存在新连接，
> 因此无需排除——全部推 KICKED_OUT 后关闭即可。
> `excludeWsSessionId` 仅用于日志追踪，帮助排查哪条新连接触发了 KICK。

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
      sendLocks.remove(session.getId());
      if (agentId == null) return;

+     // computeIfPresent 内部原子判断：移除 session 后 Set 是否为空
+     // 避免 noMoreLocalSessions() 两步检查的 TOCTOU 竞态
+     boolean[] isEmpty = {false};
+     agentToSessions.computeIfPresent(agentId, (k, set) -> {
+         set.remove(session);
+         if (set.isEmpty()) {
+             isEmpty[0] = true;
+             return null;   // 返回 null = 从 map 中删除该 key
+         }
+         return set;
+     });
+
+     // 本 Pod 上该 agentId 已无连接，从 presence Set 移除本 Pod
+     if (isEmpty[0]) {
+         presenceRegistry.unregisterAgent(agentId, podIdentity.get());
+     }
+     cancelHeartbeat(session.getId());
  }

+ /**
+  * 关闭该座席在本 Pod 上的所有连接。
+  * 用于 KICK 命令的远端 Pod 处理：收到 KICK_AGENT 时，本 Pod 上该 agentId 的
+  * 所有连接均为旧连接，向所有旧连接推 KICKED_OUT 后全部关闭。
+  *
+  * <p>此方法只调用 session.close()，不主动调用 unregister()。
+  * Spring WebSocket 在 session 关闭后会自动触发 afterConnectionClosed
+  * → AgentChannelWsHandler.afterConnectionClosed → registry.unregister()，
+  * 由此完成 agentToSessions map 和 Redis presence 的清理，无需手动处理。
+  */
+ public void closeAll(String agentId) {
+     Set<WebSocketSession> sessions = agentToSessions.get(agentId);
+     if (sessions == null) return;
+     for (WebSocketSession session : sessions) {
+         if (session.isOpen()) {
+             try { session.close(CloseStatus.GOING_AWAY); } catch (IOException ignored) {}
+         }
+     }
+ }
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
+     // 本 Pod：向旧连接推 KICKED_OUT，关闭旧连接
+     registry.broadcastExcept(agentId, session, WsKickedOutMessage.INSTANCE);
+     registry.closeAllExcept(agentId, session);
+     // 跨 Pod：向其他 Pod 发 KICK 命令（详见 §6.2）
+     Set<String> allPods = presenceRegistry.getAgentPods(agentId);
+     for (String pod : allPods) {
+         if (!podIdentity.isLocal(pod)) {
+             router.sendKick(pod, agentId, session.getId());
+         }
+     }
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

  // notifyVisitor：保持本地直推语义，不走路由
  // ⚠️ 此方法不能改为调用 router.sendToVisitor()！
  // WsMessageRouter.sendToVisitor 本地路径通过 visitorNotifier 接口调用此方法做本地 socket 写入。
  // 若此方法改调 router，则产生：notifyVisitor → router.sendToVisitor → visitorNotifier.notifyVisitor 无限递归。
  @Override
  public void notifyVisitor(String sessionId, Object payload) {
      // 保持原有实现不变：直接写本地 visitorSessions socket
      WebSocketSession vs = visitorSessions.get(sessionId);
      if (vs == null || !vs.isOpen()) { log.warn(...); return; }
      sendJson(vs, payload);
  }
```

**AgentChannelWsHandler.java**（改造点：`handleTextMessage` 中的访客推送走路由层）

```diff
+ private final WsMessageRouter router;   // 新增注入

  // handleTextMessage 中，座席回复访客时：
- visitorNotifier.notifyVisitor(sessionId, WsChatMessage.fromAgent(...));
+ router.sendToVisitor(sessionId, WsChatMessage.fromAgent(...));  // 通过路由层，支持跨 Pod 投递

  // TYPING 信令同理：
- visitorNotifier.notifyVisitor(sessionId, WsTypingMessage.of(sessionId, ts));
+ router.sendToVisitor(sessionId, WsTypingMessage.of(sessionId, ts));

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
| `ws:kick:agent:{agentId}` | Redisson RLock | 10s（固定 leaseTime，非 watchdog） | KICK 模式分布式锁 |
| `lock:ingest:persist:{docId}` | Redisson RLock | watchdog | PersistHandler 幂等锁 |
| `lock:scheduler:doc-expiry` | Redisson RLock | 10min | 定时任务排它锁 |

### 8.5 魔法字符串常量化（实现时要求）

以下字符串在多个文件中重复使用，实现时必须提取为常量，避免拼写错误和散落维护：

**建议新增 `infrastructure/ws/cluster/WsClusterConstants.java`**：

```java
// infrastructure/ws/cluster/WsClusterConstants.java
public final class WsClusterConstants {

    private WsClusterConstants() {}

    // ---- RabbitMQ Exchange ----
    /** WS 跨 Pod 投递 Direct Exchange 名称 */
    public static final String WS_DELIVERY_EXCHANGE = "ws.delivery";

    // ---- Redis Key 前缀 ----
    /** 访客 presence key 前缀，完整格式：ws:visitor:pod:{sessionId} */
    public static final String VISITOR_POD_KEY_PREFIX = "ws:visitor:pod:";

    /** 座席 presence key 前缀，完整格式：ws:agent:pods:{agentId} */
    public static final String AGENT_PODS_KEY_PREFIX = "ws:agent:pods:";

    /** KICK 分布式锁 key 前缀，完整格式：ws:kick:agent:{agentId} */
    public static final String KICK_LOCK_KEY_PREFIX = "ws:kick:agent:";
}
```

`WsPresenceRegistry`、`WsMessageRouter`、`AgentChannelWsHandler` 均引用此常量类，禁止在各类中重复定义字符串字面量。

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
