# P1 访客标签与会话备注 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为客服系统添加两层标签体系（访客持久标签 + 会话级标签）和会话内部备注功能，支持预定义字典和坐席自定义标签，备注对访客不可见。

**Architecture:** 新增 4 张表（`cs_tag`/`cs_visitor_tag`/`cs_conversation_tag`/`cs_conversation_note`）；`TagAppService` 统一处理打标逻辑（支持按 tagId 或 tagName 二选一，CUSTOM 标签自动创建，`usage_count` 原子自增，写操作加 `@Transactional`）；`NoteAppService` 处理备注 CRUD（编辑/删除有归属权限校验）；访客标签结果缓存 Redis TTL=24h；`SessionQueueItem` 追加 `visitorTags` 字段；SSE 新增 `TAG_UPDATED` 事件。

**Tech Stack:** Spring Boot, MyBatis-Plus (BaseMapper + LambdaWrapper), Redisson, RabbitMQ SSE fanout, Sa-Token, Lombok, JUnit 5 + Mockito

## Global Constraints

- Entity 类放 `com.aria.conversation.infrastructure.persistence.entity`，`@TableName(schema="cs_conversation", value="table_name")`
- Mapper 放 `com.aria.conversation.infrastructure.persistence.mapper`，继承 `BaseMapper<T>`，加 `@Mapper`
- Application Service 放 `com.aria.conversation.application.service`，`@Slf4j @Service @RequiredArgsConstructor`
- 写方法加 `@Transactional(rollbackFor = Exception.class)`，读方法不加
- 业务异常抛 `BusinessException`（`com.aria.common.core.exception`），error code 常量 `private static final int`
- 响应用 `R<T>`（`com.aria.common.web.response.R`）
- Controller 放 `com.aria.conversation.interfaces.rest`，`@Slf4j @Validated @RestController @RequestMapping @RequiredArgsConstructor`
- Sa-Token 权限：坐席操作用 `@SaCheckPermission("session:tag:write")`，管理端用 `"system:tag:manage"`
- 所有新表用 `create_time`（阿里规范）
- Schema 变更追加到 `docs/sql/conversation-service-schema.sql`
- 测试用 `@ExtendWith(MockitoExtension.class)` + AssertJ
- `SessionEventType` 枚举在 `com.aria.conversation.domain.SessionEventType`

---

## Task 1: DB Schema — 标签与备注表

**Files:**
- Modify: `docs/sql/conversation-service-schema.sql`

**Interfaces:**
- Produces:
  - `cs_tag(id, name, color, source, usage_count, created_by, create_time, update_time)`
  - `cs_visitor_tag(visitor_id, tag_id, tagged_by, create_time)`
  - `cs_conversation_tag(session_id, tag_id, tagged_by, create_time)`
  - `cs_conversation_note(id, session_id, content, created_by, create_time, update_time)`

- [ ] **Step 1: 追加 DDL**

打开 `docs/sql/conversation-service-schema.sql`，在文件末尾追加：

```sql
-- 标签字典
CREATE TABLE IF NOT EXISTS `cs_tag` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name`        VARCHAR(50)  NOT NULL                    COMMENT '标签名',
    `color`       VARCHAR(7)   NOT NULL DEFAULT '#6B7280'  COMMENT '十六进制色值',
    `source`      VARCHAR(10)  NOT NULL DEFAULT 'PRESET'   COMMENT 'PRESET | CUSTOM',
    `usage_count` INT          NOT NULL DEFAULT 0          COMMENT '使用次数（原子更新）',
    `created_by`  VARCHAR(64)                              COMMENT '创建人 userId',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签字典';

-- 访客持久标签（跨会话）
CREATE TABLE IF NOT EXISTS `cs_visitor_tag` (
    `visitor_id`  VARCHAR(64)  NOT NULL COMMENT 'anonymousId',
    `tag_id`      BIGINT       NOT NULL COMMENT 'FK → cs_tag.id',
    `tagged_by`   VARCHAR(64)  NOT NULL COMMENT '操作坐席 userId',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`visitor_id`, `tag_id`),
    INDEX `idx_visitor_id` (`visitor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客持久标签';

-- 会话级标签
CREATE TABLE IF NOT EXISTS `cs_conversation_tag` (
    `session_id`  VARCHAR(64)  NOT NULL COMMENT 'sessionId',
    `tag_id`      BIGINT       NOT NULL COMMENT 'FK → cs_tag.id',
    `tagged_by`   VARCHAR(64)  NOT NULL COMMENT '操作坐席 userId',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`, `tag_id`),
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话级标签';

-- 会话内部备注（对访客不可见）
CREATE TABLE IF NOT EXISTS `cs_conversation_note` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id`  VARCHAR(64)  NOT NULL COMMENT 'sessionId',
    `content`     TEXT         NOT NULL COMMENT '备注内容',
    `created_by`  VARCHAR(64)  NOT NULL COMMENT '坐席 userId',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话内部备注';
```

- [ ] **Step 2: 追加预置标签数据**

打开 `docs/sql/conversation-service-data.sql`，追加：

```sql
-- 预置标签
INSERT IGNORE INTO `cs_tag` (`name`, `color`, `source`) VALUES
('VIP',    '#F59E0B', 'PRESET'),
('潜在客户', '#10B981', 'PRESET'),
('投诉用户', '#EF4444', 'PRESET'),
('高价值',  '#6366F1', 'PRESET'),
('需跟进',  '#F97316', 'PRESET');
```

- [ ] **Step 3: 在本地数据库执行**

```bash
mysql -u root -p cs_conversation -e "
  CREATE TABLE IF NOT EXISTS cs_tag ...;  -- 执行上述 DDL
"
-- 验证
SELECT * FROM cs_tag;
-- 应有 5 条预置标签
```

- [ ] **Step 4: Commit**

```bash
git add docs/sql/
git commit -m "feat(tags): add tag, visitor_tag, conversation_tag, note tables"
```

---

## Task 2: Entity + Mapper + TagVO/NoteVO

**Files:**
- Create: `...entity/TagEntity.java`
- Create: `...entity/VisitorTagEntity.java`
- Create: `...entity/ConversationTagEntity.java`
- Create: `...entity/ConversationNoteEntity.java`
- Create: `...mapper/TagMapper.java`
- Create: `...mapper/VisitorTagMapper.java`
- Create: `...mapper/ConversationTagMapper.java`
- Create: `...mapper/ConversationNoteMapper.java`
- Create: `...interfaces/rest/vo/TagVO.java`
- Create: `...interfaces/rest/vo/NoteVO.java`

**Interfaces:**
- Produces:
  - `TagVO(Long id, String name, String color, String source)`
  - `NoteVO(Long id, String content, String createdBy, LocalDateTime createTime, LocalDateTime updateTime)`
  - `TagMapper.selectByName(String name): TagEntity`
  - `VisitorTagMapper.selectTagsByVisitorId(String visitorId): List<TagEntity>`
  - `ConversationTagMapper.selectTagsBySessionId(String sessionId): List<TagEntity>`

- [ ] **Step 1: 创建 TagEntity**

```java
package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@TableName(schema = "cs_conversation", value = "cs_tag")
public class TagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String color;
    private String source;   // PRESET | CUSTOM
    private Integer usageCount;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: 创建关联表 Entity**

```java
// VisitorTagEntity.java
package com.aria.conversation.infrastructure.persistence.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@TableName(schema = "cs_conversation", value = "cs_visitor_tag")
public class VisitorTagEntity {
    private String visitorId;
    private Long tagId;
    private String taggedBy;
    private LocalDateTime createTime;
}
```

```java
// ConversationTagEntity.java
@Data @Builder
@TableName(schema = "cs_conversation", value = "cs_conversation_tag")
public class ConversationTagEntity {
    private String sessionId;
    private Long tagId;
    private String taggedBy;
    private LocalDateTime createTime;
}
```

- [ ] **Step 3: 创建 ConversationNoteEntity**

```java
package com.aria.conversation.infrastructure.persistence.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@TableName(schema = "cs_conversation", value = "cs_conversation_note")
public class ConversationNoteEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String content;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 4: 创建 Mapper（含联表查询）**

```java
// TagMapper.java
@Mapper
public interface TagMapper extends BaseMapper<TagEntity> {
    default TagEntity selectByName(String name) {
        return selectOne(Wrappers.<TagEntity>lambdaQuery()
                .eq(TagEntity::getName, name));
    }
}

// VisitorTagMapper.java
@Mapper
public interface VisitorTagMapper extends BaseMapper<VisitorTagEntity> {
    /** 查询访客所有标签（JOIN cs_tag） — XML 实现 */
    List<TagEntity> selectTagsByVisitorId(@Param("visitorId") String visitorId);

    default boolean existsTag(String visitorId, Long tagId) {
        return exists(Wrappers.<VisitorTagEntity>lambdaQuery()
                .eq(VisitorTagEntity::getVisitorId, visitorId)
                .eq(VisitorTagEntity::getTagId, tagId));
    }
}

// ConversationTagMapper.java
@Mapper
public interface ConversationTagMapper extends BaseMapper<ConversationTagEntity> {
    List<TagEntity> selectTagsBySessionId(@Param("sessionId") String sessionId);
}

// ConversationNoteMapper.java
@Mapper
public interface ConversationNoteMapper extends BaseMapper<ConversationNoteEntity> {
    default List<ConversationNoteEntity> selectBySessionId(String sessionId) {
        return selectList(Wrappers.<ConversationNoteEntity>lambdaQuery()
                .eq(ConversationNoteEntity::getSessionId, sessionId)
                .orderByAsc(ConversationNoteEntity::getCreateTime));
    }
}
```

- [ ] **Step 5: 创建联表查询 XML**

创建 `src/main/resources/mapper/VisitorTagMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.aria.conversation.infrastructure.persistence.mapper.VisitorTagMapper">
    <select id="selectTagsByVisitorId"
            resultType="com.aria.conversation.infrastructure.persistence.entity.TagEntity">
        SELECT t.id, t.name, t.color, t.source
        FROM cs_conversation.cs_visitor_tag vt
        JOIN cs_conversation.cs_tag t ON t.id = vt.tag_id
        WHERE vt.visitor_id = #{visitorId}
        ORDER BY vt.create_time DESC
    </select>
</mapper>
```

创建 `src/main/resources/mapper/ConversationTagMapper.xml`（结构同上，表名换 `cs_conversation_tag`，WHERE 用 `session_id`）。

- [ ] **Step 6: 创建 VO**

```java
// TagVO.java
package com.aria.conversation.interfaces.rest.vo;
public record TagVO(Long id, String name, String color, String source) {}

// NoteVO.java
public record NoteVO(Long id, String content, String createdBy,
                     LocalDateTime createTime, LocalDateTime updateTime) {}
```

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(tags): add Tag/VisitorTag/ConversationTag/Note entities, mappers, VOs"
```

---

## Task 3: TagAppService — 核心业务逻辑

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/TagAppService.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/TagAppServiceTest.java`

**Interfaces:**
- Consumes: `TagMapper`, `VisitorTagMapper`, `ConversationTagMapper`, `StringRedisTemplate`, `ConversationMapper`（取 visitorId）
- Produces:
  - `TagAppService.addVisitorTag(String sessionId, String operatorId, Long tagId, String tagName): TagVO`
  - `TagAppService.removeVisitorTag(String sessionId, String operatorId, Long tagId): void`
  - `TagAppService.addSessionTag(String sessionId, String operatorId, Long tagId, String tagName): TagVO`
  - `TagAppService.removeSessionTag(String sessionId, String operatorId, Long tagId): void`
  - `TagAppService.listVisitorTags(String sessionId): List<TagVO>`
  - `TagAppService.listSessionTags(String sessionId): List<TagVO>`

- [ ] **Step 1: 编写失败测试**

创建 `TagAppServiceTest.java`（先写测试，再实现）：

```java
package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.entity.VisitorTagEntity;
import com.aria.conversation.infrastructure.persistence.mapper.*;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagAppServiceTest {

    @Mock TagMapper               tagMapper;
    @Mock VisitorTagMapper        visitorTagMapper;
    @Mock ConversationTagMapper   conversationTagMapper;
    @Mock ConversationNoteMapper  noteMapper;
    @Mock ConversationMapper      conversationMapper;
    @Mock StringRedisTemplate     redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    TagAppService service;

    private static final String SESSION_ID  = "sess-001";
    private static final String VISITOR_ID  = "visitor-abc";
    private static final String OPERATOR_ID = "agent-001";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TagAppService(tagMapper, visitorTagMapper, conversationTagMapper,
                                    noteMapper, conversationMapper, redisTemplate);

        // 默认：会话存在且有 visitorId
        ConversationEntity conv = new ConversationEntity();
        conv.setSessionId(SESSION_ID);
        conv.setVisitorId(VISITOR_ID);
        when(conversationMapper.selectBySessionId(SESSION_ID)).thenReturn(conv);
    }

    @Test
    @DisplayName("用已有 tagId 打访客标签 -> 写关联表 + 更新计数 + 失效缓存")
    void addVisitorTag_byId_success() {
        TagEntity tag = TagEntity.builder().id(1L).name("VIP").color("#F59E0B").source("PRESET").build();
        when(tagMapper.selectById(1L)).thenReturn(tag);
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(false);

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, 1L, null);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("VIP");

        ArgumentCaptor<VisitorTagEntity> captor = ArgumentCaptor.forClass(VisitorTagEntity.class);
        verify(visitorTagMapper).insert(captor.capture());
        assertThat(captor.getValue().getVisitorId()).isEqualTo(VISITOR_ID);
        assertThat(captor.getValue().getTaggedBy()).isEqualTo(OPERATOR_ID);

        // usage_count 原子自增
        verify(tagMapper).atomicIncrUsageCount(1L);
        // 缓存失效
        verify(redisTemplate).delete("visitor:tags:" + VISITOR_ID);
    }

    @Test
    @DisplayName("用不存在的 tagName 打标 -> 自动创建 CUSTOM 标签")
    void addVisitorTag_byNewName_createsCustomTag() {
        when(tagMapper.selectByName("新客户")).thenReturn(null);
        when(tagMapper.selectById(any())).thenReturn(null);
        // insert 后 id 由 DB 填充，模拟
        doAnswer(inv -> {
            TagEntity e = inv.getArgument(0);
            e.setId(99L);
            return 1;
        }).when(tagMapper).insert(any(TagEntity.class));

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, null, "新客户");

        ArgumentCaptor<TagEntity> tagCaptor = ArgumentCaptor.forClass(TagEntity.class);
        verify(tagMapper).insert(tagCaptor.capture());
        assertThat(tagCaptor.getValue().getSource()).isEqualTo("CUSTOM");
        assertThat(tagCaptor.getValue().getName()).isEqualTo("新客户");
        assertThat(result.id()).isEqualTo(99L);
    }

    @Test
    @DisplayName("重复打标签 -> 幂等跳过，不抛异常")
    void addVisitorTag_duplicate_idempotent() {
        TagEntity tag = TagEntity.builder().id(1L).name("VIP").color("#F59E0B").source("PRESET").build();
        when(tagMapper.selectById(1L)).thenReturn(tag);
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(true);  // 已存在

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, 1L, null);

        assertThat(result.id()).isEqualTo(1L);
        verify(visitorTagMapper, never()).insert(any());  // 不重复写入
        verify(tagMapper, never()).atomicIncrUsageCount(any());
    }

    @Test
    @DisplayName("移除访客标签 -> 删关联 + usage_count 自减 + 失效缓存")
    void removeVisitorTag_success() {
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(true);

        service.removeVisitorTag(SESSION_ID, OPERATOR_ID, 1L);

        verify(visitorTagMapper).deleteByVisitorIdAndTagId(VISITOR_ID, 1L);
        verify(tagMapper).atomicDecrUsageCount(1L);
        verify(redisTemplate).delete("visitor:tags:" + VISITOR_ID);
    }
}
```

- [ ] **Step 2: 运行测试（验证失败）**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=TagAppServiceTest -q
```

期望：编译失败（TagAppService 不存在）。

- [ ] **Step 3: 实现 TagAppService**

创建 `TagAppService.java`：

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.*;
import com.aria.conversation.infrastructure.persistence.mapper.*;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagAppService {

    private static final int    NOT_FOUND      = 40400;
    private static final String VISITOR_CACHE_PREFIX = "visitor:tags:";
    private static final long   CACHE_TTL_HOURS = 24;

    private final TagMapper              tagMapper;
    private final VisitorTagMapper       visitorTagMapper;
    private final ConversationTagMapper  conversationTagMapper;
    private final ConversationNoteMapper noteMapper;
    private final ConversationMapper     conversationMapper;
    private final StringRedisTemplate    redisTemplate;

    // ── 访客持久标签 ────────────────────────────────────────────────────────────

    public List<TagVO> listVisitorTags(String sessionId) {
        String visitorId = requireVisitorId(sessionId);
        return visitorTagMapper.selectTagsByVisitorId(visitorId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public TagVO addVisitorTag(String sessionId, String operatorId,
                                Long tagId, String tagName) {
        String visitorId = requireVisitorId(sessionId);
        TagEntity tag = resolveOrCreateTag(tagId, tagName, operatorId);

        if (!visitorTagMapper.existsTag(visitorId, tag.getId())) {
            visitorTagMapper.insert(VisitorTagEntity.builder()
                    .visitorId(visitorId).tagId(tag.getId()).taggedBy(operatorId).build());
            tagMapper.atomicIncrUsageCount(tag.getId());
        }
        evictVisitorCache(visitorId);
        return toVO(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeVisitorTag(String sessionId, String operatorId, Long tagId) {
        String visitorId = requireVisitorId(sessionId);
        if (visitorTagMapper.existsTag(visitorId, tagId)) {
            visitorTagMapper.deleteByVisitorIdAndTagId(visitorId, tagId);
            tagMapper.atomicDecrUsageCount(tagId);
        }
        evictVisitorCache(visitorId);
    }

    // ── 会话级标签 ──────────────────────────────────────────────────────────────

    public List<TagVO> listSessionTags(String sessionId) {
        return conversationTagMapper.selectTagsBySessionId(sessionId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public TagVO addSessionTag(String sessionId, String operatorId,
                                Long tagId, String tagName) {
        TagEntity tag = resolveOrCreateTag(tagId, tagName, operatorId);
        boolean exists = conversationTagMapper.exists(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConversationTagEntity>lambdaQuery()
                        .eq(ConversationTagEntity::getSessionId, sessionId)
                        .eq(ConversationTagEntity::getTagId, tag.getId()));
        if (!exists) {
            conversationTagMapper.insert(ConversationTagEntity.builder()
                    .sessionId(sessionId).tagId(tag.getId()).taggedBy(operatorId).build());
            tagMapper.atomicIncrUsageCount(tag.getId());
        }
        return toVO(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSessionTag(String sessionId, String operatorId, Long tagId) {
        boolean exists = conversationTagMapper.exists(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConversationTagEntity>lambdaQuery()
                        .eq(ConversationTagEntity::getSessionId, sessionId)
                        .eq(ConversationTagEntity::getTagId, tagId));
        if (exists) {
            conversationTagMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConversationTagEntity>lambdaQuery()
                            .eq(ConversationTagEntity::getSessionId, sessionId)
                            .eq(ConversationTagEntity::getTagId, tagId));
            tagMapper.atomicDecrUsageCount(tagId);
        }
    }

    // ── 私有辅助 ────────────────────────────────────────────────────────────────

    private String requireVisitorId(String sessionId) {
        ConversationEntity conv = conversationMapper.selectBySessionId(sessionId);
        if (conv == null || conv.getVisitorId() == null) {
            throw new BusinessException(NOT_FOUND, "会话不存在或访客 ID 为空: " + sessionId);
        }
        return conv.getVisitorId();
    }

    private TagEntity resolveOrCreateTag(Long tagId, String tagName, String createdBy) {
        if (tagId != null) {
            TagEntity tag = tagMapper.selectById(tagId);
            if (tag == null) throw new BusinessException(NOT_FOUND, "标签不存在: " + tagId);
            return tag;
        }
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(40001, "tagId 和 tagName 不能同时为空");
        }
        TagEntity existing = tagMapper.selectByName(tagName);
        if (existing != null) return existing;

        TagEntity newTag = TagEntity.builder()
                .name(tagName).color("#6B7280").source("CUSTOM").createdBy(createdBy).build();
        tagMapper.insert(newTag);
        return newTag;
    }

    private void evictVisitorCache(String visitorId) {
        redisTemplate.delete(VISITOR_CACHE_PREFIX + visitorId);
    }

    private TagVO toVO(TagEntity e) {
        return new TagVO(e.getId(), e.getName(), e.getColor(), e.getSource());
    }
}
```

- [ ] **Step 4: 在 TagMapper 中添加原子自增/自减方法**

在 `TagMapper.java` 中追加：

```java
@Update("UPDATE cs_conversation.cs_tag SET usage_count = usage_count + 1 WHERE id = #{id}")
void atomicIncrUsageCount(@Param("id") Long id);

@Update("UPDATE cs_conversation.cs_tag SET usage_count = GREATEST(usage_count - 1, 0) WHERE id = #{id}")
void atomicDecrUsageCount(@Param("id") Long id);
```

- [ ] **Step 5: 在 VisitorTagMapper 中添加删除方法**

```java
default void deleteByVisitorIdAndTagId(String visitorId, Long tagId) {
    delete(Wrappers.<VisitorTagEntity>lambdaQuery()
            .eq(VisitorTagEntity::getVisitorId, visitorId)
            .eq(VisitorTagEntity::getTagId, tagId));
}
```

- [ ] **Step 6: 运行测试**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -Dtest=TagAppServiceTest -q
```

期望：5 个测试全 PASS。

- [ ] **Step 7: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(tags): add TagAppService with visitor/session tag CRUD and atomic usage_count"
```

---

## Task 4: NoteAppService + 管理端标签 Controller

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/NoteAppService.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/TagAdminController.java`
- Create: `ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/NoteAppServiceTest.java`

**Interfaces:**
- Consumes: `ConversationNoteMapper`
- Produces:
  - `NoteAppService.listNotes(String sessionId): List<NoteVO>`
  - `NoteAppService.addNote(String sessionId, String operatorId, String content): NoteVO`
  - `NoteAppService.updateNote(Long noteId, String operatorId, String content): NoteVO`
  - `NoteAppService.deleteNote(Long noteId, String operatorId, boolean isAdmin): void`
  - `TagAdminController` CRUD endpoints for `cs_tag`

- [ ] **Step 1: 编写 NoteAppService 失败测试**

创建 `NoteAppServiceTest.java`：

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.ConversationNoteEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationNoteMapper;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoteAppServiceTest {

    @Mock ConversationNoteMapper noteMapper;

    NoteAppService service;

    @BeforeEach
    void setUp() {
        service = new NoteAppService(noteMapper);
    }

    @Test
    @DisplayName("新增备注 -> 插入 DB 并返回 NoteVO")
    void addNote_success() {
        doAnswer(inv -> {
            ConversationNoteEntity e = inv.getArgument(0);
            e.setId(1L);
            e.setCreateTime(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            return 1;
        }).when(noteMapper).insert(any());

        NoteVO result = service.addNote("sess-001", "agent-001", "重要客户");

        ArgumentCaptor<ConversationNoteEntity> captor =
                ArgumentCaptor.forClass(ConversationNoteEntity.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("重要客户");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("agent-001");
        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("他人备注只有管理员可删 -> 普通坐席删他人备注抛异常")
    void deleteNote_otherOwner_nonAdmin_throws() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        assertThatThrownBy(() -> service.deleteNote(1L, "agent-001", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @DisplayName("管理员可删任意备注")
    void deleteNote_admin_success() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        service.deleteNote(1L, "admin-001", true);  // isAdmin=true

        verify(noteMapper).deleteById(1L);
    }

    @Test
    @DisplayName("修改他人备注 -> 抛异常")
    void updateNote_otherOwner_throws() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        assertThatThrownBy(() -> service.updateNote(1L, "agent-001", "新内容"))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: 运行测试（验证失败）**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=NoteAppServiceTest -q
```

期望：编译失败（NoteAppService 不存在）。

- [ ] **Step 3: 实现 NoteAppService**

创建 `NoteAppService.java`：

```java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.ConversationNoteEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationNoteMapper;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAppService {

    private static final int NOT_FOUND = 40400;
    private static final int FORBIDDEN = 40300;

    private final ConversationNoteMapper noteMapper;

    public List<NoteVO> listNotes(String sessionId) {
        return noteMapper.selectBySessionId(sessionId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO addNote(String sessionId, String operatorId, String content) {
        ConversationNoteEntity entity = ConversationNoteEntity.builder()
                .sessionId(sessionId).content(content).createdBy(operatorId).build();
        noteMapper.insert(entity);
        return toVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO updateNote(Long noteId, String operatorId, String content) {
        ConversationNoteEntity note = requireNote(noteId);
        if (!note.getCreatedBy().equals(operatorId)) {
            throw new BusinessException(FORBIDDEN, "无权修改他人备注");
        }
        note.setContent(content);
        noteMapper.updateById(note);
        return toVO(noteMapper.selectById(noteId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteNote(Long noteId, String operatorId, boolean isAdmin) {
        ConversationNoteEntity note = requireNote(noteId);
        if (!isAdmin && !note.getCreatedBy().equals(operatorId)) {
            throw new BusinessException(FORBIDDEN, "无权删除他人备注");
        }
        noteMapper.deleteById(noteId);
    }

    private ConversationNoteEntity requireNote(Long noteId) {
        ConversationNoteEntity note = noteMapper.selectById(noteId);
        if (note == null) throw new BusinessException(NOT_FOUND, "备注不存在: " + noteId);
        return note;
    }

    private NoteVO toVO(ConversationNoteEntity e) {
        return new NoteVO(e.getId(), e.getContent(), e.getCreatedBy(),
                          e.getCreateTime(), e.getUpdateTime());
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl ai-conversation/conversation-service -Dtest=NoteAppServiceTest -q
```

期望：4 个测试全 PASS。

- [ ] **Step 5: 创建 TagAdminController（管理端标签字典）**

创建 `TagAdminController.java`：

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.web.response.R;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.mapper.TagMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
public class TagAdminController {

    private static final int CONFLICT  = 40900;
    private static final int NOT_FOUND = 40400;

    private final TagMapper tagMapper;

    @GetMapping
    @SaCheckPermission("system:tag:manage")
    public R<List<TagEntity>> list(
            @RequestParam(required = false) String source) {
        var wrapper = Wrappers.<TagEntity>lambdaQuery();
        if (source != null) wrapper.eq(TagEntity::getSource, source);
        return R.ok(tagMapper.selectList(wrapper.orderByAsc(TagEntity::getName)));
    }

    @PostMapping
    @SaCheckPermission("system:tag:manage")
    public R<TagEntity> create(@RequestBody @Validated CreateTagReq req) {
        if (tagMapper.selectByName(req.getName()) != null) {
            throw new BusinessException(CONFLICT, "标签名已存在: " + req.getName());
        }
        TagEntity entity = TagEntity.builder()
                .name(req.getName()).color(req.getColor())
                .source("PRESET").build();
        tagMapper.insert(entity);
        return R.ok(entity);
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> update(@PathVariable Long id,
                           @RequestBody @Validated UpdateTagReq req) {
        TagEntity existing = tagMapper.selectById(id);
        if (existing == null) throw new BusinessException(NOT_FOUND, "标签不存在");
        existing.setName(req.getName());
        existing.setColor(req.getColor());
        existing.setSource(req.getSource());
        tagMapper.updateById(existing);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> delete(@PathVariable Long id) {
        // usage_count > 0 时前端已二次确认，后端直接执行
        tagMapper.deleteById(id);
        return R.ok();
    }

    @Data
    public static class CreateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
    }

    @Data
    public static class UpdateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
        @NotBlank private String source;
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(tags): add NoteAppService and TagAdminController"
```

---

## Task 5: 坐席端 Tag/Note Controllers

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/SessionTagController.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/VisitorTagController.java`
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/SessionNoteController.java`

**Interfaces:**
- Consumes: `TagAppService`, `NoteAppService`
- Produces:
  - `GET/POST/DELETE /api/v1/sessions/{sessionId}/visitor/tags`
  - `GET/POST/DELETE /api/v1/sessions/{sessionId}/tags`
  - `GET/POST/PUT/DELETE /api/v1/sessions/{sessionId}/notes`

- [ ] **Step 1: 创建 VisitorTagController**

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.TagAppService;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 访客持久标签操作（通过 sessionId 上下文鉴权）。
 * 路径设计决策：保留 /sessions/{sessionId}/visitor/tags 而非 /visitors/{visitorId}/tags，
 * 原因：坐席操作入口是会话上下文，便于直接鉴权（只有当前会话坐席可操作）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/visitor/tags")
@RequiredArgsConstructor
public class VisitorTagController {

    private final TagAppService tagAppService;

    @GetMapping
    @SaCheckPermission("session:tag:write")
    public R<List<TagVO>> listVisitorTags(@PathVariable String sessionId) {
        return R.ok(tagAppService.listVisitorTags(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:tag:write")
    public R<TagVO> addVisitorTag(@PathVariable String sessionId,
                                   @RequestBody @Validated TagReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(tagAppService.addVisitorTag(sessionId, operatorId, req.getTagId(), req.getTagName()));
    }

    @DeleteMapping("/{tagId}")
    @SaCheckPermission("session:tag:write")
    public R<Void> removeVisitorTag(@PathVariable String sessionId,
                                     @PathVariable Long tagId) {
        String operatorId = StpUtil.getLoginIdAsString();
        tagAppService.removeVisitorTag(sessionId, operatorId, tagId);
        return R.ok();
    }

    @Data
    public static class TagReq {
        private Long   tagId;    // tagId 和 tagName 二选一
        private String tagName;
    }
}
```

- [ ] **Step 2: 创建 SessionTagController（会话级标签）**

结构与 VisitorTagController 完全相同，仅路径和调用方法不同：

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.TagAppService;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/tags")
@RequiredArgsConstructor
public class SessionTagController {

    private final TagAppService tagAppService;

    @GetMapping
    @SaCheckPermission("session:tag:write")
    public R<List<TagVO>> listSessionTags(@PathVariable String sessionId) {
        return R.ok(tagAppService.listSessionTags(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:tag:write")
    public R<TagVO> addSessionTag(@PathVariable String sessionId,
                                   @RequestBody @Validated VisitorTagController.TagReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(tagAppService.addSessionTag(sessionId, operatorId, req.getTagId(), req.getTagName()));
    }

    @DeleteMapping("/{tagId}")
    @SaCheckPermission("session:tag:write")
    public R<Void> removeSessionTag(@PathVariable String sessionId,
                                     @PathVariable Long tagId) {
        String operatorId = StpUtil.getLoginIdAsString();
        tagAppService.removeSessionTag(sessionId, operatorId, tagId);
        return R.ok();
    }
}
```

- [ ] **Step 3: 创建 SessionNoteController**

```java
package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.NoteAppService;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/notes")
@RequiredArgsConstructor
public class SessionNoteController {

    private final NoteAppService noteAppService;

    @GetMapping
    @SaCheckPermission("session:note:write")
    public R<List<NoteVO>> listNotes(@PathVariable String sessionId) {
        return R.ok(noteAppService.listNotes(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:note:write")
    public R<NoteVO> addNote(@PathVariable String sessionId,
                              @RequestBody @Validated NoteReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(noteAppService.addNote(sessionId, operatorId, req.getContent()));
    }

    @PutMapping("/{noteId}")
    @SaCheckPermission("session:note:write")
    public R<NoteVO> updateNote(@PathVariable String sessionId,
                                 @PathVariable Long noteId,
                                 @RequestBody @Validated NoteReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(noteAppService.updateNote(noteId, operatorId, req.getContent()));
    }

    @DeleteMapping("/{noteId}")
    @SaCheckPermission("session:note:write")
    public R<Void> deleteNote(@PathVariable String sessionId,
                               @PathVariable Long noteId) {
        String operatorId = StpUtil.getLoginIdAsString();
        // 检查当前用户是否为管理员角色
        boolean isAdmin = StpUtil.hasRole("super_admin") || StpUtil.hasRole("kf_manager");
        noteAppService.deleteNote(noteId, operatorId, isAdmin);
        return R.ok();
    }

    @Data
    public static class NoteReq {
        @NotBlank @Size(max = 2000) private String content;
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(tags): add VisitorTagController, SessionTagController, SessionNoteController"
```

---

## Task 6: SessionQueueItem 追加 visitorTags + SSE TAG_UPDATED 事件

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionQueueItem.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/domain/SessionEventType.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/SessionQueueService.java`（追加 visitorTags 填充）

**Interfaces:**
- Produces:
  - `SessionQueueItem` 增加 `visitorTags: List<TagVO>`
  - `SessionEventType.TAG_UPDATED`

- [ ] **Step 1: 扩展 SessionQueueItem**

打开 `SessionQueueItem.java`，在 record 末尾追加字段（注意：record 组件顺序固定，新增必须加在末尾）：

```java
@JsonPropertyOrder({"sessionId", "userName", "transferReason", "tag",
                    "waitSince", "status", "agentId", "visitorTags"})
public record SessionQueueItem(
        String sessionId,
        String userName,
        String transferReason,
        String tag,
        long waitSince,
        SessionStatus status,
        String agentId,
        // 新增：访客持久标签，坐席查看队列时直接展示
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<TagVO> visitorTags
) {
    /** 向后兼容构造器：无 visitorTags 时传 null */
    public SessionQueueItem(String sessionId, String userName, String transferReason,
                             String tag, long waitSince, SessionStatus status, String agentId) {
        this(sessionId, userName, transferReason, tag, waitSince, status, agentId, null);
    }
}
```

> **重要：** 在 `SessionQueueRepository`（Redis CAS 操作）中检查是否依赖字段顺序进行字符串比较，若有则确认旧构造器保持字段顺序兼容。

- [ ] **Step 2: 在 SessionEventType 追加 TAG_UPDATED**

打开 `SessionEventType.java`：

```java
public enum SessionEventType {
    ENQUEUE,
    ACCEPTED,
    CLOSED,
    TRANSFER,
    TAG_UPDATED   // 新增：标签变更通知，多坐席协作时实时同步
}
```

- [ ] **Step 3: 在 TagAppService 中发布 TAG_UPDATED SSE 事件**

在 `TagAppService` 中注入 `SessionQueueService`（或直接注入 `RabbitTemplate eventsRabbitTemplate`），在 `addVisitorTag`/`removeVisitorTag`/`addSessionTag`/`removeSessionTag` 方法末尾发布事件：

```java
// 在 TagAppService 类中注入（追加字段和构造参数）
private final org.springframework.amqp.rabbit.core.RabbitTemplate eventsRabbitTemplate;
@org.springframework.beans.factory.annotation.Value("${conversation.events.exchange}")
private String eventsExchange;

// 在 addVisitorTag / removeVisitorTag 末尾调用
private void publishTagUpdatedEvent(String sessionId, String visitorId) {
    try {
        var event = Map.of(
            "type", SessionEventType.TAG_UPDATED.name(),
            "sessionId", sessionId,
            "visitorId", visitorId,
            "visitorTags", listVisitorTags(sessionId),
            "sessionTags", listSessionTags(sessionId)
        );
        eventsRabbitTemplate.convertAndSend(eventsExchange, "", event);
    } catch (Exception e) {
        log.warn("[TagAppService] TAG_UPDATED event publish failed session={}", sessionId, e);
    }
}
```

> **注意：** `eventsRabbitTemplate` 需要 `@Qualifier("eventsRabbitTemplate")` 注入，参考 `SessionQueueService` 中的注入方式，在构造函数中显式声明。

- [ ] **Step 4: 运行全量测试**

```bash
cd ai-conversation/conversation-service
mvn test -pl . -q
```

期望：所有测试 PASS，无编译错误。

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/
git commit -m "feat(tags): extend SessionQueueItem with visitorTags, add TAG_UPDATED SSE event"
```

---
