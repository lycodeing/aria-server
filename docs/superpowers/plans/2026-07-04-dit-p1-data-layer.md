# DIT 框架 P1：数据层（DB + Entity + Mapper + 配置仓储）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Domain-Intent-Tool 框架的数据基础——7 张表的 SQL 迁移、MyBatis-Plus Entity/Mapper、以及带 Redis 缓存的配置仓储层。

**Architecture:** 复用项目已有规范：Entity 用 `@TableName(value="cs_conversation.xxx")` + `@Getter @Setter`，JSONB 字段用 `JsonbTypeHandler`，Mapper 继承 `BaseMapper`，Redis 用 `RedisCacheHelper.set/get/delete`。DomainRepository 封装"Redis 10分钟缓存 + DB 兜底"模式，管理后台修改配置后主动失效缓存。

**Tech Stack:** MyBatis-Plus 3.5.7, PostgreSQL (cs_conversation schema), Redis (RedisCacheHelper), JUnit 5 + Mockito

---

## 文件改动总览

| 操作 | 文件路径 | 说明 |
|---|---|---|
| 新建 SQL | `docs/sql/migration-002-dit-tables.sql` | 7 张 DIT 表 |
| 新建 | `infrastructure/dit/domain/DomainDO.java` | 领域表 Entity |
| 新建 | `infrastructure/dit/domain/IntentDO.java` | 意图表 Entity |
| 新建 | `infrastructure/dit/domain/IntentSlotDO.java` | 槽位表 Entity |
| 新建 | `infrastructure/dit/domain/ToolDO.java` | 工具表 Entity |
| 新建 | `infrastructure/dit/domain/IntentToolDO.java` | 意图-工具绑定 Entity |
| 新建 | `infrastructure/dit/domain/ToolCallLogDO.java` | 工具调用日志 Entity |
| 新建 | `infrastructure/dit/mapper/DomainMapper.java` | 领域 Mapper |
| 新建 | `infrastructure/dit/mapper/IntentMapper.java` | 意图 Mapper |
| 新建 | `infrastructure/dit/mapper/IntentSlotMapper.java` | 槽位 Mapper |
| 新建 | `infrastructure/dit/mapper/ToolMapper.java` | 工具 Mapper |
| 新建 | `infrastructure/dit/mapper/IntentToolMapper.java` | 绑定 Mapper |
| 新建 | `infrastructure/dit/mapper/ToolCallLogMapper.java` | 日志 Mapper |
| 新建 | `infrastructure/dit/config/DomainConfig.java` | 领域配置 record（缓存对象） |
| 新建 | `infrastructure/dit/config/IntentConfig.java` | 意图配置 record |
| 新建 | `infrastructure/dit/config/SlotConfig.java` | 槽位配置 record |
| 新建 | `infrastructure/dit/config/ToolConfig.java` | 工具配置 record |
| 新建 | `infrastructure/dit/config/IntentToolBinding.java` | 绑定配置 record |
| 新建 | `infrastructure/dit/repository/DomainRepository.java` | 领域配置仓储（含 Redis 缓存） |
| 新建 | `infrastructure/dit/repository/PendingSlotRepository.java` | 挂起状态 Redis 仓储 |
| 新建测试 | `test/.../dit/repository/DomainRepositoryTest.java` | 仓储层单元测试 |

所有 Java 文件的基础包路径：
`com.aria.conversation.infrastructure.dit`

---

## Task 1: SQL 迁移文件

**Files:**
- Create: `docs/sql/migration-002-dit-tables.sql`

- [ ] **Step 1: 创建 SQL 文件**

```sql
-- ============================================================
-- DIT 框架表结构迁移
-- migration-002-dit-tables.sql
-- 执行前提：cs_conversation schema 已存在
-- ============================================================

-- 1. 领域/场景表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_domain (
    id                  BIGSERIAL    PRIMARY KEY,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    system_prompt_addon TEXT,
    knowledge_base_id   BIGINT,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_domain_code UNIQUE (code)
);
COMMENT ON TABLE  cs_conversation.cs_domain IS '领域/场景配置表';
COMMENT ON COLUMN cs_conversation.cs_domain.code IS '前端传入的领域标识，如 ecommerce';
COMMENT ON COLUMN cs_conversation.cs_domain.system_prompt_addon IS '追加到 system prompt 的领域专属说明';

-- 2. 意图表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent (
    id              BIGSERIAL    PRIMARY KEY,
    domain_id       BIGINT       NOT NULL REFERENCES cs_conversation.cs_domain(id),
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT         NOT NULL,
    example_queries JSONB        NOT NULL DEFAULT '[]',
    auto_transfer   BOOLEAN      NOT NULL DEFAULT FALSE,
    skip_rag        BOOLEAN      NOT NULL DEFAULT FALSE,
    fallback_reply  TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_intent_domain_code UNIQUE (domain_id, code)
);
COMMENT ON TABLE  cs_conversation.cs_intent IS '意图定义表';
COMMENT ON COLUMN cs_conversation.cs_intent.example_queries IS '少样本示例，JSON 数组，如 ["查订单","我的包裹到哪了"]';
COMMENT ON COLUMN cs_conversation.cs_intent.auto_transfer   IS 'true=命中后自动转人工（投诉/敏感操作）';
COMMENT ON COLUMN cs_conversation.cs_intent.skip_rag        IS 'true=跳过 RAG 检索';

-- 3. 槽位定义表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_slot (
    id                    BIGSERIAL    PRIMARY KEY,
    intent_id             BIGINT       NOT NULL REFERENCES cs_conversation.cs_intent(id),
    slot_name             VARCHAR(64)  NOT NULL,
    slot_type             VARCHAR(32)  NOT NULL DEFAULT 'string',
    description           VARCHAR(256) NOT NULL,
    required              BOOLEAN      NOT NULL DEFAULT FALSE,
    resolve_strategy      JSONB        NOT NULL DEFAULT '["EXTRACT","SESSION","DISCOVER","ASK_USER"]',
    session_key           VARCHAR(64),
    discover_tool_code    VARCHAR(64),
    discover_fixed_params JSONB        DEFAULT '{}',
    ask_user_prompt       VARCHAR(256),
    enum_values           JSONB,
    sort_order            INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_slot_intent_name UNIQUE (intent_id, slot_name)
);
COMMENT ON COLUMN cs_conversation.cs_intent_slot.resolve_strategy   IS '解析策略优先级，JSON 数组，按顺序尝试';
COMMENT ON COLUMN cs_conversation.cs_intent_slot.discover_tool_code IS 'DISCOVER 级使用的发现工具 code';

-- 4. 工具注册表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_tool (
    id                BIGSERIAL    PRIMARY KEY,
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       TEXT         NOT NULL,
    tool_type         VARCHAR(32)  NOT NULL DEFAULT 'HTTP',
    http_method       VARCHAR(16),
    url_template      VARCHAR(512),
    headers_template  JSONB        DEFAULT '{}',
    body_template     JSONB,
    param_schema      JSONB        NOT NULL DEFAULT '{}',
    response_jsonpath VARCHAR(256),
    auth_type         VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    auth_config       JSONB        DEFAULT '{}',
    timeout_ms        INT          NOT NULL DEFAULT 5000,
    is_discover_tool  BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tool_code UNIQUE (code)
);
COMMENT ON TABLE  cs_conversation.cs_tool IS '工具注册表（HTTP 调用或内置 Java 实现）';
COMMENT ON COLUMN cs_conversation.cs_tool.tool_type        IS 'HTTP=通用 HTTP 调用, BUILTIN=Java 内置实现';
COMMENT ON COLUMN cs_conversation.cs_tool.url_template     IS 'URL 模板，支持 {slot_name} 路径参数';
COMMENT ON COLUMN cs_conversation.cs_tool.param_schema     IS '参数 JSON Schema，供 LLM Function Calling 使用';
COMMENT ON COLUMN cs_conversation.cs_tool.is_discover_tool IS 'true=可作为槽位 DISCOVER 级发现工具';

-- 5. 意图-工具绑定表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_intent_tool (
    id               BIGSERIAL   PRIMARY KEY,
    intent_id        BIGINT      NOT NULL REFERENCES cs_conversation.cs_intent(id),
    tool_id          BIGINT      NOT NULL REFERENCES cs_conversation.cs_tool(id),
    execution_mode   VARCHAR(16) NOT NULL DEFAULT 'OPTIONAL',
    execution_order  INT         NOT NULL DEFAULT 0,
    param_mappings   JSONB       NOT NULL DEFAULT '{}',
    CONSTRAINT uq_intent_tool UNIQUE (intent_id, tool_id)
);
COMMENT ON COLUMN cs_conversation.cs_intent_tool.execution_mode  IS 'REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策';
COMMENT ON COLUMN cs_conversation.cs_intent_tool.param_mappings  IS '参数来源映射，JSON，key=工具参数名，value={source,key}';

-- 6. 工具调用日志
CREATE TABLE IF NOT EXISTS cs_conversation.cs_tool_call_log (
    id           BIGSERIAL   PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL,
    tool_code    VARCHAR(64) NOT NULL,
    intent_code  VARCHAR(64),
    domain_code  VARCHAR(64),
    params       JSONB,
    response     TEXT,
    status       VARCHAR(16) NOT NULL,
    http_status  INT,
    duration_ms  INT,
    error_msg    TEXT,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_session ON cs_conversation.cs_tool_call_log(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_created ON cs_conversation.cs_tool_call_log(created_at);
COMMENT ON TABLE  cs_conversation.cs_tool_call_log IS '工具调用日志（调试+监控）';
COMMENT ON COLUMN cs_conversation.cs_tool_call_log.status IS 'SUCCESS/ERROR/TIMEOUT/SKIPPED';

-- 7. 对话 pipeline 挂起状态表
CREATE TABLE IF NOT EXISTS cs_conversation.cs_pending_slot (
    session_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    domain_code    VARCHAR(64) NOT NULL,
    intent_code    VARCHAR(64) NOT NULL,
    pending_slot   VARCHAR(64) NOT NULL,
    pending_type   VARCHAR(16) NOT NULL,
    candidates     JSONB,
    resolved_slots JSONB       NOT NULL DEFAULT '{}',
    retry_count    INT         NOT NULL DEFAULT 0,
    expires_at     TIMESTAMP   NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  cs_conversation.cs_pending_slot IS '槽位解析挂起状态，用于多轮对话中间状态恢复';
COMMENT ON COLUMN cs_conversation.cs_pending_slot.pending_type IS 'DISCOVERED=候选项待选, MISSING=等待用户输入';
```

- [ ] **Step 2: 提交**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
git add docs/sql/migration-002-dit-tables.sql
git commit -m "feat(dit): add DIT framework table DDL (migration-002)"
```

---
## Task 2: Entity 类（6 个领域对象）

所有 Entity 放在包 `com.aria.conversation.infrastructure.dit.domain`，对应路径：
`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/`

**Files:**
- Create: `infrastructure/dit/domain/DomainDO.java`
- Create: `infrastructure/dit/domain/IntentDO.java`
- Create: `infrastructure/dit/domain/IntentSlotDO.java`
- Create: `infrastructure/dit/domain/ToolDO.java`
- Create: `infrastructure/dit/domain/IntentToolDO.java`
- Create: `infrastructure/dit/domain/ToolCallLogDO.java`

- [ ] **Step 1: 创建 DomainDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cs_conversation.cs_domain")
public class DomainDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 前端传入的领域标识，如 "ecommerce" */
    private String code;

    private String name;
    private String description;

    /** 追加到 system prompt 的领域专属说明 */
    private String systemPromptAddon;

    /** 领域专属知识库 ID，null 时使用全局知识库 */
    private Long knowledgeBaseId;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 IntentDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.common.web.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_intent", autoResultMap = true)
public class IntentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long domainId;
    private String code;
    private String name;
    private String description;

    /** 少样本示例，JSON 数组字符串，如 ["查订单","我的包裹到哪了"] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String exampleQueries;

    /** true=命中后自动转人工 */
    private Boolean autoTransfer;

    /** true=跳过 RAG 检索 */
    private Boolean skipRag;

    /** 工具失败时的兜底回复 */
    private String fallbackReply;

    private Boolean enabled;
    private Integer sortOrder;
}
```

- [ ] **Step 3: 创建 IntentSlotDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.common.web.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_intent_slot", autoResultMap = true)
public class IntentSlotDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long intentId;
    private String slotName;

    /** string / number / date / enum */
    private String slotType;

    private String description;
    private Boolean required;

    /**
     * 解析策略优先级，JSON 数组，如 ["EXTRACT","SESSION","DISCOVER","ASK_USER"]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resolveStrategy;

    /** SESSION 级：从会话上下文取的 key */
    private String sessionKey;

    /** DISCOVER 级：调用的发现工具 code */
    private String discoverToolCode;

    /** DISCOVER 工具的额外固定参数，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String discoverFixedParams;

    /** ASK_USER 级：询问话术 */
    private String askUserPrompt;

    /** enum 类型时的可选值，JSON 数组 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String enumValues;

    private Integer sortOrder;
}
```

- [ ] **Step 4: 创建 ToolDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.common.web.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_tool", autoResultMap = true)
public class ToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String description;

    /** HTTP=通用 HTTP 调用，BUILTIN=Java 内置实现 */
    private String toolType;

    /** GET / POST / PUT / DELETE */
    private String httpMethod;

    /** URL 模板，支持 {slot_name} 占位符 */
    private String urlTemplate;

    /** 请求头模板，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String headersTemplate;

    /** 请求体模板（POST），JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String bodyTemplate;

    /** 参数 JSON Schema，供 LLM Function Calling 使用 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String paramSchema;

    /** 从响应中提取结果的 JSONPath，如 "$.data" */
    private String responseJsonpath;

    /** NONE / API_KEY / BEARER / BASIC */
    private String authType;

    /** 认证配置，AES 加密存储，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String authConfig;

    private Integer timeoutMs;

    /** true=可作为槽位 DISCOVER 级发现工具 */
    private Boolean isDiscoverTool;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: 创建 IntentToolDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.common.web.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_intent_tool", autoResultMap = true)
public class IntentToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long intentId;
    private Long toolId;

    /** REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策 */
    private String executionMode;

    /** REQUIRED 工具的串行执行顺序 */
    private Integer executionOrder;

    /**
     * 参数来源映射，JSON。
     * 格式：{"order_id":{"source":"slot","key":"order_id"}}
     * source 取值：slot / session / literal
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String paramMappings;
}
```

- [ ] **Step 6: 创建 ToolCallLogDO**

```java
package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.common.web.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_tool_call_log", autoResultMap = true)
public class ToolCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String toolCode;
    private String intentCode;
    private String domainCode;

    /** 实际发送的参数（脱敏，不含 token/password） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String params;

    /** HTTP 原始响应摘要（截断至 2000 字符） */
    private String response;

    /** SUCCESS / ERROR / TIMEOUT / SKIPPED */
    private String status;

    private Integer httpStatus;
    private Integer durationMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 7: 编译确认**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/domain/
git commit -m "feat(dit): add DIT domain Entity classes (DomainDO, IntentDO, SlotDO, ToolDO, etc.)"
```

---
## Task 3: Mapper 接口（6 个）

所有 Mapper 放在包 `com.aria.conversation.infrastructure.dit.mapper`，路径：
`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/mapper/`

**Files:**
- Create: `infrastructure/dit/mapper/DomainMapper.java`
- Create: `infrastructure/dit/mapper/IntentMapper.java`
- Create: `infrastructure/dit/mapper/IntentSlotMapper.java`
- Create: `infrastructure/dit/mapper/ToolMapper.java`
- Create: `infrastructure/dit/mapper/IntentToolMapper.java`
- Create: `infrastructure/dit/mapper/ToolCallLogMapper.java`

- [ ] **Step 1: 创建 DomainMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface DomainMapper extends BaseMapper<DomainDO> {

    @Select("SELECT * FROM cs_conversation.cs_domain WHERE code = #{code} AND enabled = TRUE LIMIT 1")
    Optional<DomainDO> findByCode(String code);
}
```

- [ ] **Step 2: 创建 IntentMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentMapper extends BaseMapper<IntentDO> {

    @Select("SELECT * FROM cs_conversation.cs_intent " +
            "WHERE domain_id = #{domainId} AND enabled = TRUE ORDER BY sort_order ASC")
    List<IntentDO> findByDomainId(Long domainId);
}
```

- [ ] **Step 3: 创建 IntentSlotMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentSlotMapper extends BaseMapper<IntentSlotDO> {

    @Select("SELECT * FROM cs_conversation.cs_intent_slot " +
            "WHERE intent_id = #{intentId} ORDER BY sort_order ASC")
    List<IntentSlotDO> findByIntentId(Long intentId);
}
```

- [ ] **Step 4: 创建 ToolMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ToolMapper extends BaseMapper<ToolDO> {

    @Select("SELECT * FROM cs_conversation.cs_tool WHERE code = #{code} AND enabled = TRUE LIMIT 1")
    Optional<ToolDO> findByCode(String code);
}
```

- [ ] **Step 5: 创建 IntentToolMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentToolMapper extends BaseMapper<IntentToolDO> {

    @Select("SELECT it.* FROM cs_conversation.cs_intent_tool it " +
            "JOIN cs_conversation.cs_tool t ON it.tool_id = t.id " +
            "WHERE it.intent_id = #{intentId} AND t.enabled = TRUE " +
            "ORDER BY it.execution_order ASC")
    List<IntentToolDO> findByIntentId(Long intentId);
}
```

- [ ] **Step 6: 创建 ToolCallLogMapper**

```java
package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.ToolCallLogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogDO> {
    // 仅使用 BaseMapper 的 insert，查询通过管理后台走独立接口
}
```

- [ ] **Step 7: 编译确认**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/mapper/
git commit -m "feat(dit): add DIT Mapper interfaces"
```

---

## Task 4: 配置 Record（缓存对象）

这些 record 是 Redis 缓存中存放的对象，是从 DB Entity 映射来的只读视图，与 Entity 分离。

所有 record 放在包 `com.aria.conversation.infrastructure.dit.config`，路径：
`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/`

**Files:**
- Create: `infrastructure/dit/config/SlotConfig.java`
- Create: `infrastructure/dit/config/ToolConfig.java`
- Create: `infrastructure/dit/config/IntentToolBinding.java`
- Create: `infrastructure/dit/config/IntentConfig.java`
- Create: `infrastructure/dit/config/DomainConfig.java`

- [ ] **Step 1: 创建 SlotConfig**

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.List;

/**
 * 槽位配置（只读，从 cs_intent_slot 映射，存入 Redis 缓存）。
 *
 * @param slotName           参数名，如 "order_id"
 * @param slotType           string / number / date / enum
 * @param description        给 LLM 的提取说明
 * @param required           是否必填
 * @param resolveStrategy    解析策略顺序，如 ["EXTRACT","SESSION","DISCOVER","ASK_USER"]
 * @param sessionKey         SESSION 级：会话上下文 key
 * @param discoverToolCode   DISCOVER 级：发现工具 code
 * @param discoverFixedParams DISCOVER 工具的额外固定参数（JSON 字符串）
 * @param askUserPrompt      ASK_USER 级：询问用户的话术
 * @param enumValues         enum 类型可选值列表
 */
public record SlotConfig(
        String slotName,
        String slotType,
        String description,
        boolean required,
        List<String> resolveStrategy,
        String sessionKey,
        String discoverToolCode,
        String discoverFixedParams,
        String askUserPrompt,
        List<String> enumValues
) implements Serializable {}
```

- [ ] **Step 2: 创建 ToolConfig**

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;

/**
 * 工具配置（只读，从 cs_tool 映射，存入 Redis 缓存）。
 *
 * @param code             工具唯一标识
 * @param name             工具名称
 * @param description      给 LLM 的工具说明（Function Calling description）
 * @param toolType         HTTP / BUILTIN
 * @param httpMethod       GET / POST / PUT / DELETE
 * @param urlTemplate      URL 模板，支持 {slot_name} 占位符
 * @param headersTemplate  请求头模板（JSON 字符串）
 * @param bodyTemplate     请求体模板（JSON 字符串，POST 使用）
 * @param paramSchema      参数 JSON Schema（JSON 字符串）
 * @param responseJsonpath 从响应提取结果的 JSONPath，如 "$.data"
 * @param authType         NONE / API_KEY / BEARER / BASIC
 * @param authConfig       认证配置（JSON 字符串，已加密）
 * @param timeoutMs        超时毫秒
 * @param isDiscoverTool   是否可作为 DISCOVER 级发现工具
 */
public record ToolConfig(
        String code,
        String name,
        String description,
        String toolType,
        String httpMethod,
        String urlTemplate,
        String headersTemplate,
        String bodyTemplate,
        String paramSchema,
        String responseJsonpath,
        String authType,
        String authConfig,
        int timeoutMs,
        boolean isDiscoverTool
) implements Serializable {}
```

- [ ] **Step 3: 创建 IntentToolBinding**

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;

/**
 * 意图-工具绑定配置（只读，从 cs_intent_tool 映射）。
 *
 * @param tool           工具配置
 * @param executionMode  REQUIRED / OPTIONAL
 * @param executionOrder REQUIRED 工具的串行顺序
 * @param paramMappings  参数来源映射（JSON 字符串）
 */
public record IntentToolBinding(
        ToolConfig tool,
        String executionMode,
        int executionOrder,
        String paramMappings
) implements Serializable {

    public boolean isRequired() {
        return "REQUIRED".equals(executionMode);
    }

    public boolean isOptional() {
        return "OPTIONAL".equals(executionMode);
    }
}
```

- [ ] **Step 4: 创建 IntentConfig**

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.List;

/**
 * 意图配置（只读，从 cs_intent 映射，含关联的槽位和工具绑定）。
 *
 * @param code           意图标识，如 "query_order"
 * @param name           意图名称，如 "查询订单"
 * @param description    给 LLM 的意图说明
 * @param exampleQueries 少样本示例（JSON 数组字符串）
 * @param autoTransfer   是否自动转人工
 * @param skipRag        是否跳过 RAG
 * @param fallbackReply  工具失败兜底回复
 * @param slots          槽位列表（按 sort_order 排序）
 * @param toolBindings   工具绑定列表（按 execution_order 排序）
 */
public record IntentConfig(
        String code,
        String name,
        String description,
        String exampleQueries,
        boolean autoTransfer,
        boolean skipRag,
        String fallbackReply,
        List<SlotConfig> slots,
        List<IntentToolBinding> toolBindings
) implements Serializable {

    /** 获取所有 REQUIRED 工具绑定，按 executionOrder 排序 */
    public List<IntentToolBinding> requiredTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isRequired)
                .sorted(java.util.Comparator.comparingInt(IntentToolBinding::executionOrder))
                .toList();
    }

    /** 获取所有 OPTIONAL 工具绑定 */
    public List<IntentToolBinding> optionalTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isOptional)
                .toList();
    }
}
```

- [ ] **Step 5: 创建 DomainConfig**

```java
package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * 领域配置（只读，从 cs_domain 映射，含完整意图列表，存入 Redis 缓存）。
 *
 * @param code              领域标识，如 "ecommerce"
 * @param name              领域名称
 * @param systemPromptAddon 追加到 system prompt 的专属说明
 * @param knowledgeBaseId   专属知识库 ID，null 使用全局
 * @param intents           意图列表（按 sort_order 排序）
 */
public record DomainConfig(
        String code,
        String name,
        String systemPromptAddon,
        Long knowledgeBaseId,
        List<IntentConfig> intents
) implements Serializable {

    /** 按 intentCode 查找意图，不存在返回 empty */
    public Optional<IntentConfig> findIntent(String intentCode) {
        return intents.stream()
                .filter(i -> i.code().equals(intentCode))
                .findFirst();
    }
}
```

- [ ] **Step 6: 编译确认**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/config/
git commit -m "feat(dit): add DIT config records (DomainConfig, IntentConfig, SlotConfig, ToolConfig)"
```

---
## Task 5: DomainRepository（带 Redis 缓存的配置仓储）

这是 P1 的核心类，封装"Redis 10分钟缓存 + DB 兜底"模式，上层调用只需 `findByCode()`，不感知缓存细节。

**Files:**
- Create: `infrastructure/dit/repository/DomainRepository.java`

- [ ] **Step 1: 创建 DomainRepository**

路径：`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java`

```java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.aria.conversation.infrastructure.dit.mapper.DomainMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentSlotMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentToolMapper;
import com.aria.conversation.infrastructure.dit.mapper.ToolMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 领域配置仓储。
 *
 * <p>缓存策略：
 * <pre>
 *   findByCode(code)
 *     ├─ Redis HIT  → 反序列化直接返回（TTL 10 分钟）
 *     └─ Redis MISS → DB 查询 → 组装 DomainConfig → 写 Redis → 返回
 * </pre>
 *
 * <p>缓存失效：管理后台修改领域/意图/工具配置后调用 {@link #evict(String)} 主动失效。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DomainRepository {

    private static final String CACHE_KEY_PREFIX = "dit:domain:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisCacheHelper cache;
    private final ObjectMapper objectMapper;
    private final DomainMapper domainMapper;
    private final IntentMapper intentMapper;
    private final IntentSlotMapper slotMapper;
    private final IntentToolMapper intentToolMapper;
    private final ToolMapper toolMapper;

    /**
     * 按 domainCode 查找领域配置。
     *
     * @param domainCode 前端传入的领域标识，如 "ecommerce"
     * @return 完整领域配置，含意图、槽位、工具绑定；不存在返回 empty
     */
    public Optional<DomainConfig> findByCode(String domainCode) {
        String cacheKey = CACHE_KEY_PREFIX + domainCode;

        // 1. 尝试从 Redis 取
        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                return Optional.of(objectMapper.readValue(cached, DomainConfig.class));
            } catch (Exception e) {
                log.warn("[DIT] 领域配置缓存反序列化失败，回退 DB code={}", domainCode, e);
                cache.delete(cacheKey);
            }
        }

        // 2. Redis miss，查 DB
        Optional<DomainDO> domainOpt = domainMapper.findByCode(domainCode);
        if (domainOpt.isEmpty()) {
            log.debug("[DIT] 领域配置不存在 code={}", domainCode);
            return Optional.empty();
        }

        DomainConfig config = buildDomainConfig(domainOpt.get());

        // 3. 写入 Redis 缓存
        try {
            cache.set(cacheKey, objectMapper.writeValueAsString(config), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[DIT] 领域配置写缓存失败 code={}", domainCode, e);
        }

        log.debug("[DIT] 领域配置从 DB 加载 code={} intents={}", domainCode, config.intents().size());
        return Optional.of(config);
    }

    /**
     * 主动失效领域配置缓存（管理后台修改配置后调用）。
     *
     * @param domainCode 领域标识
     */
    public void evict(String domainCode) {
        cache.delete(CACHE_KEY_PREFIX + domainCode);
        log.info("[DIT] 领域配置缓存已失效 code={}", domainCode);
    }

    // ---- 私有：DB 数据组装 ----

    private DomainConfig buildDomainConfig(DomainDO domain) {
        List<IntentDO> intentDOs = intentMapper.findByDomainId(domain.getId());
        List<IntentConfig> intents = new ArrayList<>(intentDOs.size());

        for (IntentDO intentDO : intentDOs) {
            List<SlotConfig> slots = buildSlots(intentDO.getId());
            List<IntentToolBinding> bindings = buildToolBindings(intentDO.getId());
            intents.add(new IntentConfig(
                    intentDO.getCode(),
                    intentDO.getName(),
                    intentDO.getDescription(),
                    intentDO.getExampleQueries(),
                    Boolean.TRUE.equals(intentDO.getAutoTransfer()),
                    Boolean.TRUE.equals(intentDO.getSkipRag()),
                    intentDO.getFallbackReply(),
                    slots,
                    bindings
            ));
        }

        return new DomainConfig(
                domain.getCode(),
                domain.getName(),
                domain.getSystemPromptAddon(),
                domain.getKnowledgeBaseId(),
                intents
        );
    }

    private List<SlotConfig> buildSlots(Long intentId) {
        List<IntentSlotDO> slotDOs = slotMapper.findByIntentId(intentId);
        List<SlotConfig> result = new ArrayList<>(slotDOs.size());

        for (IntentSlotDO s : slotDOs) {
            List<String> strategy = parseJsonArray(s.getResolveStrategy());
            List<String> enumVals = parseJsonArray(s.getEnumValues());
            result.add(new SlotConfig(
                    s.getSlotName(),
                    s.getSlotType() != null ? s.getSlotType() : "string",
                    s.getDescription(),
                    Boolean.TRUE.equals(s.getRequired()),
                    strategy,
                    s.getSessionKey(),
                    s.getDiscoverToolCode(),
                    s.getDiscoverFixedParams(),
                    s.getAskUserPrompt(),
                    enumVals
            ));
        }
        return result;
    }

    private List<IntentToolBinding> buildToolBindings(Long intentId) {
        List<IntentToolDO> bindingDOs = intentToolMapper.findByIntentId(intentId);
        List<IntentToolBinding> result = new ArrayList<>(bindingDOs.size());

        for (IntentToolDO b : bindingDOs) {
            Optional<ToolDO> toolOpt = toolMapper.findByCode(
                    getToolCodeById(b.getToolId()));
            if (toolOpt.isEmpty()) continue;

            ToolDO t = toolOpt.get();
            ToolConfig toolConfig = new ToolConfig(
                    t.getCode(), t.getName(), t.getDescription(),
                    t.getToolType(), t.getHttpMethod(), t.getUrlTemplate(),
                    t.getHeadersTemplate(), t.getBodyTemplate(), t.getParamSchema(),
                    t.getResponseJsonpath(), t.getAuthType(), t.getAuthConfig(),
                    t.getTimeoutMs() != null ? t.getTimeoutMs() : 5000,
                    Boolean.TRUE.equals(t.getIsDiscoverTool())
            );
            result.add(new IntentToolBinding(
                    toolConfig,
                    b.getExecutionMode() != null ? b.getExecutionMode() : "OPTIONAL",
                    b.getExecutionOrder() != null ? b.getExecutionOrder() : 0,
                    b.getParamMappings()
            ));
        }
        return result;
    }

    /** 通过 toolId 查工具 code（再查工具详情） */
    private String getToolCodeById(Long toolId) {
        ToolDO toolDO = toolMapper.selectById(toolId);
        return toolDO != null ? toolDO.getCode() : "";
    }

    /** 容错解析 JSON 数组字符串为 List<String>，失败返回空列表 */
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DIT] JSON 数组解析失败: {}", json);
            return List.of();
        }
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/DomainRepository.java
git commit -m "feat(dit): add DomainRepository with Redis cache + DB fallback"
```

---

## Task 6: PendingSlotRepository（槽位挂起状态 Redis 仓储）

**Files:**
- Create: `infrastructure/dit/repository/PendingSlotRepository.java`
- Create: `infrastructure/dit/repository/PendingSlotState.java`

- [ ] **Step 1: 创建 PendingSlotState record**

路径：`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/PendingSlotState.java`

```java
package com.aria.conversation.infrastructure.dit.repository;

import java.util.List;
import java.util.Map;

/**
 * 槽位解析挂起状态，存储于 Redis。
 *
 * <p>当槽位处于 DISCOVERED（展示候选项等待选择）或 MISSING（等待用户输入）时，
 * pipeline 进入等待，下一轮对话从此恢复上下文继续执行。
 *
 * @param sessionId      会话 ID
 * @param domainCode     领域标识
 * @param intentCode     当前意图标识
 * @param pendingSlot    当前等待解析的槽位名
 * @param pendingType    DISCOVERED / MISSING
 * @param candidates     DISCOVERED 时的候选项列表，每项包含 id 和 label
 * @param resolvedSlots  已解析完成的槽位值 Map
 * @param retryCount     已重试次数，达到 2 次触发兜底转人工
 */
public record PendingSlotState(
        String sessionId,
        String domainCode,
        String intentCode,
        String pendingSlot,
        String pendingType,
        List<Map<String, String>> candidates,
        Map<String, Object> resolvedSlots,
        int retryCount
) {
    /** 最大重试次数，超过后触发兜底转人工 */
    public static final int MAX_RETRY = 2;

    public boolean isDiscovered() { return "DISCOVERED".equals(pendingType); }
    public boolean isMissing()    { return "MISSING".equals(pendingType); }
    public boolean shouldGiveUp() { return retryCount >= MAX_RETRY; }

    /** 返回 retryCount +1 的新实例 */
    public PendingSlotState withIncrementedRetry() {
        return new PendingSlotState(sessionId, domainCode, intentCode,
                pendingSlot, pendingType, candidates, resolvedSlots, retryCount + 1);
    }
}
```

- [ ] **Step 2: 创建 PendingSlotRepository**

路径：`ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/PendingSlotRepository.java`

```java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 槽位解析挂起状态 Redis 仓储。
 *
 * <p>key: {@code dit:pending:{sessionId}}，TTL 30 分钟。
 * 30 分钟内用户无响应，状态自动过期，下次对话从头开始 pipeline。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PendingSlotRepository {

    private static final String KEY_PREFIX = "dit:pending:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisCacheHelper cache;
    private final ObjectMapper objectMapper;

    /**
     * 保存挂起状态。
     *
     * @param state 挂起状态
     */
    public void save(PendingSlotState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            cache.set(KEY_PREFIX + state.sessionId(), json, TTL);
            log.debug("[DIT] 保存挂起状态 sessionId={} slot={} type={} retry={}",
                    state.sessionId(), state.pendingSlot(), state.pendingType(), state.retryCount());
        } catch (Exception e) {
            log.error("[DIT] 保存挂起状态失败 sessionId={}", state.sessionId(), e);
        }
    }

    /**
     * 查询挂起状态。
     *
     * @param sessionId 会话 ID
     * @return 挂起状态，不存在或已过期返回 empty
     */
    public Optional<PendingSlotState> find(String sessionId) {
        String json = cache.get(KEY_PREFIX + sessionId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, PendingSlotState.class));
        } catch (Exception e) {
            log.warn("[DIT] 挂起状态反序列化失败 sessionId={}", sessionId, e);
            delete(sessionId);
            return Optional.empty();
        }
    }

    /**
     * 删除挂起状态（槽位解析完成或触发兜底转人工时调用）。
     *
     * @param sessionId 会话 ID
     */
    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
        log.debug("[DIT] 删除挂起状态 sessionId={}", sessionId);
    }

    /**
     * 判断会话是否有挂起中的槽位解析。
     *
     * @param sessionId 会话 ID
     */
    public boolean hasPending(String sessionId) {
        return find(sessionId).isPresent();
    }
}
```

- [ ] **Step 3: 编译确认**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn compile -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/dit/repository/
git commit -m "feat(dit): add PendingSlotRepository and PendingSlotState for slot resolution state"
```

---
## Task 7: 单元测试

**Files:**
- Create: `test/.../dit/repository/DomainRepositoryTest.java`
- Create: `test/.../dit/repository/PendingSlotRepositoryTest.java`
- Create: `test/.../dit/config/DomainConfigTest.java`

基础包测试路径：
`ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/dit/`

- [ ] **Step 1: 创建 DomainConfigTest（纯 record 逻辑，无依赖）**

```java
package com.aria.conversation.infrastructure.dit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DomainConfig record 辅助方法")
class DomainConfigTest {

    private static IntentConfig makeIntent(String code, boolean autoTransfer,
                                           boolean skipRag) {
        return new IntentConfig(code, code + "_name", "desc", "[]",
                autoTransfer, skipRag, null, List.of(), List.of());
    }

    @Test
    @DisplayName("findIntent: 存在时返回对应意图")
    void findIntent_found() {
        IntentConfig intent = makeIntent("query_order", false, true);
        DomainConfig domain = new DomainConfig("ecommerce", "电商", null, null,
                List.of(intent));

        Optional<IntentConfig> result = domain.findIntent("query_order");

        assertTrue(result.isPresent());
        assertEquals("query_order", result.get().code());
    }

    @Test
    @DisplayName("findIntent: 不存在时返回 empty")
    void findIntent_notFound() {
        DomainConfig domain = new DomainConfig("ecommerce", "电商", null, null, List.of());
        assertTrue(domain.findIntent("no_such_intent").isEmpty());
    }

    @Test
    @DisplayName("IntentConfig.requiredTools: 只返回 REQUIRED 绑定，按 order 排序")
    void requiredTools_filtered_and_sorted() {
        ToolConfig tool = new ToolConfig("t1","n","d","HTTP","GET",
                "http://x","{}",null,"{}",null,"NONE","{}",5000,false);
        IntentToolBinding req1 = new IntentToolBinding(tool, "REQUIRED", 2, "{}");
        IntentToolBinding req2 = new IntentToolBinding(tool, "REQUIRED", 1, "{}");
        IntentToolBinding opt  = new IntentToolBinding(tool, "OPTIONAL", 0, "{}");

        IntentConfig intent = new IntentConfig("q","n","d","[]",false,false,null,
                List.of(), List.of(req1, req2, opt));

        List<IntentToolBinding> required = intent.requiredTools();
        assertEquals(2, required.size());
        assertEquals(1, required.get(0).executionOrder()); // order=1 在前
        assertEquals(2, required.get(1).executionOrder());
    }

    @Test
    @DisplayName("IntentToolBinding.isRequired / isOptional 正确")
    void executionMode_helpers() {
        ToolConfig tool = new ToolConfig("t","n","d","HTTP","GET",
                "http://x","{}",null,"{}",null,"NONE","{}",5000,false);
        assertTrue(new IntentToolBinding(tool, "REQUIRED", 0, "{}").isRequired());
        assertFalse(new IntentToolBinding(tool, "REQUIRED", 0, "{}").isOptional());
        assertTrue(new IntentToolBinding(tool, "OPTIONAL", 0, "{}").isOptional());
    }

    @Test
    @DisplayName("PendingSlotState.shouldGiveUp: retryCount >= MAX_RETRY 时为 true")
    void pendingSlotState_giveUp() {
        var state0 = new com.aria.conversation.infrastructure.dit.repository
                .PendingSlotState("s","d","i","order_id","MISSING",null,java.util.Map.of(),0);
        var state1 = state0.withIncrementedRetry();
        var state2 = state1.withIncrementedRetry();

        assertFalse(state0.shouldGiveUp());
        assertFalse(state1.shouldGiveUp());
        assertTrue(state2.shouldGiveUp());  // retry=2 >= MAX_RETRY=2
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service \
    -Dtest=DomainConfigTest -q
```

预期：`BUILD SUCCESS`，0 failures

- [ ] **Step 3: 创建 PendingSlotRepositoryTest**

```java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingSlotRepository")
class PendingSlotRepositoryTest {

    @Mock private RedisCacheHelper cache;
    private PendingSlotRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PendingSlotRepository(cache, new ObjectMapper());
    }

    @Test
    @DisplayName("save: 序列化后调用 cache.set")
    void save_callsCacheSet() {
        PendingSlotState state = new PendingSlotState(
                "s1", "ecommerce", "query_order", "order_id",
                "MISSING", null, Map.of(), 0);

        repo.save(state);

        verify(cache).set(eq("dit:pending:s1"), anyString(), any());
    }

    @Test
    @DisplayName("find: cache miss 返回 empty")
    void find_cacheMiss_returnsEmpty() {
        when(cache.get("dit:pending:s1")).thenReturn(null);
        assertTrue(repo.find("s1").isEmpty());
    }

    @Test
    @DisplayName("find: cache hit 反序列化返回 state")
    void find_cacheHit_returnsState() throws Exception {
        PendingSlotState state = new PendingSlotState(
                "s1", "ecommerce", "query_order", "order_id",
                "MISSING", null, Map.of(), 1);
        String json = new ObjectMapper().writeValueAsString(state);
        when(cache.get("dit:pending:s1")).thenReturn(json);

        Optional<PendingSlotState> result = repo.find("s1");

        assertTrue(result.isPresent());
        assertEquals("order_id", result.get().pendingSlot());
        assertEquals(1, result.get().retryCount());
    }

    @Test
    @DisplayName("delete: 调用 cache.delete")
    void delete_callsCacheDelete() {
        repo.delete("s1");
        verify(cache).delete("dit:pending:s1");
    }

    @Test
    @DisplayName("hasPending: cache miss 返回 false")
    void hasPending_false_whenNoCache() {
        when(cache.get(anyString())).thenReturn(null);
        assertFalse(repo.hasPending("s1"));
    }
}
```

- [ ] **Step 4: 创建 DomainRepositoryTest**

```java
package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainRepository")
class DomainRepositoryTest {

    @Mock private RedisCacheHelper cache;
    @Mock private DomainMapper domainMapper;
    @Mock private IntentMapper intentMapper;
    @Mock private IntentSlotMapper slotMapper;
    @Mock private IntentToolMapper intentToolMapper;
    @Mock private ToolMapper toolMapper;

    private DomainRepository repo;

    @BeforeEach
    void setUp() {
        repo = new DomainRepository(cache, new ObjectMapper(),
                domainMapper, intentMapper, slotMapper, intentToolMapper, toolMapper);
    }

    @Test
    @DisplayName("findByCode: Redis HIT，直接返回缓存，不查 DB")
    void findByCode_cacheHit_noDbCall() throws Exception {
        var config = new com.aria.conversation.infrastructure.dit.config.DomainConfig(
                "ecommerce", "电商", null, null, List.of());
        String json = new ObjectMapper().writeValueAsString(config);
        when(cache.get("dit:domain:ecommerce")).thenReturn(json);

        Optional<com.aria.conversation.infrastructure.dit.config.DomainConfig> result =
                repo.findByCode("ecommerce");

        assertTrue(result.isPresent());
        assertEquals("ecommerce", result.get().code());
        verify(domainMapper, never()).findByCode(anyString()); // 不查 DB
    }

    @Test
    @DisplayName("findByCode: Redis MISS，DB 也无数据，返回 empty")
    void findByCode_cacheMiss_dbMiss_returnsEmpty() {
        when(cache.get(anyString())).thenReturn(null);
        when(domainMapper.findByCode("unknown")).thenReturn(Optional.empty());

        Optional<com.aria.conversation.infrastructure.dit.config.DomainConfig> result =
                repo.findByCode("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findByCode: Redis MISS，从 DB 加载并写缓存")
    void findByCode_cacheMiss_dbHit_writesCache() {
        when(cache.get(anyString())).thenReturn(null);

        DomainDO domain = new DomainDO();
        domain.setId(1L);
        domain.setCode("ecommerce");
        domain.setName("电商");
        domain.setEnabled(true);

        when(domainMapper.findByCode("ecommerce")).thenReturn(Optional.of(domain));
        when(intentMapper.findByDomainId(1L)).thenReturn(List.of());

        Optional<com.aria.conversation.infrastructure.dit.config.DomainConfig> result =
                repo.findByCode("ecommerce");

        assertTrue(result.isPresent());
        assertEquals("电商", result.get().name());
        verify(cache).set(eq("dit:domain:ecommerce"), anyString(), any()); // 写缓存
    }

    @Test
    @DisplayName("evict: 调用 cache.delete")
    void evict_deletesCache() {
        repo.evict("ecommerce");
        verify(cache).delete("dit:domain:ecommerce");
    }
}
```

- [ ] **Step 5: 运行所有测试**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service \
    -Dtest="DomainConfigTest,PendingSlotRepositoryTest,DomainRepositoryTest" -q
```

预期：`BUILD SUCCESS`，0 failures

- [ ] **Step 6: 提交**

```bash
git add ai-conversation/conversation-service/src/test/java/com/aria/conversation/infrastructure/dit/
git commit -m "test(dit): add unit tests for DomainConfig, PendingSlotRepository, DomainRepository"
```

---

## 验收标准

- [ ] `migration-002-dit-tables.sql` 在本地 PostgreSQL 执行无报错（7 张表全部创建）
- [ ] `mvn test -pl ai-conversation/conversation-service -Dtest="DomainConfigTest,PendingSlotRepositoryTest,DomainRepositoryTest"` 全部通过
- [ ] `mvn compile -pl ai-conversation/conversation-service` 无警告
- [ ] 原有测试不受影响：`SessionStatusTest`、`ChatWebSocketHandlerSessionIdTest`

---

## 下一步：P2 计划

P1 完成后，P2 将实现：
- `IntentClassifier`（领域感知版，携带领域意图列表做分类）
- `SlotResolver`（槽位解析四级策略：EXTRACT / SESSION / DISCOVER / ASK_USER）
- `DIT Pipeline` 的 Steps 1-3（领域加载 → 意图识别 → 槽位解析）
- `ChatController` 接入 domainCode 参数

P3 将实现：
- `HttpToolRunner`（通用 HTTP 工具执行器）
- `ToolExecutor`（REQUIRED 串行 + OPTIONAL Function Calling）
- DIT Pipeline 完整 Steps 4-6
