# RabbitMQ 可靠消息持久化方案

> **日期：** 2026-06-29  
> **服务：** conversation-service（端口 8082）  
> **执行方式：** 使用 `superpowers:executing-plans` 按 Task 顺序逐步执行

---

## 背景与问题

### 现有数据丢失点

当前系统通过 Redis Stream 异步持久化对话消息到 PostgreSQL，存在以下 **6 个数据丢失点**：

| # | 位置 | 丢失内容 | 可见性 |
|---|------|----------|--------|
| 1 | `publishMessageEvent()` Stream 写失败 | 单条对话消息永不入 Stream | `log.warn` 仅打印 |
| 2 | `publishSessionStart()` Stream 写失败 | 会话记录永不写 DB，后续消息成孤儿 | `log.warn` 仅打印 |
| 3 | `publishSessionEnd()` Stream 写失败 | 会话永远不标记 CLOSED | `log.warn` 仅打印 |
| 4 | Redis 全量宕机 | 热数据和 Stream 同时失败 | 异常日志 |
| 5 | DLQ 无重放机制 | 进入 DLQ 的消息无法自动恢复 | `log.warn` |
| 6 | 孤儿消息 | SESSION_START 丢失导致无父记录的消息行 | 静默，无 FK 约束 |

**根本原因：发布端没有持久化保障**，完全依赖 Redis Stream 一次写入成功。

---

## 方案选择

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 直接 DB 写** | append() 同步写 Redis List + 异步写 DB | 最简单 | 绕过 Stream，架构语义弱 |
| **B. Outbox Pattern** | 先写 Outbox 表，Poller 推 Stream | 工业标准，100% 可靠 | 新增 Outbox 表和 Poller |
| **✅ C. RabbitMQ** | 替换 Redis Stream，Publisher Confirms + Retry | 天然可靠，代码更简洁，消除 PEL/XCLAIM 复杂逻辑 | 新增 RabbitMQ 容器 |

**选择：方案 C（RabbitMQ）**

---

## 目标架构

```
append() / appendAgentMessage() / publishSession*()
  ├─ Redis List（热数据，实时路由，不变）
  └─ ConversationMessagePublisher.publish()
       └─ rabbitTemplate.convertAndSend()
            + publisher confirms（Broker 确认收到才返回）
            + Spring @Retryable（3次，指数退避 1s→2s→4s）
                   ↓
       Exchange: cs.conversation（direct）
                   ↓ routing-key: persist
       Queue: cs.conversation.persist（持久化队列，绑定 DLX）
                   ↓ 消费失败 → 超出重试
       cs.conversation.persist.dlq（死信队列，人工排查）
                   ↓ 正常消费
       @RabbitListener ConversationMessageConsumer
                   ↓
       ConversationPersistRepository（已有，不变）
                   ↓
       PostgreSQL cs_conversation + cs_conversation_message（不变）
```

### 可靠性保障层次

| 层次 | 机制 | 覆盖场景 |
|------|------|----------|
| 发布端 L1 | Spring `@Retryable`（3次，指数退避） | RabbitMQ 短暂不可用 |
| 发布端 L2 | Publisher Confirms | Broker 持久化确认 |
| 消费端 L1 | Spring AMQP 自动 Retry + nack | DB 短暂不可用 |
| 消费端 L2 | DLX → DLQ | 消息格式错误/DB 持续异常 |
| 幂等保障 | SESSION_START unique key 捕获 | 消息重复投递 |

---

## 改动范围

### 新增文件（4 个）

| 文件 | 职责 |
|------|------|
| `infrastructure/config/RabbitMQConfig.java` | Exchange / Queue / DLX 拓扑声明 |
| `infrastructure/mq/ConversationMessagePublisher.java` | 发布端（含 Confirms + Retry） |
| `infrastructure/mq/ConversationMessageConsumer.java` | 消费端（替换 Worker+PersistenceService） |
| `docker run cs-rabbitmq` | RabbitMQ 容器（management 插件） |

### 修改文件（3 个）

| 文件 | 改动 |
|------|------|
| `pom.xml` | 新增 `spring-boot-starter-amqp` 依赖 |
| `application.yml` | 新增 RabbitMQ 连接配置 + conversation.persist.* |
| `ConversationHistoryRepository.java` | 注入 Publisher，替换 RedisStreamHelper 调用 |
| `SessionQueueService.java` | 注入 Publisher，替换 publishSession*() 实现 |
| `ConversationApplication.java` | 新增 `@EnableRetry` |

### 删除文件（2 个）

| 文件 | 原因 |
|------|------|
| `ConversationStreamWorker.java` | RabbitMQ @RabbitListener 替代 @Scheduled |
| `ConversationPersistenceService.java` | PEL/XCLAIM 逻辑全部由 Spring AMQP 内置机制替代 |

---

## 详细实现步骤

### Task 1: 启动 RabbitMQ 容器

```bash
docker run -d \
  --name cs-rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management-alpine

# 等待启动
sleep 10 && docker exec cs-rabbitmq rabbitmq-diagnostics ping
```

验证：访问 http://localhost:15672（账号：guest / guest）

---

### Task 2: pom.xml 加 AMQP 依赖

在 `ai-conversation/conversation-service/pom.xml` 的 `<dependencies>` 中追加：

```xml
<!-- RabbitMQ 可靠消息（替换 Redis Stream 持久化通道） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>

<!-- Spring Retry（@Retryable 支持） -->
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
```

---

### Task 3: application.yml 加 RabbitMQ 配置

在现有 `spring:` 节点下追加：

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    # Publisher Confirms：Broker 持久化确认后才返回，保障发布端可靠性
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0

conversation:
  persist:
    exchange: cs.conversation
    queue: cs.conversation.persist
    dlq: cs.conversation.persist.dlq
    routing-key: persist
```

**同时删除** 以下已无用的 Stream 配置（可选，保留不影响运行）：

```yaml
# 以下配置在切换到 RabbitMQ 后不再使用
conversation:
  persist:
    stream-key: cs:conversation:messages
    group-name: persist-group
    ...
```

---

### Task 4: RabbitMQConfig.java

新建 `src/main/java/com/aidevplatform/conversation/infrastructure/config/RabbitMQConfig.java`：

```java
package com.aidevplatform.conversation.infrastructure.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明。
 * Exchange、Queue、Binding、DLX 均以 Bean 方式声明，Spring AMQP 自动在 Broker 创建（幂等）。
 *
 * 拓扑：
 *   cs.conversation（direct Exchange）
 *     ↓ routing-key: persist
 *   cs.conversation.persist（持久队列，关联 DLX）
 *     ↓ 消费失败超出重试次数
 *   cs.conversation.persist.dlq（死信队列，人工排查）
 */
@Configuration
public class RabbitMQConfig {

    @Value("${conversation.persist.exchange}")   private String exchange;
    @Value("${conversation.persist.queue}")      private String queue;
    @Value("${conversation.persist.dlq}")        private String dlq;
    @Value("${conversation.persist.routing-key}") private String routingKey;

    /** 主 Exchange（direct 类型，路由精确） */
    @Bean
    public DirectExchange conversationExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    /** 死信队列（DLQ），持久化，人工排查用 */
    @Bean
    public Queue conversationDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    /**
     * 主持久化队列。
     * 绑定 DLX：消息被 nack 且超出重试次数后，自动路由到 dlq。
     */
    @Bean
    public Queue conversationPersistQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "")      // 使用默认 Exchange
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    /** 队列绑定到 Exchange */
    @Bean
    public Binding conversationBinding() {
        return BindingBuilder.bind(conversationPersistQueue())
                .to(conversationExchange())
                .with(routingKey);
    }
}
```

---

### Task 5: ConversationMessagePublisher.java

新建 `src/main/java/com/aidevplatform/conversation/infrastructure/mq/ConversationMessagePublisher.java`：

```java
package com.aidevplatform.conversation.infrastructure.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 对话消息 RabbitMQ 发布端。
 *
 * 可靠性保障（两层）：
 * 1. {@code @Retryable}：发布失败最多重试 3 次，指数退避（1s → 2s → 4s）
 * 2. Publisher Confirms（yml 配置）：Broker 确认持久化后才返回
 *
 * 三次重试全部失败后，异常向上传播，调用方记录 WARN 日志，
 * 消息仅存 Redis List（热路由仍可用，持久化链路降级）。
 */
@Slf4j
@Component
public class ConversationMessagePublisher {

    // ---- Stream 字段名常量（与 ConversationStreamEvent 保持一致） ----
    private static final String FIELD_TYPE            = "type";
    private static final String FIELD_SESSION_ID      = "sessionId";
    private static final String FIELD_ROLE            = "role";
    private static final String FIELD_CONTENT         = "content";
    private static final String FIELD_VISITOR_NAME    = "visitorName";
    private static final String FIELD_TAG             = "tag";
    private static final String FIELD_TRANSFER_REASON = "transferReason";
    private static final String FIELD_TIMESTAMP       = "timestamp";

    private final RabbitTemplate rabbitTemplate;
    private final String         exchange;
    private final String         routingKey;

    public ConversationMessagePublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${conversation.persist.exchange}")    String exchange,
            @Value("${conversation.persist.routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange       = exchange;
        this.routingKey     = routingKey;
    }

    /**
     * 发布单条对话消息（MESSAGE 类型）。
     *
     * @param sessionId 会话 ID
     * @param role      DB 角色标识（user / assistant / agent）
     * @param content   消息内容
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishMessage(String sessionId, String role, String content) {
        Map<String, Object> payload = Map.of(
            FIELD_TYPE,       ConversationStreamEvent.Type.MESSAGE.name(),
            FIELD_SESSION_ID, sessionId,
            FIELD_ROLE,       role,
            FIELD_CONTENT,    content,
            FIELD_TIMESTAMP,  Instant.now().getEpochSecond()
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.debug("[MQ] MESSAGE published sessionId={} role={}", sessionId, role);
    }

    /**
     * 发布会话开始事件（SESSION_START 类型）。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionStart(String sessionId, String visitorName,
                                     String transferReason, String tag, long timestamp) {
        Map<String, Object> payload = Map.of(
            FIELD_TYPE,            ConversationStreamEvent.Type.SESSION_START.name(),
            FIELD_SESSION_ID,      sessionId,
            FIELD_VISITOR_NAME,    visitorName != null ? visitorName : "访客",
            FIELD_TRANSFER_REASON, transferReason != null ? transferReason : "",
            FIELD_TAG,             (tag != null && !tag.isBlank()) ? tag : "咨询",
            FIELD_TIMESTAMP,       timestamp
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_START published sessionId={}", sessionId);
    }

    /**
     * 发布会话结束事件（SESSION_END 类型）。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionEnd(String sessionId) {
        Map<String, Object> payload = Map.of(
            FIELD_TYPE,       ConversationStreamEvent.Type.SESSION_END.name(),
            FIELD_SESSION_ID, sessionId,
            FIELD_TIMESTAMP,  Instant.now().getEpochSecond()
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_END published sessionId={}", sessionId);
    }
}
```

---

### Task 6: ConversationMessageConsumer.java

新建 `src/main/java/com/aidevplatform/conversation/infrastructure/mq/ConversationMessageConsumer.java`：

```java
package com.aidevplatform.conversation.infrastructure.mq;

import com.aidevplatform.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 对话消息 RabbitMQ 消费者。
 *
 * 替换原 ConversationStreamWorker + ConversationPersistenceService，
 * PEL/XCLAIM 复杂逻辑全部由 Spring AMQP + RabbitMQ 原生机制替代：
 * - 消费失败自动 nack → Spring Retry（3次）→ DLX → cs.conversation.persist.dlq
 *
 * 幂等性保障：
 * - SESSION_START：ConversationPersistRepository.startConversation() 捕获 unique key 冲突
 * - MESSAGE：按 sessionId + created_at 追加，允许重复（极端场景产生重复行，可接受）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMessageConsumer {

    private final ConversationPersistRepository persistRepository;

    @RabbitListener(queues = "${conversation.persist.queue}", concurrency = "2")
    public void consume(Map<String, Object> payload) {
        String type      = str(payload, "type");
        String sessionId = str(payload, "sessionId");
        log.debug("[MQ Consumer] 处理消息 type={} sessionId={}", type, sessionId);

        switch (type) {
            case "SESSION_START" -> handleSessionStart(payload, sessionId);
            case "SESSION_END"   -> handleSessionEnd(payload, sessionId);
            case "MESSAGE"       -> handleMessage(payload, sessionId);
            default              -> log.warn("[MQ Consumer] 未知事件类型: {}", type);
        }
    }

    private void handleSessionStart(Map<String, Object> payload, String sessionId) {
        persistRepository.startConversation(
            sessionId,
            str(payload, "visitorName"),
            str(payload, "transferReason"),
            str(payload, "tag"),
            toOffsetDateTime(longVal(payload, "timestamp")));
    }

    private void handleSessionEnd(Map<String, Object> payload, String sessionId) {
        persistRepository.closeConversation(
            sessionId,
            toOffsetDateTime(longVal(payload, "timestamp")));
    }

    private void handleMessage(Map<String, Object> payload, String sessionId) {
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(str(payload, "role"));
        entity.setContent(str(payload, "content"));
        entity.setCreatedAt(toOffsetDateTime(longVal(payload, "timestamp")));
        persistRepository.saveMessages(List.of(entity));
    }

    // ---- 工具方法 ----

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private long longVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Instant.now().getEpochSecond();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return Instant.now().getEpochSecond();
        }
    }

    private OffsetDateTime toOffsetDateTime(long epochSeconds) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}
```

---

### Task 7: 修改 ConversationHistoryRepository

将构造器中的 `RedisStreamHelper` 替换为 `ConversationMessagePublisher`：

```java
// 构造器改为
public ConversationHistoryRepository(
        StringRedisTemplate redis,
        ConversationMessagePublisher publisher,   // ← 替换 RedisStreamHelper
        @Value("${conversation.persist.stream-key:cs:conversation:messages}") String streamKey) {
    this.redis     = redis;
    this.publisher = publisher;
    // streamKey 保留兼容旧配置，新路径不再使用
}

// publishMessageEvent() 改为
private void publishMessageEvent(String sessionId, String role, String content) {
    try {
        publisher.publishMessage(sessionId, role, content);
    } catch (Exception e) {
        log.warn("[History] MQ 发布失败（3次重试后），消息仅存 Redis List，sessionId={}", sessionId, e);
    }
}
```

---

### Task 8: 修改 SessionQueueService

将构造器中 `RedisStreamHelper` 替换为 `ConversationMessagePublisher`：

```java
// 构造器改为
public SessionQueueService(
        StringRedisTemplate redis,
        ObjectMapper objectMapper,
        ConversationMessagePublisher publisher) {   // ← 替换 RedisStreamHelper
    this.redis     = redis;
    this.objectMapper = objectMapper;
    this.publisher = publisher;
}

// publishSessionStart() 改为
private void publishSessionStart(String sessionId, String visitorName,
                                  String transferReason, String tag, long timestamp) {
    try {
        publisher.publishSessionStart(sessionId, visitorName, transferReason, tag, timestamp);
    } catch (Exception e) {
        log.warn("[SessionQueue] SESSION_START MQ 发布失败 sessionId={}", sessionId, e);
    }
}

// publishSessionEnd() 改为
private void publishSessionEnd(String sessionId) {
    try {
        publisher.publishSessionEnd(sessionId);
    } catch (Exception e) {
        log.warn("[SessionQueue] SESSION_END MQ 发布失败 sessionId={}", sessionId, e);
    }
}
```

---

### Task 9: ConversationApplication.java 加 @EnableRetry

```java
@SpringBootApplication
@EnableScheduling
@EnableRetry                    // ← 新增，激活 @Retryable
@MapperScan("com.aidevplatform.conversation.infrastructure.persistence.mapper")
public class ConversationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
```

同时**删除**旧文件：
- `ConversationStreamWorker.java`
- `ConversationPersistenceService.java`

---

### Task 10: 编译 + 验证

```bash
# 1. 启动 RabbitMQ
docker run -d --name cs-rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3.13-management-alpine

# 2. 编译
cd ~/IdeaProjects/ai-customerservice-backend
mvn package -DskipTests -pl ai-conversation/conversation-service -am -q

# 3. 重启服务
lsof -ti :8082 | xargs kill -9 2>/dev/null; sleep 2
nohup java -Xms64m -Xmx256m \
  -jar ai-conversation/conversation-service/target/conversation-service-1.0.0-SNAPSHOT.jar \
  > ~/logs/cs-conversation.log 2>&1 &

# 4. 等待启动
sleep 20 && lsof -nP -i :8082 | grep LISTEN

# 5. 发送测试请求
curl -s -X POST http://localhost:8082/api/v1/chat/transfer \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"mq-test-001","userName":"验证MQ","tag":"咨询"}' | python3 -m json.tool

# 6. 等待消费
sleep 3

# 7. 验证 DB 写入
docker exec cs-postgres psql -U postgres -d ai_customerservice \
  -c "SELECT session_id, visitor_name, status, started_at \
      FROM cs_conversation.cs_conversation WHERE session_id='mq-test-001';"

# 8. 验证 RabbitMQ 管理界面
echo "访问 http://localhost:15672 查看队列状态（guest/guest）"
```

---

## 不变的文件

以下文件**无需任何改动**：

- `V1__create_conversation_tables.sql`（DB 表结构）
- `ConversationEntity.java` / `ConversationMessageEntity.java`
- `ConversationMapper.java` / `ConversationMessageMapper.java`
- `ConversationPersistRepository.java`
- `ConversationStreamEvent.java`（`Type` 枚举继续复用）
- `SessionStatus.java` / `SessionEventType.java`
- `ChatWebSocketHandler.java`（使用 `historyRepository.appendAgentMessage()`，无变化）

---

## 实现后的可靠性对比

| 场景 | 改造前 | 改造后 |
|------|--------|--------|
| RabbitMQ/Redis 短暂不可用（< 60s） | 数据丢失，仅 log.warn | @Retryable 自动重试，透明恢复 |
| Redis 长时间宕机 | 所有数据永久丢失 | Redis 恢复后，RabbitMQ 队列中消息继续消费 |
| DB 短暂不可用 | PEL 重试（有效） | Spring AMQP nack + Retry（更简洁） |
| DB 持续异常 | 手动处理 DLQ（Redis List） | 自动路由到 RabbitMQ DLQ，管理界面可见 |
| 消息孤儿（SESSION_START 丢失） | 静默孤儿行 | Publisher Confirms 保障 SESSION_START 必达 |
| 进程 crash 时内存 retry queue | 丢失 | RabbitMQ 持久化队列，重启后继续 |

---

## 附录：枚举改造记录（已完成）

### 背景

实现 RabbitMQ 方案过程中，同步完成了代码中魔法字符串的枚举化改造，
消除了 `"user"/"assistant"/"agent"/"WAITING"/"ACTIVE"/"CLOSED"` 等散落的字面量。

### 新增枚举

#### `MessageRole`（`domain` 包）

| 枚举值 | DB 存储值 | 用途 |
|--------|-----------|------|
| `USER` | `user` | 访客消息 |
| `ASSISTANT` | `assistant` | AI 回复（与 OpenAI 标准一致，Redis List 存储格式） |
| `AGENT` | `agent` | 人工座席回复（DB 专用，区分 AI 和人工，便于质检分析） |
| `SYSTEM` | `system` | 系统消息（接入提示、会话超时、转交通知等，前端建议居中气泡样式） |

```java
public enum MessageRole {
    USER("user"), ASSISTANT("assistant"), AGENT("agent"), SYSTEM("system");

    @EnumValue
    private final String value;
    // ...
    public static MessageRole fromValue(String value) { /* 大小写不敏感解析 */ }
}
```

#### `SessionStatus`（已有，补充 `@EnumValue`）

新增 `value` 字段和 `@EnumValue` 注解，MyBatis-Plus 自动映射，
无需再调用 `.name()` 手动转字符串。

### 改动文件清单

| 文件 | 改动内容 |
|------|----------|
| `domain/MessageRole.java` | **新建**，含 4 个角色枚举值 + `@EnumValue` + `fromValue()` |
| `domain/SessionStatus.java` | 补充 `@EnumValue` + `value` 字段 + `toString()` |
| `entity/ConversationEntity.java` | `status: String` → `SessionStatus` |
| `entity/ConversationMessageEntity.java` | `role: String` → `MessageRole` |
| `ConversationPersistRepository.java` | `entity.setStatus(SessionStatus.WAITING)` 直接用枚举 |
| `ConversationPersistenceService.java` | `entity.setRole(MessageRole.fromValue(...))` |
| `ChatWebSocketHandler.java` | `ROLE_USER/AGENT/ASSISTANT` 常量 → `MessageRole.*.getValue()` |
| `application.yml` | 配置 `default-enum-type-handler: MybatisEnumTypeHandler` |

### SYSTEM 角色使用指南

`MessageRole.SYSTEM` 用于发送非对话内容的系统级通知，例如：

```java
// 座席接入通知
historyRepository.append(sessionId, MessageRole.SYSTEM.getValue(), "👤 人工客服已接入，请直接输入您的问题。");

// 会话结束通知  
historyRepository.append(sessionId, MessageRole.SYSTEM.getValue(), "本次会话已结束，感谢您的使用。");
```

前端建议对 `role === "system"` 的消息使用居中气泡样式，与普通对话消息视觉区分。
