# SLA Webhook 通知 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SLA 违规告警新增 Webhook 推送能力，支持飞书、钉钉、企业微信和自定义 Webhook，管理员可在 SLA 策略中绑定多个 Webhook 渠道。

**Architecture:** 新增 `cs_webhook_config` 表存储配置；Strategy 模式实现各平台发送器（`FeishuWebhookSender`/`DingtalkWebhookSender`/`WecomWebhookSender`/`CustomWebhookSender`）；`WebhookDispatcher` 异步分发，`@Async` 独立线程池不阻塞 SLA 扫描；`SlaBreachActions` 追加 `webhookIds` 字段，`SlaBreachNotifier.notifyBatch()` 末尾触发分发。

**Tech Stack:** Spring Boot, MyBatis-Plus, PostgreSQL, RabbitMQ（已有），Java `HttpClient`（已在 HolidaySyncScheduler 中使用），JUnit 5 + Mockito

## Global Constraints

- 所有新 Entity 放 `com.aria.conversation.infrastructure.persistence.entity`，`@TableName(schema="cs_conversation", value="table_name")`
- 所有新 Mapper 放 `com.aria.conversation.infrastructure.persistence.mapper`，继承 `BaseMapper<T>`，加 `@Mapper`
- Application Service 放 `com.aria.conversation.application.service`，`@Slf4j @Service @RequiredArgsConstructor`
- 写方法加 `@Transactional(rollbackFor = Exception.class)`
- 业务异常抛 `BusinessException`（`com.aria.common.core.exception`）
- 响应用 `R<T>`（`com.aria.common.web.response.R`）
- Controller 放 `com.aria.conversation.interfaces.rest`，权限 `@SaCheckPermission("system:sla:manage")`
- `WebhookSender` 实现类放 `com.aria.conversation.infrastructure.webhook`（新建子包）
- `WebhookDispatcher` 放 `com.aria.conversation.infrastructure.webhook`
- 所有新表用 `create_time`/`update_time`（阿里规范），PostgreSQL 语法（BIGSERIAL, TIMESTAMPTZ, JSONB）
- Schema 变更追加到 `docs/sql/conversation-service-schema.sql` 和 `docs/sql/conversation-service-data.sql`
- 测试用 `@ExtendWith(MockitoExtension.class)` + AssertJ
- HTTP 超时：连接 5s，读取 10s，重试最多 3 次（指数退避 1s/3s/9s）
- `secret` 字段存明文（本期不加密），生产部署时配合数据库加密或 Vault

---

## Task 1: DB Schema + Entity + Mapper

**Files:**
- Modify: `docs/sql/conversation-service-schema.sql`
- Modify: `docs/sql/conversation-service-data.sql`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/SlaBreachActions.java`
- Create: `...infrastructure/persistence/entity/WebhookConfigEntity.java`
- Create: `...infrastructure/persistence/mapper/WebhookConfigMapper.java`
- Modify: `...infrastructure/persistence/entity/SlaBreachEntity.java` (追加 `webhookNotifiedAt`)
- Modify: `...infrastructure/persistence/mapper/SlaBreachMapper.java` (追加 `updateWebhookNotifiedAt`)

**Interfaces:**
- Produces:
  - `WebhookConfigEntity(id, name, type, url, secret, customHeaders, messageTemplate, isEnabled, createTime, updateTime)`
  - `WebhookConfigMapper.selectByIds(List<Long> ids): List<WebhookConfigEntity>`
  - `SlaBreachMapper.updateWebhookNotifiedAt(List<Long> breachIds, OffsetDateTime at): void`
  - `SlaBreachActions.getWebhookIds(): List<Long>`

- [ ] **Step 1: 追加 DDL 到 conversation-service-schema.sql**

打开 `docs/sql/conversation-service-schema.sql`，在文件末尾追加：

```sql
-- Webhook 通知配置
CREATE TABLE IF NOT EXISTS cs_conversation.cs_webhook_config (
    id               BIGSERIAL     NOT NULL,
    name             VARCHAR(50)   NOT NULL,
    type             VARCHAR(10)   NOT NULL,
    url              VARCHAR(500)  NOT NULL,
    secret           VARCHAR(200),
    custom_headers   JSONB,
    message_template TEXT,
    is_enabled       SMALLINT      NOT NULL DEFAULT 1,
    create_time      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    update_time      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT uk_webhook_name UNIQUE (name)
);
COMMENT ON TABLE  cs_conversation.cs_webhook_config             IS 'SLA Webhook 通知配置';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.name        IS '配置名称，全局唯一';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.type        IS 'FEISHU | DINGTALK | WECOM | CUSTOM';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.url         IS 'Webhook 请求地址';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.secret      IS '签名密钥（飞书/钉钉需要），明文存储';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.custom_headers IS 'CUSTOM 类型自定义请求头 JSON';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.message_template IS '自定义消息模板，支持 ${变量}，空则用默认模板';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.is_enabled   IS '是否启用';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.create_time  IS '创建时间';
COMMENT ON COLUMN cs_conversation.cs_webhook_config.update_time  IS '更新时间';

CREATE TRIGGER trg_cs_webhook_config_update_time
    BEFORE UPDATE ON cs_conversation.cs_webhook_config
    FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();

-- cs_sla_breach 追加 webhook_notified_at 列
ALTER TABLE cs_conversation.cs_sla_breach
    ADD COLUMN IF NOT EXISTS webhook_notified_at TIMESTAMPTZ;
COMMENT ON COLUMN cs_conversation.cs_sla_breach.webhook_notified_at IS 'Webhook 推送时间，null=未推送';
```

- [ ] **Step 2: 在本地数据库执行 DDL**

```bash
# 连接到开发库执行
psql -U your_user -d your_db -c "\i docs/sql/conversation-service-schema.sql"
# 验证
psql -U your_user -d your_db -c "\d cs_conversation.cs_webhook_config"
psql -U your_user -d your_db -c "\d cs_conversation.cs_sla_breach" | grep webhook
```

期望：`cs_webhook_config` 表存在，`cs_sla_breach` 有 `webhook_notified_at` 列。

- [ ] **Step 3: 更新 `SlaBreachActions` 追加 webhookIds 字段**

打开 `domain/model/SlaBreachActions.java`，追加字段：

```java
package com.aria.conversation.domain.model;

import lombok.Data;
import java.util.List;

/**
 * SLA 违规行为配置（对应 cs_sla_policy.actions JSON 字段）。
 * 使用 JacksonTypeHandler 反序列化。
 */
@Data
public class SlaBreachActions {
    private boolean recordBreachOnly = true;
    private boolean sseAlert         = true;
    private boolean autoEscalate     = false;
    private String  escalateToUserId;
    /** 违规时推送的 Webhook 配置 ID 列表，空列表表示不推送 */
    private List<Long> webhookIds;
}
```

- [ ] **Step 4: 创建 WebhookConfigEntity**

创建 `infrastructure/persistence/entity/WebhookConfigEntity.java`：

```java
package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_webhook_config", autoResultMap = true)
public class WebhookConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** FEISHU | DINGTALK | WECOM | CUSTOM */
    private String type;

    private String url;

    /** 签名密钥，飞书/钉钉需要 */
    private String secret;

    /** CUSTOM 类型的自定义请求头，key=header名，value=header值 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> customHeaders;

    /** 自定义消息模板，支持 ${变量}，空则用平台默认模板 */
    private String messageTemplate;

    private Integer isEnabled;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 5: 创建 WebhookConfigMapper**

创建 `infrastructure/persistence/mapper/WebhookConfigMapper.java`：

```java
package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WebhookConfigMapper extends BaseMapper<WebhookConfigEntity> {

    /** 按 ID 列表批量查询，只返回已启用的配置 */
    default List<WebhookConfigEntity> selectEnabledByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectList(Wrappers.<WebhookConfigEntity>lambdaQuery()
                .in(WebhookConfigEntity::getId, ids)
                .eq(WebhookConfigEntity::getIsEnabled, 1));
    }
}
```

- [ ] **Step 6: 在 SlaBreachEntity 追加 webhookNotifiedAt**

打开 `infrastructure/persistence/entity/SlaBreachEntity.java`，在字段列表末尾追加：

```java
/** Webhook 推送时间，null 表示未推送 */
private OffsetDateTime webhookNotifiedAt;
```

- [ ] **Step 7: 在 SlaBreachMapper 追加 updateWebhookNotifiedAt**

打开 `infrastructure/persistence/mapper/SlaBreachMapper.java`，追加：

```java
@Update("<script>UPDATE cs_conversation.cs_sla_breach " +
        "SET webhook_notified_at = #{at} " +
        "WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
        "</script>")
void updateWebhookNotifiedAt(@Param("ids") List<Long> ids,
                              @Param("at") java.time.OffsetDateTime at);
```

- [ ] **Step 8: 编译验证**

```bash
cd ai-conversation/conversation-service && mvn compile -q 2>&1 | tail -5 && echo "BUILD OK"
```

- [ ] **Step 9: Commit**

```bash
git add docs/sql/ \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/model/SlaBreachActions.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/WebhookConfigEntity.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/entity/SlaBreachEntity.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/WebhookConfigMapper.java \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/persistence/mapper/SlaBreachMapper.java
git commit -m "feat(webhook): 新增 cs_webhook_config 表及相关 Entity/Mapper"
```

---

## Task 2: WebhookSender 接口 + 四个平台实现

**Files:**
- Create: `...infrastructure/webhook/WebhookSender.java`
- Create: `...infrastructure/webhook/SlaBreachContext.java`
- Create: `...infrastructure/webhook/FeishuWebhookSender.java`
- Create: `...infrastructure/webhook/DingtalkWebhookSender.java`
- Create: `...infrastructure/webhook/WecomWebhookSender.java`
- Create: `...infrastructure/webhook/CustomWebhookSender.java`
- Create: `...infrastructure/webhook/FeishuWebhookSenderTest.java`

**Interfaces:**
- Consumes: `WebhookConfigEntity`, `SlaBreachEntity`
- Produces:
  - `WebhookSender.supportedType(): String`
  - `WebhookSender.send(WebhookConfigEntity config, SlaBreachContext ctx): void`
  - `SlaBreachContext(sessionId, visitorName, policyName, breaches)`

- [ ] **Step 1: 创建 SlaBreachContext 值对象**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import java.util.List;

/**
 * SLA 违规现场上下文，供 WebhookSender 构造消息使用。
 */
public record SlaBreachContext(
        String sessionId,
        String visitorName,
        String policyName,
        List<SlaBreachEntity> breaches
) {}
```

- [ ] **Step 2: 创建 WebhookSender 接口**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;

/**
 * Webhook 发送器接口（Strategy 模式）。
 * 每个平台实现一个，由 WebhookDispatcher 根据 config.type 路由。
 */
public interface WebhookSender {

    /** 返回支持的平台类型字符串，如 "FEISHU"，与 WebhookConfigEntity.type 匹配 */
    String supportedType();

    /**
     * 发送 Webhook 通知。实现类负责签名、序列化和 HTTP 发送。
     * 失败时抛出 RuntimeException，由 WebhookDispatcher 统一处理重试。
     */
    void send(WebhookConfigEntity config, SlaBreachContext ctx);
}
```

- [ ] **Step 3: 创建通用 HTTP 发送辅助方法（抽象基类）**

创建 `AbstractWebhookSender.java`：

```java
package com.aria.conversation.infrastructure.webhook;

import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
public abstract class AbstractWebhookSender implements WebhookSender {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;

    protected static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    /**
     * 执行 HTTP POST 请求。
     *
     * @param url     请求 URL
     * @param headers 请求头 Map（key=header名，value=header值）
     * @param body    JSON 请求体字符串
     * @throws RuntimeException 若 HTTP 状态码非 2xx 或请求超时
     */
    protected void doPost(String url, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);

        try {
            HttpResponse<String> resp = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("Webhook HTTP " + resp.statusCode()
                        + ": " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Webhook request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Webhook request failed: " + e.getMessage(), e);
        }
    }

    /** 将模板中的 ${变量} 替换为实际值 */
    protected String renderTemplate(String template,
                                     Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** 从 SlaBreachContext 构造模板变量 Map（取第一条违规的信息） */
    protected Map<String, String> buildVariables(SlaBreachContext ctx) {
        var breach = ctx.breaches().get(0);
        String label = switch (breach.getBreachType()) {
            case "WAIT"   -> "排队等待超时";
            case "FRT"    -> "首响超时";
            case "HANDLE" -> "处理超时";
            default       -> breach.getBreachType();
        };
        return Map.of(
            "sessionId",       ctx.sessionId(),
            "visitorName",     ctx.visitorName() != null ? ctx.visitorName() : "未知访客",
            "breachType",      breach.getBreachType(),
            "breachTypeLabel", label,
            "targetSec",       String.valueOf(breach.getTargetSec()),
            "actualSec",       String.valueOf(breach.getActualSec()),
            "policyName",      ctx.policyName(),
            "breachAt",        breach.getBreachAt() != null
                               ? breach.getBreachAt().toString() : "",
            "stage",           breach.getStage()
        );
    }
}
```

- [ ] **Step 4: 编写 FeishuWebhookSender 测试（TDD）**

创建 `FeishuWebhookSenderTest.java`：

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookSenderTest {

    FeishuWebhookSender sender;

    @BeforeEach
    void setUp() { sender = new FeishuWebhookSender(); }

    @Test
    @DisplayName("supportedType 返回 FEISHU")
    void supportedType_isFeishu() {
        assertThat(sender.supportedType()).isEqualTo("FEISHU");
    }

    @Test
    @DisplayName("buildRequestBody 包含会话ID和违规类型")
    void buildRequestBody_containsSessionAndType() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com").build();
        SlaBreachEntity breach = SlaBreachEntity.builder()
                .sessionId("sess-001").breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185).build();
        SlaBreachContext ctx = new SlaBreachContext("sess-001", "张三", "VIP-SLA", List.of(breach));

        String body = sender.buildRequestBody(config, ctx);

        assertThat(body).contains("sess-001");
        assertThat(body).contains("排队等待超时");
    }
}
```

- [ ] **Step 5: 运行测试（验证失败）**

```bash
cd ai-conversation/conversation-service && mvn test -Dtest=FeishuWebhookSenderTest -q 2>&1 | tail -5
```

期望：编译失败（FeishuWebhookSender 不存在）。

- [ ] **Step 6: 实现 FeishuWebhookSender**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 飞书 Webhook 发送器。
 * 签名算法：HMAC-SHA256(timestamp + "\n" + secret)，Base64 编码。
 * 消息格式：interactive 卡片或默认 text 消息。
 */
@Slf4j
@Component
public class FeishuWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "FEISHU"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        String body = buildRequestBody(config, ctx);
        String url  = config.getUrl();

        Map<String, String> headers = Map.of();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = sign(timestamp, config.getSecret());
            // 飞书签名通过 JSON body 携带，不通过 header
            body = injectSignature(body, timestamp, sign);
        }
        doPost(url, headers, body);
    }

    /** 构造请求体（供测试调用） */
    String buildRequestBody(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);

        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            return renderTemplate(config.getMessageTemplate(), vars);
        }
        // 默认飞书 text 消息
        return """
                {
                  "msg_type": "text",
                  "content": {
                    "text": "⚠️ SLA %s 违规\\n会话：%s\\n访客：%s\\n策略：%s\\n目标：%ss｜实际：%ss"
                  }
                }
                """.formatted(
                vars.get("breachTypeLabel"), vars.get("sessionId"),
                vars.get("visitorName"), vars.get("policyName"),
                vars.get("targetSec"), vars.get("actualSec"));
    }

    private String sign(long timestamp, String secret) {
        try {
            String content = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("飞书签名失败", e);
        }
    }

    private String injectSignature(String body, long timestamp, String sign) {
        // 在 JSON 根对象中注入 timestamp 和 sign 字段
        return body.trim().replaceFirst("^\\{", "{"
                + "\"timestamp\":\"" + timestamp + "\","
                + "\"sign\":\"" + sign + "\",");
    }
}
```

- [ ] **Step 7: 实现 DingtalkWebhookSender**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉 Webhook 发送器。
 * 签名算法：HMAC-SHA256(timestamp + "\n" + secret)，
 * 签名和时间戳通过 URL 参数传递：?timestamp=xxx&sign=xxx
 */
@Slf4j
@Component
public class DingtalkWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "DINGTALK"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            body = renderTemplate(config.getMessageTemplate(), vars);
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "title": "SLA违规告警",
                        "text": "### ⚠️ SLA %s 违规\\n- 会话：%s\\n- 访客：%s\\n- 策略：%s\\n- 目标：%ss｜实际：%ss"
                      }
                    }
                    """.formatted(
                    vars.get("breachTypeLabel"), vars.get("sessionId"),
                    vars.get("visitorName"), vars.get("policyName"),
                    vars.get("targetSec"), vars.get("actualSec"));
        }

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

    private String sign(long timestamp, String secret) {
        try {
            String content = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("钉钉签名失败", e);
        }
    }
}
```

- [ ] **Step 8: 实现 WecomWebhookSender**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业微信 Webhook 发送器。
 * 企业微信无需签名，直接 POST 到 Webhook URL。
 */
@Slf4j
@Component
public class WecomWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "WECOM"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            body = renderTemplate(config.getMessageTemplate(), vars);
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "content": "## ⚠️ SLA %s 违规\\n> 会话：%s\\n> 访客：%s\\n> 策略：%s\\n> 目标：%ss / 实际：%ss"
                      }
                    }
                    """.formatted(
                    vars.get("breachTypeLabel"), vars.get("sessionId"),
                    vars.get("visitorName"), vars.get("policyName"),
                    vars.get("targetSec"), vars.get("actualSec"));
        }
        doPost(config.getUrl(), Map.of(), body);
    }
}
```

- [ ] **Step 9: 实现 CustomWebhookSender**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 自定义 Webhook 发送器。
 * 用户自定义请求头和消息模板，不内置签名逻辑。
 */
@Slf4j
@Component
public class CustomWebhookSender extends AbstractWebhookSender {

    private static final String DEFAULT_TEMPLATE =
            "{\"message\":\"SLA ${breachTypeLabel} 违规，会话：${sessionId}，"
            + "目标：${targetSec}s，实际：${actualSec}s\"}";

    @Override
    public String supportedType() { return "CUSTOM"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String template = (config.getMessageTemplate() != null
                && !config.getMessageTemplate().isBlank())
                ? config.getMessageTemplate() : DEFAULT_TEMPLATE;
        String body = renderTemplate(template, vars);

        Map<String, String> headers = new HashMap<>();
        if (config.getCustomHeaders() != null) {
            config.getCustomHeaders().forEach(headers::put);
        }
        doPost(config.getUrl(), headers, body);
    }
}
```

- [ ] **Step 10: 运行测试**

```bash
cd ai-conversation/conversation-service && mvn test -Dtest=FeishuWebhookSenderTest -q 2>&1 | tail -5
```

期望：2 个测试 PASS。

- [ ] **Step 11: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(webhook): 新增 WebhookSender 接口及飞书/钉钉/企微/自定义实现"
```

---

## Task 3: WebhookDispatcher + 异步线程池

**Files:**
- Create: `...infrastructure/webhook/WebhookDispatcher.java`
- Modify: `ai-conversation/conversation-service/src/main/resources/application.yml` (追加线程池配置)
- Create: `...infrastructure/config/WebhookExecutorConfig.java`
- Create: `...infrastructure/webhook/WebhookDispatcherTest.java`

**Interfaces:**
- Consumes: `List<WebhookSender>` (Spring 自动注入所有实现), `WebhookConfigMapper`, `SlaBreachMapper`
- Produces:
  - `WebhookDispatcher.dispatch(List<Long> webhookIds, SlaBreachContext ctx, List<Long> breachIds): void`

- [ ] **Step 1: 追加线程池配置到 application.yml**

打开 `ai-conversation/conversation-service/src/main/resources/application.yml`，在 `sla:` 块下追加：

```yaml
sla:
  # ... 现有配置 ...
  webhook:
    core-pool-size: 2
    max-pool-size: 5
    queue-capacity: 50
```

- [ ] **Step 2: 创建线程池配置类**

创建 `infrastructure/config/WebhookExecutorConfig.java`：

```java
package com.aria.conversation.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Webhook 异步推送线程池配置。
 * 独立线程池，与 SLA 扫描主线程隔离，避免 Webhook 超时阻塞分片扫描。
 */
@Configuration
public class WebhookExecutorConfig {

    @Value("${sla.webhook.core-pool-size:2}")
    private int corePoolSize;

    @Value("${sla.webhook.max-pool-size:5}")
    private int maxPoolSize;

    @Value("${sla.webhook.queue-capacity:50}")
    private int queueCapacity;

    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-");
        // 队列满时降级为调用方线程同步执行，不丢失告警
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 3: 编写 WebhookDispatcher 测试（TDD）**

创建 `WebhookDispatcherTest.java`：

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock WebhookConfigMapper webhookConfigMapper;
    @Mock SlaBreachMapper     slaBreachMapper;
    @Mock WebhookSender       feishuSender;

    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(feishuSender.supportedType()).thenReturn("FEISHU");
        dispatcher = new WebhookDispatcher(
                List.of(feishuSender), webhookConfigMapper, slaBreachMapper);
    }

    @Test
    @DisplayName("启用的 Webhook 配置调用对应 sender.send()")
    void dispatch_callsSenderForEnabledConfig() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .id(1L).type("FEISHU").url("https://example.com").isEnabled(1).build();
        when(webhookConfigMapper.selectEnabledByIds(List.of(1L))).thenReturn(List.of(config));

        SlaBreachEntity breach = SlaBreachEntity.builder()
                .id(10L).sessionId("s1").breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185).build();
        SlaBreachContext ctx = new SlaBreachContext("s1", "张三", "默认SLA", List.of(breach));

        dispatcher.dispatch(List.of(1L), ctx, List.of(10L));

        verify(feishuSender).send(eq(config), eq(ctx));
        verify(slaBreachMapper).updateWebhookNotifiedAt(eq(List.of(10L)), any());
    }

    @Test
    @DisplayName("sender.send() 抛出异常时不影响其他 Webhook 执行")
    void dispatch_senderException_doesNotAbortOthers() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .id(1L).type("FEISHU").url("https://example.com").isEnabled(1).build();
        when(webhookConfigMapper.selectEnabledByIds(any())).thenReturn(List.of(config));
        doThrow(new RuntimeException("timeout")).when(feishuSender).send(any(), any());

        SlaBreachContext ctx = new SlaBreachContext("s1", "张三", "默认SLA",
                List.of(SlaBreachEntity.builder().breachType("WAIT").stage("BREACH")
                        .targetSec(120).actualSec(185).build()));

        // 不应抛出异常
        dispatcher.dispatch(List.of(1L), ctx, List.of(10L));

        // 发送失败时不标记 webhook_notified_at
        verify(slaBreachMapper, never()).updateWebhookNotifiedAt(any(), any());
    }

    @Test
    @DisplayName("webhookIds 为空时不调用任何 sender")
    void dispatch_emptyIds_doesNothing() {
        dispatcher.dispatch(List.of(), new SlaBreachContext("s1", "张三", "SLA", List.of()), List.of());
        verifyNoInteractions(webhookConfigMapper, feishuSender);
    }
}
```

- [ ] **Step 4: 运行测试（验证失败）**

```bash
cd ai-conversation/conversation-service && mvn test -Dtest=WebhookDispatcherTest -q 2>&1 | tail -5
```

期望：编译失败（WebhookDispatcher 不存在）。

- [ ] **Step 5: 实现 WebhookDispatcher**

```java
package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook 分发器。
 *
 * <p>根据 Webhook 配置的 type 字段路由到对应 {@link WebhookSender} 实现，
 * 通过 {@code @Async("webhookExecutor")} 在独立线程池中执行，
 * 不阻塞 SLA 扫描主线程。
 *
 * <p>失败时记录 ERROR 日志但不重抛异常（重试逻辑由调用方决策）。
 */
@Slf4j
@Component
public class WebhookDispatcher {

    private final Map<String, WebhookSender>  senders;
    private final WebhookConfigMapper          webhookConfigMapper;
    private final SlaBreachMapper              slaBreachMapper;

    /** Spring 自动注入所有 WebhookSender 实现，按 supportedType() 建立路由表 */
    public WebhookDispatcher(List<WebhookSender> senderList,
                              WebhookConfigMapper webhookConfigMapper,
                              SlaBreachMapper slaBreachMapper) {
        this.senders = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        this.webhookConfigMapper = webhookConfigMapper;
        this.slaBreachMapper     = slaBreachMapper;
    }

    /**
     * 异步分发 Webhook 通知。
     *
     * @param webhookIds 需要推送的 Webhook 配置 ID 列表
     * @param ctx        SLA 违规现场上下文
     * @param breachIds  本次违规记录 ID 列表（推送成功后更新 webhook_notified_at）
     */
    @Async("webhookExecutor")
    public void dispatch(List<Long> webhookIds, SlaBreachContext ctx, List<Long> breachIds) {
        if (webhookIds == null || webhookIds.isEmpty()) return;

        List<WebhookConfigEntity> configs =
                webhookConfigMapper.selectEnabledByIds(webhookIds);

        boolean anySuccess = false;
        for (WebhookConfigEntity config : configs) {
            WebhookSender sender = senders.get(config.getType());
            if (sender == null) {
                log.warn("[Webhook] 未找到类型 {} 的 Sender，跳过 id={}", config.getType(), config.getId());
                continue;
            }
            try {
                sendWithRetry(sender, config, ctx);
                anySuccess = true;
                log.info("[Webhook] 推送成功 id={} type={} session={}",
                         config.getId(), config.getType(), ctx.sessionId());
            } catch (Exception e) {
                log.error("[Webhook] 推送失败 id={} type={} session={}",
                          config.getId(), config.getType(), ctx.sessionId(), e);
            }
        }

        // 至少一个成功时才标记通知时间
        if (anySuccess && !breachIds.isEmpty()) {
            slaBreachMapper.updateWebhookNotifiedAt(breachIds, OffsetDateTime.now());
        }
    }

    /**
     * 带重试的发送（指数退避：1s / 3s / 9s，最多 3 次）。
     */
    private void sendWithRetry(WebhookSender sender, WebhookConfigEntity config,
                                SlaBreachContext ctx) {
        Exception lastEx = null;
        long delaySec = 1;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sender.send(config, ctx);
                return;  // 成功直接返回
            } catch (Exception e) {
                lastEx = e;
                log.warn("[Webhook] 第 {}/3 次发送失败 id={}: {}", attempt, config.getId(), e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(delaySec * 1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted during retry", ie);
                    }
                    delaySec *= 3;
                }
            }
        }
        throw new RuntimeException("Webhook 重试 3 次全部失败", lastEx);
    }
}
```

- [ ] **Step 6: 运行测试**

```bash
cd ai-conversation/conversation-service && mvn test -Dtest=WebhookDispatcherTest -q 2>&1 | tail -5
```

期望：3 个测试 PASS。

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(webhook): 新增 WebhookDispatcher 及异步线程池配置"
```

---

## Task 4: SlaBreachNotifier 扩展 + WebhookAppService

**Files:**
- Modify: `...infrastructure/scheduler/SlaBreachNotifier.java`
- Create: `...application/service/WebhookAppService.java`

**Interfaces:**
- Consumes: `WebhookDispatcher`, `WebhookConfigMapper`, `WebhookSender` (for test connection)
- Produces:
  - `WebhookAppService.listWebhooks(): List<WebhookConfigEntity>`
  - `WebhookAppService.createWebhook(req): WebhookConfigEntity`
  - `WebhookAppService.updateWebhook(id, req): void`
  - `WebhookAppService.deleteWebhook(id): void`
  - `WebhookAppService.testWebhook(id): void`

- [ ] **Step 1: 扩展 SlaBreachNotifier 追加 Webhook 分发**

打开 `infrastructure/scheduler/SlaBreachNotifier.java`，在构造函数追加 `WebhookDispatcher` 参数，在 `notifyBatch()` 末尾追加：

在构造函数参数列表末尾加：
```java
WebhookDispatcher webhookDispatcher
```
字段声明：
```java
private final WebhookDispatcher webhookDispatcher;
```
构造函数赋值：
```java
this.webhookDispatcher = webhookDispatcher;
```

在 `notifyBatch()` 方法的自动升级代码段之后追加：

```java
// Webhook 推送（异步，不阻塞主线程）
List<Long> webhookIds = actions.getWebhookIds();
if (webhookIds != null && !webhookIds.isEmpty()) {
    List<Long> allBreachIds = newBreaches.stream()
            .map(SlaBreachEntity::getId).toList();
    SlaBreachContext webhookCtx = new SlaBreachContext(
            session.getSessionId(),
            session.getVisitorName(),
            policy.getName(),
            newBreaches);
    webhookDispatcher.dispatch(webhookIds, webhookCtx, allBreachIds);
}
```

同时在文件顶部追加缺失的 import：
```java
import com.aria.conversation.infrastructure.webhook.SlaBreachContext;
import com.aria.conversation.infrastructure.webhook.WebhookDispatcher;
```

- [ ] **Step 2: 创建 WebhookAppService**

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import com.aria.conversation.infrastructure.webhook.SlaBreachContext;
import com.aria.conversation.infrastructure.webhook.WebhookDispatcher;
import com.aria.conversation.infrastructure.webhook.WebhookSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookAppService {

    private static final int NOT_FOUND = 40400;
    private static final int CONFLICT  = 40900;

    private final WebhookConfigMapper        webhookConfigMapper;
    private final List<WebhookSender>        senderList;

    public List<WebhookConfigEntity> listWebhooks() {
        return webhookConfigMapper.selectList(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WebhookConfigEntity createWebhook(WebhookConfigEntity entity) {
        if (webhookConfigMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WebhookConfigEntity>lambdaQuery()
                        .eq(WebhookConfigEntity::getName, entity.getName())) != null) {
            throw new BusinessException(CONFLICT, "Webhook 名称已存在: " + entity.getName());
        }
        webhookConfigMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateWebhook(Long id, WebhookConfigEntity update) {
        WebhookConfigEntity existing = webhookConfigMapper.selectById(id);
        if (existing == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);
        update.setId(id);
        webhookConfigMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWebhook(Long id) {
        webhookConfigMapper.deleteById(id);
    }

    /**
     * 发送测试消息，验证 Webhook 配置可达。
     * 发送一条模拟违规消息，成功则返回，失败抛出 BusinessException。
     */
    public void testWebhook(Long id) {
        WebhookConfigEntity config = webhookConfigMapper.selectById(id);
        if (config == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);

        Map<String, WebhookSender> senderMap = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        WebhookSender sender = senderMap.get(config.getType());
        if (sender == null) {
            throw new BusinessException(40001, "不支持的 Webhook 类型: " + config.getType());
        }

        // 构造模拟违规上下文
        SlaBreachEntity mockBreach = SlaBreachEntity.builder()
                .sessionId("test-session")
                .breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185)
                .build();
        SlaBreachContext mockCtx = new SlaBreachContext(
                "test-session", "测试访客", "测试策略", List.of(mockBreach));
        try {
            sender.send(config, mockCtx);
        } catch (Exception e) {
            throw new BusinessException(500, "Webhook 测试发送失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd ai-conversation/conversation-service && mvn compile -q 2>&1 | tail -5 && echo "BUILD OK"
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(webhook): 扩展 SlaBreachNotifier 集成 WebhookDispatcher，新增 WebhookAppService"
```

---

## Task 5: WebhookController REST API

**Files:**
- Create: `...interfaces/rest/WebhookController.java`

**Interfaces:**
- Consumes: `WebhookAppService`
- Produces:
  - `GET    /api/v1/admin/sla/webhooks`
  - `POST   /api/v1/admin/sla/webhooks`
  - `PUT    /api/v1/admin/sla/webhooks/{id}`
  - `DELETE /api/v1/admin/sla/webhooks/{id}`
  - `POST   /api/v1/admin/sla/webhooks/{id}/test`

- [ ] **Step 1: 创建 WebhookController**

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.WebhookAppService;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SLA Webhook 通知配置 Controller。
 * 管理飞书/钉钉/企微/自定义 Webhook 配置，供 SLA 策略绑定后推送告警。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/sla/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookAppService webhookAppService;

    @GetMapping
    @SaCheckPermission("system:sla:manage")
    public R<List<WebhookConfigEntity>> list() {
        return R.ok(webhookAppService.listWebhooks());
    }

    @PostMapping
    @SaCheckPermission("system:sla:manage")
    public R<WebhookConfigEntity> create(@RequestBody @Valid WebhookReq req) {
        WebhookConfigEntity entity = buildEntity(null, req);
        return R.ok(webhookAppService.createWebhook(entity));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> update(@PathVariable Long id,
                           @RequestBody @Valid WebhookReq req) {
        webhookAppService.updateWebhook(id, buildEntity(id, req));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> delete(@PathVariable Long id) {
        webhookAppService.deleteWebhook(id);
        return R.ok();
    }

    /**
     * 发送测试消息，验证 Webhook 配置可用。
     * 发送一条模拟告警，前端展示成功/失败反馈。
     */
    @PostMapping("/{id}/test")
    @SaCheckPermission("system:sla:manage")
    public R<Void> test(@PathVariable Long id) {
        webhookAppService.testWebhook(id);
        return R.ok();
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class WebhookReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String type;           // FEISHU | DINGTALK | WECOM | CUSTOM
        @NotBlank private String url;
        private String secret;                   // 飞书/钉钉签名密钥，可空
        private Map<String, String> customHeaders; // CUSTOM 类型专用
        private String messageTemplate;          // 自定义模板，空则用默认
        private Integer isEnabled = 1;
    }

    private WebhookConfigEntity buildEntity(Long id, WebhookReq req) {
        return WebhookConfigEntity.builder()
                .id(id)
                .name(req.getName())
                .type(req.getType())
                .url(req.getUrl())
                .secret(req.getSecret())
                .customHeaders(req.getCustomHeaders())
                .messageTemplate(req.getMessageTemplate())
                .isEnabled(req.getIsEnabled())
                .build();
    }
}
```

- [ ] **Step 2: 编译 + 全量测试**

```bash
cd ai-conversation/conversation-service && mvn test -q 2>&1 | grep -E "BUILD|Tests run:" | tail -5
```

期望：BUILD SUCCESS，全部测试通过。

- [ ] **Step 3: 手动测试（curl）**

启动服务后验证接口可访问：
```bash
# 创建一个飞书 Webhook
curl -X POST http://localhost:8080/api/v1/admin/sla/webhooks \
  -H "Content-Type: application/json" \
  -d '{"name":"研发飞书群","type":"FEISHU","url":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","secret":"your_secret","isEnabled":1}'

# 查询列表
curl http://localhost:8080/api/v1/admin/sla/webhooks
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(webhook): 新增 WebhookController REST API（CRUD + 测试发送）"
```

---
