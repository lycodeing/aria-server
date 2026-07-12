# 系统配置模块技术设计文档

## 1. 概述与背景

### 1.1 背景

当前项目中，各业务模块（会话服务、知识库服务、RAG 检索、仪表盘等）的关键配置参数均以硬编码形式分散在各处。运营人员无法在不重新部署的情况下调整这些参数，维护成本高。

本模块旨在建立统一的系统配置管理机制，将可调节的运营参数持久化到数据库，通过管理后台提供 CRUD 接口，并让各业务模块在运行时动态读取。

### 1.2 范围

**纳入本模块管理的配置（持久化到 DB）：**

| config_key | 配置类型 | 业务用途 |
|---|---|---|
| `agent.maxConcurrent` | CUSTOMER_SERVICE | 座席接受会话时的并发上限校验 |
| `agent.welcomeMessage` | CUSTOMER_SERVICE | 后端在会话创建时插入首条消息 |
| `knowledge.searchTopK` | CUSTOMER_SERVICE | RAG 检索 LIMIT 参数 |
| `knowledge.uploadMaxFileSizeMb` | CUSTOMER_SERVICE | 文件上传接口的大小限制校验 |
| `prompt.agent.suggestion` | CUSTOMER_SERVICE | 座席建议回复的 prompt 模板 |
| `prompt.kb.qa` | CUSTOMER_SERVICE | 知识库问答的 prompt 模板 |
| `prompt.visitor.autoReply` | CUSTOMER_SERVICE | 访客自动回复的 prompt 模板 |
| `prompt.session.summary` | CUSTOMER_SERVICE | 会话摘要生成的 prompt 模板 |
| `prompt.intent.classify` | CUSTOMER_SERVICE | 意图识别分类的 prompt 模板 |
| `dashboard.recentLimit` | SYSTEM | 仪表盘最近记录的 SQL LIMIT |

**不纳入 DB 管理的配置（保留硬编码或 application.yml）：**
- 纯前端 UI 参数：`queue.pageSize`、`suggestion.debounceMs` 等
- WS/SSE 连接参数：心跳间隔、重连策略等

### 1.3 设计目标

1. **统一入口**：所有可调配置通过 `SystemConfigService` 统一读取
2. **类型隔离**：CUSTOMER_SERVICE 与 SYSTEM 类型配置各自守卫，防止越权修改
3. **硬编码兜底**：业务代码读取配置时，若 DB 中无对应 key，回退到硬编码默认值
4. **权限细化**：管理员仅能管理对应类型的配置，超级管理员可管理全部
5. **缓存友好**：`mapByType()` 批量加载，减少频繁单 key 查询

### 1.4 模块位置

所有代码均位于 `ai-auth/auth-service` 模块，配置数据存储于 `cs_auth` schema。

## 2. 数据模型

### 2.1 表结构 DDL

在 `docs/sql/ai_customerservice-schema.sql` 的 `cs_auth` schema 部分追加以下内容：

```sql
-- ============================================================
-- Table: cs_auth.system_config
-- 系统配置表，存储可动态调节的运营参数
-- ============================================================
CREATE TABLE cs_auth.system_config (
    id              bigserial                   PRIMARY KEY,
    config_key      character varying(100)      NOT NULL,
    config_value    text                        NOT NULL,
    config_type     character varying(50)       NOT NULL DEFAULT 'SYSTEM',
    description     character varying(255)      NOT NULL DEFAULT '',
    is_enabled      boolean                     NOT NULL DEFAULT true,
    created_at      timestamp with time zone    NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone    NOT NULL DEFAULT now(),
    deleted_at      timestamp with time zone
);

-- 唯一约束：同一 config_key 只允许一条未删除记录
CREATE UNIQUE INDEX uq_system_config_key
    ON cs_auth.system_config (config_key)
    WHERE deleted_at IS NULL;

-- 类型索引：按 config_type 批量加载配置
CREATE INDEX idx_system_config_type
    ON cs_auth.system_config (config_type)
    WHERE deleted_at IS NULL;

-- 触发器：自动更新 updated_at
CREATE TRIGGER set_updated_at_system_config
    BEFORE UPDATE ON cs_auth.system_config
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

COMMENT ON TABLE  cs_auth.system_config               IS '系统配置表';
COMMENT ON COLUMN cs_auth.system_config.id            IS '主键';
COMMENT ON COLUMN cs_auth.system_config.config_key    IS '配置键，全局唯一（未删除）';
COMMENT ON COLUMN cs_auth.system_config.config_value  IS '配置值，统一以字符串存储';
COMMENT ON COLUMN cs_auth.system_config.config_type   IS '配置类型：SYSTEM | CUSTOMER_SERVICE';
COMMENT ON COLUMN cs_auth.system_config.description   IS '配置描述，供管理员阅读';
COMMENT ON COLUMN cs_auth.system_config.is_enabled    IS '是否启用';
COMMENT ON COLUMN cs_auth.system_config.created_at    IS '创建时间';
COMMENT ON COLUMN cs_auth.system_config.updated_at    IS '最后更新时间（触发器自动维护）';
COMMENT ON COLUMN cs_auth.system_config.deleted_at    IS '软删除时间，NULL 表示未删除';
```

### 2.2 字段说明

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | bigserial | PK | 自增主键 |
| `config_key` | varchar(100) | NOT NULL, UNIQUE（软删除安全） | 配置键，格式建议 `domain.subDomain.param`（小驼峰点分隔） |
| `config_value` | text | NOT NULL | 统一以字符串存储，业务层按需转型 |
| `config_type` | varchar(50) | NOT NULL | `SYSTEM`（系统级）或 `CUSTOMER_SERVICE`（客服业务级） |
| `description` | varchar(255) | NOT NULL | 管理员可读描述，说明该配置的用途和取值范围 |
| `is_enabled` | boolean | NOT NULL, DEFAULT true | 禁用时业务层应回退到硬编码默认值 |
| `created_at` | timestamptz | NOT NULL | 记录创建时间 |
| `updated_at` | timestamptz | NOT NULL | 触发器自动维护 |
| `deleted_at` | timestamptz | NULL | 软删除，所有查询需加 `WHERE deleted_at IS NULL` |

### 2.3 配置类型枚举

```
SYSTEM            -- 系统级配置，仅超级管理员可管理（如 dashboard.recentLimit）
CUSTOMER_SERVICE  -- 客服业务配置，客服管理员可管理（如 agent.*, knowledge.*, prompt.*）
```

### 2.4 Flyway 迁移文件

本项目使用 Flyway 管理 schema 变更。需在 `auth-service` 资源目录创建迁移脚本：

```
ai-auth/auth-service/src/main/resources/db/migration/
  V{next_version}__create_system_config.sql
```

脚本内容即上方 DDL（去掉注释行可减小文件体积）。版本号需在当前最大版本号 +1，执行前通过 `SELECT MAX(version) FROM cs_auth.flyway_schema_history` 确认。

## 3. 种子数据

### 3.1 初始配置 INSERT

在 `docs/sql/ai_customerservice-schema.sql` 的 DML 区段（或独立的 `data.sql`）追加：

```sql
-- ============================================================
-- 系统配置种子数据
-- 说明：id 从当前最大值续写，执行前请确认
-- ============================================================
INSERT INTO cs_auth.system_config
    (config_key, config_value, config_type, description, is_enabled)
VALUES
-- ── 座席配置 ─────────────────────────────────────────────
(
    'agent.maxConcurrent',
    '5',
    'CUSTOMER_SERVICE',
    '单个座席同时接待的最大会话数。超出时系统拒绝新分配。取值范围：1–50',
    true
),
(
    'agent.welcomeMessage',
    '您好，感谢联系我们，请问有什么可以帮助您？',
    'CUSTOMER_SERVICE',
    '会话建立时后端自动插入的欢迎消息内容',
    true
),
-- ── 知识库配置 ───────────────────────────────────────────
(
    'knowledge.searchTopK',
    '5',
    'CUSTOMER_SERVICE',
    'RAG 检索时返回的最大相关片段数（TopK）。取值范围：1–20',
    true
),
(
    'knowledge.uploadMaxFileSizeMb',
    '20',
    'CUSTOMER_SERVICE',
    '知识库文件上传的单文件大小上限（单位：MB）。取值范围：1–200',
    true
),
-- ── Prompt 模板 ──────────────────────────────────────────
(
    'prompt.agent.suggestion',
    '你是一名专业客服，请根据以下对话历史和知识库内容，为座席生成 3 条简洁的回复建议。\n\n对话历史：\n{history}\n\n知识库参考：\n{context}',
    'CUSTOMER_SERVICE',
    '座席建议回复的 prompt 模板。支持占位符：{history}、{context}',
    true
),
(
    'prompt.kb.qa',
    '你是一名专业客服助手，请根据以下知识库内容回答用户问题。如果知识库中没有相关信息，请如实告知。\n\n知识库内容：\n{context}\n\n用户问题：{question}',
    'CUSTOMER_SERVICE',
    '知识库问答的 prompt 模板。支持占位符：{context}、{question}',
    true
),
(
    'prompt.visitor.autoReply',
    '你是一名智能客服，请根据以下对话历史和知识库内容，自动回复访客的最新消息。回复要简洁、友好、专业。\n\n知识库内容：\n{context}\n\n对话历史：\n{history}',
    'CUSTOMER_SERVICE',
    '访客自动回复的 prompt 模板。支持占位符：{context}、{history}',
    true
),
(
    'prompt.session.summary',
    '请根据以下客服对话记录，生成一份简洁的会话摘要，包含：用户主要问题、解决方案、是否已解决。\n\n对话记录：\n{history}',
    'CUSTOMER_SERVICE',
    '会话结束后生成摘要的 prompt 模板。支持占位符：{history}',
    true
),
(
    'prompt.intent.classify',
    '请分析用户消息的意图，从以下类别中选择最匹配的一个：{intents}。\n\n用户消息：{message}\n\n只需返回类别名称，不需要解释。',
    'CUSTOMER_SERVICE',
    '意图识别分类的 prompt 模板。支持占位符：{intents}、{message}',
    true
),
-- ── 仪表盘配置 ───────────────────────────────────────────
(
    'dashboard.recentLimit',
    '10',
    'SYSTEM',
    '仪表盘"最近记录"查询的 SQL LIMIT 值。取值范围：5–100',
    true
);
```

### 3.2 数据约定

- `config_value` 统一存字符串，业务层按需转型（`Integer.parseInt`、直接使用等）
- Prompt 模板中的占位符格式为 `{paramName}`，由业务代码在调用前做字符串替换
- 所有种子数据 `is_enabled = true`，若需禁用某条配置，UPDATE 而非 DELETE

### 3.3 运营建议

| config_key | 建议调整时机 |
|---|---|
| `agent.maxConcurrent` | 根据座席负载和服务质量 KPI 动态调整 |
| `agent.welcomeMessage` | 节假日或营销活动时修改欢迎语 |
| `knowledge.searchTopK` | 答案质量不足时适当增大，性能敏感时减小 |
| `knowledge.uploadMaxFileSizeMb` | 根据服务器存储和带宽调整 |
| `prompt.*` | 模型升级或业务场景变化时迭代优化 |
| `dashboard.recentLimit` | 根据仪表盘加载性能调整 |

## 4. Java 层设计

所有文件均位于 `ai-auth/auth-service` 模块，基础包 `com.aria.auth`。

### 4.1 目录结构

```
com.aria.auth
├── infrastructure
│   └── persistence
│       └── systemconfig
│           ├── SystemConfigDO.java
│           ├── SystemConfigMapper.java
│           └── mapper
│               └── SystemConfigMapper.xml
├── application
│   └── service
│       ├── SystemConfigService.java
│       └── impl
│           └── SystemConfigServiceImpl.java
└── interfaces
    └── rest
        ├── AdminSystemConfigController.java
        └── vo
            ├── SystemConfigVO.java
            └── dto
                └── SystemConfigDTO.java
```

---

### 4.2 SystemConfigDO.java

```java
package com.aria.auth.infrastructure.persistence.systemconfig;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统配置持久化对象
 */
@Getter
@Setter
@TableName("cs_auth.system_config")
public class SystemConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String configValue;

    /** 配置类型：SYSTEM | CUSTOMER_SERVICE */
    private String configType;

    private String description;

    @TableField("is_enabled")
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
```

---

### 4.3 SystemConfigMapper.java

```java
package com.aria.auth.infrastructure.persistence.systemconfig;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper
 * 基础 CRUD 由 BaseMapper 提供；如有复杂查询可在 XML 中扩展
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfigDO> {
}
```

> 无需 XML 文件，BaseMapper 已覆盖全部 CRUD 需求。

---

### 4.4 SystemConfigService.java（接口）

```java
package com.aria.auth.application.service;

import com.aria.auth.interfaces.rest.vo.SystemConfigVO;
import com.aria.auth.interfaces.rest.vo.dto.SystemConfigDTO;
import com.aria.common.core.page.PageQuery;
import com.aria.common.core.page.PageResult;

import java.util.Map;

/**
 * 系统配置服务接口
 */
public interface SystemConfigService {

    /**
     * 分页查询配置列表
     *
     * @param configType 配置类型过滤（null 表示不过滤）
     * @param keyword    config_key 或 description 关键字（null 表示不过滤）
     * @param pageQuery  分页参数
     */
    PageResult<SystemConfigVO> page(String configType, String keyword, PageQuery pageQuery);

    /**
     * 查询单条配置
     */
    SystemConfigVO getById(Long id);

    /**
     * 按类型批量加载，返回 key→value 映射
     * 仅返回 is_enabled=true 且未删除的记录
     * 业务代码通过此方法读取配置
     */
    Map<String, String> mapByType(String configType);

    /**
     * 读取单个配置值，找不到或已禁用时返回 defaultValue
     */
    String getValue(String configKey, String defaultValue);

    /**
     * 新增配置
     */
    SystemConfigVO create(SystemConfigDTO dto);

    /**
     * 更新配置（configKey 不可修改）
     */
    SystemConfigVO update(Long id, SystemConfigDTO dto);

    /**
     * 软删除配置
     */
    void delete(Long id);

    /**
     * 切换启用 / 禁用状态
     */
    void toggleEnabled(Long id, boolean enabled);
}
```

---

### 4.5 SystemConfigServiceImpl.java

```java
package com.aria.auth.application.service.impl;

import com.aria.auth.application.service.SystemConfigService;
import com.aria.auth.infrastructure.persistence.systemconfig.SystemConfigDO;
import com.aria.auth.infrastructure.persistence.systemconfig.SystemConfigMapper;
import com.aria.auth.interfaces.rest.vo.SystemConfigVO;
import com.aria.auth.interfaces.rest.vo.dto.SystemConfigDTO;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.page.PageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.page.PageUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    // ── 查询 ─────────────────────────────────────────────

    @Override
    public PageResult<SystemConfigVO> page(String configType, String keyword, PageQuery pageQuery) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .isNull(SystemConfigDO::getDeletedAt);

        if (StringUtils.hasText(configType)) {
            wrapper.eq(SystemConfigDO::getConfigType, configType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(SystemConfigDO::getConfigKey, keyword)
                    .or()
                    .like(SystemConfigDO::getDescription, keyword));
        }
        wrapper.orderByDesc(SystemConfigDO::getCreatedAt);

        Page<SystemConfigDO> mpPage = systemConfigMapper.selectPage(
                PageUtil.toMpPage(pageQuery), wrapper);

        return PageUtil.toPageResult(mpPage, pageQuery)
                .map(this::toVO);
    }

    @Override
    public SystemConfigVO getById(Long id) {
        SystemConfigDO config = requireExists(id);
        return toVO(config);
    }

    @Override
    public Map<String, String> mapByType(String configType) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .eq(SystemConfigDO::getConfigType, configType)
                .eq(SystemConfigDO::getEnabled, true)
                .isNull(SystemConfigDO::getDeletedAt);

        return systemConfigMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(
                        SystemConfigDO::getConfigKey,
                        SystemConfigDO::getConfigValue,
                        (a, b) -> a  // key 唯一约束，理论上不会冲突
                ));
    }

    @Override
    public String getValue(String configKey, String defaultValue) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .eq(SystemConfigDO::getConfigKey, configKey)
                .eq(SystemConfigDO::getEnabled, true)
                .isNull(SystemConfigDO::getDeletedAt)
                .last("LIMIT 1");

        SystemConfigDO config = systemConfigMapper.selectOne(wrapper);
        return config != null ? config.getConfigValue() : defaultValue;
    }

    // ── 写操作 ───────────────────────────────────────────

    @Override
    @Transactional
    public SystemConfigVO create(SystemConfigDTO dto) {
        // 检查 key 唯一性
        long count = systemConfigMapper.selectCount(
                new LambdaQueryWrapper<SystemConfigDO>()
                        .eq(SystemConfigDO::getConfigKey, dto.getConfigKey())
                        .isNull(SystemConfigDO::getDeletedAt));
        if (count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "配置键已存在: " + dto.getConfigKey());
        }

        SystemConfigDO config = new SystemConfigDO();
        config.setConfigKey(dto.getConfigKey());
        config.setConfigValue(dto.getConfigValue());
        config.setConfigType(dto.getConfigType());
        config.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        config.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);

        systemConfigMapper.insert(config);
        log.info("系统配置已创建: key={}, type={}", config.getConfigKey(), config.getConfigType());
        return toVO(config);
    }

    @Override
    @Transactional
    public SystemConfigVO update(Long id, SystemConfigDTO dto) {
        SystemConfigDO config = requireExists(id);

        // configKey 不允许修改
        if (StringUtils.hasText(dto.getConfigValue())) {
            config.setConfigValue(dto.getConfigValue());
        }
        if (StringUtils.hasText(dto.getDescription())) {
            config.setDescription(dto.getDescription());
        }
        if (dto.getEnabled() != null) {
            config.setEnabled(dto.getEnabled());
        }

        systemConfigMapper.updateById(config);
        log.info("系统配置已更新: id={}, key={}", id, config.getConfigKey());
        return toVO(config);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SystemConfigDO config = requireExists(id);
        config.setDeletedAt(LocalDateTime.now());
        systemConfigMapper.updateById(config);
        log.info("系统配置已软删除: id={}, key={}", id, config.getConfigKey());
    }

    @Override
    @Transactional
    public void toggleEnabled(Long id, boolean enabled) {
        SystemConfigDO config = requireExists(id);
        config.setEnabled(enabled);
        systemConfigMapper.updateById(config);
        log.info("系统配置状态变更: id={}, key={}, enabled={}", id, config.getConfigKey(), enabled);
    }

    // ── 私有工具 ─────────────────────────────────────────

    private SystemConfigDO requireExists(Long id) {
        SystemConfigDO config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigDO>()
                        .eq(SystemConfigDO::getId, id)
                        .isNull(SystemConfigDO::getDeletedAt));
        if (config == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "系统配置不存在");
        }
        return config;
    }

    private SystemConfigVO toVO(SystemConfigDO config) {
        return SystemConfigVO.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .configValue(config.getConfigValue())
                .configType(config.getConfigType())
                .description(config.getDescription())
                .enabled(config.getEnabled())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
```

---

### 4.6 SystemConfigVO.java

```java
package com.aria.auth.interfaces.rest.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置视图对象
 */
@Data
@Builder
public class SystemConfigVO {

    private Long id;
    private String configKey;
    private String configValue;
    private String configType;
    private String description;

    @JsonProperty("enabled")
    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

---

### 4.7 SystemConfigDTO.java

```java
package com.aria.auth.interfaces.rest.vo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统配置创建 / 更新请求体
 * 注意：update 接口中 configKey 和 configType 字段不生效
 */
@Data
public class SystemConfigDTO {

    /** 配置键，仅 create 接口使用。格式：domain.subDomain.param */
    @NotBlank(message = "配置键不能为空")
    @Size(max = 100, message = "配置键长度不超过 100 字符")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9.]+$",
             message = "配置键只能包含字母、数字和点，且必须以字母开头")
    private String configKey;

    /** 配置值 */
    @NotBlank(message = "配置值不能为空")
    private String configValue;

    /** 配置类型：SYSTEM | CUSTOMER_SERVICE */
    @NotBlank(message = "配置类型不能为空")
    @Pattern(regexp = "^(SYSTEM|CUSTOMER_SERVICE)$", message = "配置类型无效")
    private String configType;

    /** 配置描述 */
    @Size(max = 255, message = "描述长度不超过 255 字符")
    private String description;

    /** 是否启用，默认 true */
    private Boolean enabled;
}
```

---

### 4.8 AdminSystemConfigController.java

```java
package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.auth.application.service.SystemConfigService;
import com.aria.auth.interfaces.rest.vo.SystemConfigVO;
import com.aria.auth.interfaces.rest.vo.dto.SystemConfigDTO;
import com.aria.common.core.page.PageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 系统配置管理接口
 * 基础路径：/api/v1/admin/system-config
 */
@RestController
@RequestMapping("/api/v1/admin/system-config")
@SaCheckLogin
@RequiredArgsConstructor
public class AdminSystemConfigController {

    private final SystemConfigService systemConfigService;

    /** 分页查询配置列表 */
    @GetMapping
    @SaCheckPermission("system:config:list")
    public R<PageResult<SystemConfigVO>> list(
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String keyword,
            PageQuery pageQuery) {
        return R.ok(systemConfigService.page(configType, keyword, pageQuery));
    }

    /** 查询单条配置 */
    @GetMapping("/{id}")
    @SaCheckPermission("system:config:list")
    public R<SystemConfigVO> getById(@PathVariable Long id) {
        return R.ok(systemConfigService.getById(id));
    }

    /** 新增配置 */
    @PostMapping
    @SaCheckPermission("system:config:create")
    public R<SystemConfigVO> create(@Valid @RequestBody SystemConfigDTO dto) {
        return R.ok(systemConfigService.create(dto));
    }

    /** 更新配置（configKey、configType 不可修改） */
    @PutMapping("/{id}")
    @SaCheckPermission("system:config:update")
    public R<SystemConfigVO> update(
            @PathVariable Long id,
            @Valid @RequestBody SystemConfigDTO dto) {
        return R.ok(systemConfigService.update(id, dto));
    }

    /** 删除配置（软删除） */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:config:delete")
    public R<Void> delete(@PathVariable Long id) {
        systemConfigService.delete(id);
        return R.ok();
    }

    /** 切换启用 / 禁用状态 */
    @PatchMapping("/{id}/enabled")
    @SaCheckPermission("system:config:update")
    public R<Void> toggleEnabled(
            @PathVariable Long id,
            @RequestBody EnabledRequest request) {
        systemConfigService.toggleEnabled(id, request.isEnabled());
        return R.ok();
    }

    @Data
    static class EnabledRequest {
        private boolean enabled;
    }
}
```

---

### 4.9 注意事项

1. **权限类型守卫**：`SYSTEM` 类型配置需确认 `StpInterfaceImpl` 中超级管理员角色判断正确。建议在 `SystemConfigServiceImpl.create/update/delete` 中额外校验：若 `configType = SYSTEM`，则要求调用方具备超管角色（`StpUtil.hasRole("super_admin")`）。

2. **缓存**：高频调用的 `mapByType()` 和 `getValue()` 可在后续版本引入 Spring Cache + Redis 缓存（TTL 5 分钟），本版本不引入，保持简单。

3. **configKey 不可变**：update 接口接收 DTO 但忽略 `configKey` 和 `configType` 字段，防止业务代码因 key 变更而失效。

## 5. 权限菜单 SQL

### 5.1 权限码 INSERT

在 `sys_permission` 表追加系统配置相关权限：

```sql
-- ============================================================
-- 系统配置权限码
-- id: 54–57（执行前确认当前最大 id）
-- ============================================================
INSERT INTO cs_auth.sys_permission (id, permission_code, permission_name, description, resource_type, created_at)
VALUES
(54, 'system:config:list',   '系统配置-查询', '查看系统配置列表及详情', 'BUTTON', now()),
(55, 'system:config:create', '系统配置-新增', '新增系统配置项',         'BUTTON', now()),
(56, 'system:config:update', '系统配置-编辑', '修改配置值及启用状态',   'BUTTON', now()),
(57, 'system:config:delete', '系统配置-删除', '软删除系统配置项',       'BUTTON', now());
```

### 5.2 菜单 INSERT

追加两条菜单记录（系统设置下一级 + 客服设置下一级）：

```sql
-- ============================================================
-- 系统配置菜单
-- 说明：
--   parent_id=10  → 系统管理父菜单（SYSTEM 类型配置入口）
--   parent_id=11  → 客服管理父菜单（CUSTOMER_SERVICE 类型配置入口）
-- 执行前通过 SELECT MAX(id) FROM cs_auth.sys_menu 确认 id
-- ============================================================
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_name, menu_path, component_path, menu_type, sort_order, is_visible, icon, created_at)
VALUES
(
    140,
    10,
    '系统参数配置',
    '/system/config',
    'system/config/index',
    'MENU',
    90,
    true,
    'Setting',
    now()
),
(
    206,
    11,
    '客服参数配置',
    '/customerservice/config',
    'system/config/index',
    'MENU',
    90,
    true,
    'Setting',
    now()
);
```

> 两个菜单共用同一 Vue 组件 `system/config/index.vue`，通过 `route.meta.configType` 区分过滤类型。

### 5.3 菜单-权限关联 INSERT

```sql
-- 菜单 140（SYSTEM 配置）关联全部 4 个权限码
INSERT INTO cs_auth.sys_menu_permission (menu_id, permission_id)
VALUES
(140, 54),
(140, 55),
(140, 56),
(140, 57);

-- 菜单 206（CUSTOMER_SERVICE 配置）关联全部 4 个权限码
INSERT INTO cs_auth.sys_menu_permission (menu_id, permission_id)
VALUES
(206, 54),
(206, 55),
(206, 56),
(206, 57);
```

### 5.4 角色-权限关联 INSERT

```sql
-- ============================================================
-- 角色权限分配
-- role_id=10  → 超级管理员（可管理 SYSTEM + CUSTOMER_SERVICE）
-- role_id=11  → 客服管理员（只管理 CUSTOMER_SERVICE）
-- ============================================================

-- 超级管理员获得全部 4 个权限
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES
(10, 54),
(10, 55),
(10, 56),
(10, 57);

-- 客服管理员获得全部 4 个权限（Service 层会额外校验 configType）
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id)
VALUES
(11, 54),
(11, 55),
(11, 56),
(11, 57);
```

### 5.5 角色-菜单关联 INSERT

```sql
-- 超级管理员可见两个菜单
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id)
VALUES
(10, 140),
(10, 206);

-- 客服管理员只可见客服参数配置菜单
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id)
VALUES
(11, 206);
```

### 5.6 SYSTEM 类型的额外服务端守卫

客服管理员虽持有 `system:config:*` 权限码，但不应能修改 `SYSTEM` 类型配置。
Service 层通过以下逻辑加一道守卫：

```java
// SystemConfigServiceImpl.java — create/update/delete 中添加
private void assertTypeAccess(String configType) {
    if ("SYSTEM".equals(configType) && !StpUtil.hasRole("super_admin")) {
        throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                "SYSTEM 类型配置仅超级管理员可操作");
    }
}
```

权限检查顺序：
```
Sa-Token 权限注解校验（system:config:create）
    ↓
assertTypeAccess(dto.getConfigType())  ← 额外类型守卫
    ↓
业务逻辑
```

## 6. 业务代码接入

各业务模块改为从 `SystemConfigService` 读取配置，同时保留硬编码兜底，确保 DB 配置缺失时服务不中断。

### 6.1 接入原则

```
优先级：DB 配置（is_enabled=true）> 硬编码默认值
```

推荐调用方式（`getValue` 方法）：

```java
// 注入 SystemConfigService
private final SystemConfigService systemConfigService;

// 读取整型配置，兜底默认值 5
int topK = Integer.parseInt(
    systemConfigService.getValue("knowledge.searchTopK", "5")
);

// 读取字符串配置，兜底默认欢迎语
String welcome = systemConfigService.getValue(
    "agent.welcomeMessage", "您好，请问有什么可以帮您？"
);
```

批量读取同类型配置（减少多次 DB 查询）：

```java
// 一次性加载所有 CUSTOMER_SERVICE 配置
Map<String, String> configs = systemConfigService.mapByType("CUSTOMER_SERVICE");

int maxConcurrent = Integer.parseInt(configs.getOrDefault("agent.maxConcurrent", "5"));
int topK          = Integer.parseInt(configs.getOrDefault("knowledge.searchTopK", "5"));
```

---

### 6.2 会话服务（conversation-service）

#### 6.2.1 座席并发上限校验

修改接受会话的业务逻辑（`ConversationService` 或 `AgentSessionService`）：

```java
// 修改前（硬编码）
private static final int MAX_CONCURRENT = 5;

// 修改后（从配置读取，硬编码兜底）
int maxConcurrent = Integer.parseInt(
    systemConfigService.getValue("agent.maxConcurrent", "5")
);
if (currentActiveCount >= maxConcurrent) {
    throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS.value(),
        "已达最大并发会话数，请先完成当前会话");
}
```

#### 6.2.2 欢迎消息

```java
// 会话创建后插入首条消息
String welcomeMsg = systemConfigService.getValue(
    "agent.welcomeMessage", "您好，感谢联系我们，请问有什么可以帮助您？"
);
// 插入系统消息到 cs_conversation_message
messageService.insertSystemMessage(conversationId, welcomeMsg);
```

#### 6.2.3 Prompt 模板

```java
// 获取模板并替换占位符
String promptTemplate = systemConfigService.getValue(
    "prompt.agent.suggestion",
    "请根据对话历史和知识库内容，为座席生成回复建议。\n对话历史：{history}\n知识库：{context}"
);
String prompt = promptTemplate
    .replace("{history}", historyText)
    .replace("{context}", contextText);
```

各 prompt 配置键与业务场景对应关系：

| config_key | 调用场景 | 占位符 |
|---|---|---|
| `prompt.agent.suggestion` | 座席建议回复生成 | `{history}`, `{context}` |
| `prompt.kb.qa` | 知识库问答 | `{context}`, `{question}` |
| `prompt.visitor.autoReply` | 访客自动回复 | `{context}`, `{history}` |
| `prompt.session.summary` | 会话结束摘要 | `{history}` |
| `prompt.intent.classify` | 意图识别分类 | `{intents}`, `{message}` |

---

### 6.3 知识库服务（knowledge-service）

#### 6.3.1 RAG 检索 TopK

```java
// KnowledgeSearchService 中
int topK = Integer.parseInt(
    systemConfigService.getValue("knowledge.searchTopK", "5")
);
List<ChunkDO> chunks = vectorStore.similaritySearch(query, topK);
```

#### 6.3.2 文件上传大小校验

```java
// KnowledgeDocumentController 或上传 Service 中
int maxFileSizeMb = Integer.parseInt(
    systemConfigService.getValue("knowledge.uploadMaxFileSizeMb", "20")
);
long maxBytes = (long) maxFileSizeMb * 1024 * 1024;
if (file.getSize() > maxBytes) {
    throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE.value(),
        "文件大小超过上限 " + maxFileSizeMb + "MB");
}
```

---

### 6.4 仪表盘服务（auth-service / dashboard）

```java
// DashboardService 中
int recentLimit = Integer.parseInt(
    systemConfigService.getValue("dashboard.recentLimit", "10")
);
List<RecentSessionVO> recent = sessionMapper.selectRecent(recentLimit);
```

---

### 6.5 跨模块调用方案

`SystemConfigService` 位于 `auth-service`。其他微服务（conversation-service、knowledge-service）有两种接入方式：

**方案 A：通过 auth-client Feign 接口（推荐）**

在 `ai-auth/auth-client` 中新增 Feign 客户端：

```java
// AuthSystemConfigClient.java (auth-client 模块)
@FeignClient(name = "auth-service", path = "/internal/system-config")
public interface AuthSystemConfigClient {

    @GetMapping("/value")
    R<String> getValue(@RequestParam String configKey,
                       @RequestParam String defaultValue);

    @GetMapping("/map")
    R<Map<String, String>> mapByType(@RequestParam String configType);
}
```

对应 `auth-service` 新增内部接口：

```java
// InternalSystemConfigController.java（不加 @SaCheckLogin，走内网鉴权）
@RestController
@RequestMapping("/internal/system-config")
public class InternalSystemConfigController {
    // 仅允许内部服务调用，通过 IP 白名单或内部网关鉴权
}
```

**方案 B：各服务维护本地配置副本（简单但数据不一致风险）**

暂不推荐，除非微服务间延迟极为敏感。

---

### 6.6 兜底策略

所有调用方必须提供兜底默认值，以下场景会触发回退：
- DB 中该 key 不存在
- 配置项 `is_enabled = false`
- auth-service 不可用（Feign 熔断）

```java
// 兜底默认值参考表
Map<String, String> DEFAULTS = Map.of(
    "agent.maxConcurrent",          "5",
    "agent.welcomeMessage",         "您好，感谢联系我们，请问有什么可以帮助您？",
    "knowledge.searchTopK",         "5",
    "knowledge.uploadMaxFileSizeMb","20",
    "dashboard.recentLimit",        "10"
);
```

## 7. API 接口规范

基础路径：`/api/v1/admin/system-config`

所有接口需通过 Sa-Token 登录校验（`@SaCheckLogin`），响应体统一使用 `R<T>` 包装。

---

### 7.1 GET /api/v1/admin/system-config — 分页查询

**权限：** `system:config:list`

**Query 参数：**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `configType` | string | 否 | `SYSTEM` 或 `CUSTOMER_SERVICE`，不传则返回全部 |
| `keyword` | string | 否 | 模糊匹配 `config_key` 或 `description` |
| `page` | int | 否 | 页码，从 0 开始，默认 0 |
| `size` | int | 否 | 每页条数，默认 20，最大 200 |

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 10,
    "page": 0,
    "size": 20,
    "items": [
      {
        "id": 1,
        "configKey": "agent.maxConcurrent",
        "configValue": "5",
        "configType": "CUSTOMER_SERVICE",
        "description": "单个座席同时接待的最大会话数",
        "enabled": true,
        "createdAt": "2026-07-12T10:00:00+08:00",
        "updatedAt": "2026-07-12T10:00:00+08:00"
      }
    ]
  }
}
```

---

### 7.2 GET /api/v1/admin/system-config/{id} — 查询单条

**权限：** `system:config:list`

**路径参数：** `id` (Long)

**响应：** 同上方 `items` 中的单个对象。

**错误码：**

| HTTP | code | 场景 |
|---|---|---|
| 404 | 404 | 配置不存在或已删除 |

---

### 7.3 POST /api/v1/admin/system-config — 新增配置

**权限：** `system:config:create`

**请求体：**

```json
{
  "configKey": "agent.maxConcurrent",
  "configValue": "5",
  "configType": "CUSTOMER_SERVICE",
  "description": "单个座席同时接待的最大会话数。取值范围：1–50",
  "enabled": true
}
```

**字段校验：**

| 字段 | 规则 |
|---|---|
| `configKey` | 必填，长度 ≤ 100，格式 `^[a-zA-Z][a-zA-Z0-9.]+$` |
| `configValue` | 必填 |
| `configType` | 必填，枚举值 `SYSTEM` 或 `CUSTOMER_SERVICE` |
| `description` | 可选，长度 ≤ 255 |
| `enabled` | 可选，默认 `true` |

**响应：** 201 + 创建后的配置对象

**错误码：**

| HTTP | code | 场景 |
|---|---|---|
| 400 | 400 | 字段校验失败 |
| 403 | 403 | 非超管尝试创建 SYSTEM 类型配置 |
| 409 | 409 | configKey 已存在 |

---

### 7.4 PUT /api/v1/admin/system-config/{id} — 更新配置

**权限：** `system:config:update`

**路径参数：** `id` (Long)

**请求体：**（`configKey` 和 `configType` 字段即使传入也不生效）

```json
{
  "configValue": "8",
  "description": "调整为 8 路并发",
  "enabled": true
}
```

**响应：** 更新后的配置对象

**错误码：**

| HTTP | code | 场景 |
|---|---|---|
| 400 | 400 | 字段校验失败 |
| 403 | 403 | 非超管尝试修改 SYSTEM 类型配置 |
| 404 | 404 | 配置不存在或已删除 |

---

### 7.5 DELETE /api/v1/admin/system-config/{id} — 删除配置

**权限：** `system:config:delete`

**路径参数：** `id` (Long)

**说明：** 软删除，设置 `deleted_at`，数据不物理删除。

**响应：** `R<Void>` — `{ "code": 200, "message": "success", "data": null }`

**错误码：**

| HTTP | code | 场景 |
|---|---|---|
| 403 | 403 | 非超管尝试删除 SYSTEM 类型配置 |
| 404 | 404 | 配置不存在或已删除 |

---

### 7.6 PATCH /api/v1/admin/system-config/{id}/enabled — 切换启用状态

**权限：** `system:config:update`

**路径参数：** `id` (Long)

**请求体：**

```json
{ "enabled": false }
```

**响应：** `R<Void>`

---

### 7.7 错误响应格式

所有错误统一通过全局异常处理器返回：

```json
{
  "code": 409,
  "message": "配置键已存在: agent.maxConcurrent",
  "data": null
}
```

---

### 7.8 实施检查清单

在提交代码前，逐项确认：

**DDL & 种子数据**
- [ ] `cs_auth.system_config` 表已创建（含索引、触发器）
- [ ] Flyway 迁移文件版本号正确，checksum 正常
- [ ] 10 条种子数据已插入

**Java 文件**
- [ ] `SystemConfigDO` — `@TableName` schema 前缀正确
- [ ] `SystemConfigMapper` — `@Mapper` 注解存在，被扫描到
- [ ] `SystemConfigService` 接口已定义全部方法
- [ ] `SystemConfigServiceImpl` — `mapByType` 和 `getValue` 正确过滤 `is_enabled=true`
- [ ] `SystemConfigDTO` — Bean Validation 注解完整
- [ ] `SystemConfigVO` — `@Builder` + `@Data`
- [ ] `AdminSystemConfigController` — 权限注解与权限码一致

**权限 SQL**
- [ ] `sys_permission` id 54–57 无冲突（执行前 `SELECT MAX(id)`）
- [ ] `sys_menu` id 140/206 无冲突
- [ ] 角色 10/11 权限及菜单关联已插入
- [ ] SYSTEM 类型守卫 `assertTypeAccess()` 已在 create/update/delete 中调用

**联调验证**
- [ ] 超管可 CRUD `SYSTEM` 类型配置
- [ ] 客服管理员可 CRUD `CUSTOMER_SERVICE` 配置
- [ ] 客服管理员尝试创建 `SYSTEM` 配置返回 403
- [ ] 删除配置后 `getValue()` 回退到默认值
- [ ] 前端 `system/config/index.vue` 两个路由可正常加载数据
