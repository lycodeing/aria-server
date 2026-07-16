# 快捷回复（Canned Responses）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 conversation-service 中实现坐席快捷回复功能，包括分组管理、公共/私人模板 CRUD、全文检索搜索、使用次数统计。

**Architecture:** 遵循现有 DDD 四层结构（interfaces → application → domain → infrastructure）。持久层复用 MyBatis-Plus LambdaWrapper 模式，搜索使用 PostgreSQL `to_tsvector` + GIN 索引。新增两张表 `cs_canned_response_group` + `cs_canned_response`，均落在 `cs_conversation` schema。

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis-Plus 3.5.7, PostgreSQL 16, Sa-Token（`StpUtil.getLoginIdAsLong()` 获取当前坐席 ID）

## Global Constraints

- 所有表名带 `cs_conversation.` schema 前缀
- Mapper 单表 CRUD 一律用 LambdaWrapper，禁止 `@Update/@Select` 魔法字符串 SQL；仅复杂查询（全文检索、`use_count` 原子递增）允许 `@Select/@Update`
- Controller 返回类型统一为 `R<T>`（`com.aria.common.web.response.R`）
- 请求体校验用 Jakarta Validation（`@NotBlank`、`@Size` 等）
- 坐席身份通过 `StpUtil.getLoginIdAsLong()` 获取（Long 类型）
- SQL schema 变更写入 `docs/sql/canned-response-schema.sql` 并同步追加到 `docs/sql/conversation-service-schema.sql`

---

## 文件清单

| 类型 | 路径 |
|------|------|
| 新建 SQL | `docs/sql/canned-response-schema.sql` |
| 新建 DO | `…/infrastructure/canned/CannedResponseGroupDO.java` |
| 新建 DO | `…/infrastructure/canned/CannedResponseDO.java` |
| 新建 Mapper | `…/infrastructure/canned/CannedResponseGroupMapper.java` |
| 新建 Mapper | `…/infrastructure/canned/CannedResponseMapper.java` |
| 新建 AppService | `…/application/service/CannedResponseAppService.java` |
| 新建 Controller | `…/interfaces/rest/CannedResponseAdminController.java` |
| 新建 Controller | `…/interfaces/rest/CannedResponseController.java` |
| 新建测试 | `…/test/…/application/service/CannedResponseAppServiceTest.java` |
| 新建测试 | `…/test/…/interfaces/rest/CannedResponseControllerTest.java` |

> 下文路径简写根包为 `com/aria/conversation`，物理根为  
> `ai-conversation/conversation-service/src/main/java/com/aria/conversation/`

---

---

### Task 1: SQL Schema + DO 类 + Mapper 接口

**Files:**
- Create: `docs/sql/canned-response-schema.sql`
- Create: `infrastructure/canned/CannedResponseGroupDO.java`
- Create: `infrastructure/canned/CannedResponseDO.java`
- Create: `infrastructure/canned/CannedResponseGroupMapper.java`
- Create: `infrastructure/canned/CannedResponseMapper.java`

**Interfaces:**
- Produces:
  - `CannedResponseGroupDO` — `id`, `name`, `parentId`, `sortOrder`, `createdBy`, `createdAt`, `deleted`
  - `CannedResponseDO` — `id`, `groupId`, `title`, `content`, `scope` (`PUBLIC`/`PRIVATE`), `ownerId`, `useCount`, `sortOrder`, `createdBy`, `createdAt`, `updatedAt`, `deleted`
  - `CannedResponseGroupMapper extends BaseMapper<CannedResponseGroupDO>`
  - `CannedResponseMapper extends BaseMapper<CannedResponseDO>` + `searchByKeyword(q, agentId, groupId, limit)` + `incrementUseCount(id)`

- [ ] **Step 1: 创建 SQL schema 文件**

```sql
-- docs/sql/canned-response-schema.sql
-- schema: cs_conversation

CREATE TABLE cs_conversation.cs_canned_response_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    parent_id   BIGINT       REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE cs_conversation.cs_canned_response_group IS '快捷回复分组';

CREATE TABLE cs_conversation.cs_canned_response (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT       REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL,
    title       VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC',
    owner_id    BIGINT,
    use_count   INT          NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE  cs_conversation.cs_canned_response          IS '快捷回复模板';
COMMENT ON COLUMN cs_conversation.cs_canned_response.scope    IS 'PUBLIC=公共, PRIVATE=个人';
COMMENT ON COLUMN cs_conversation.cs_canned_response.use_count IS '使用次数，用于搜索排序';

-- GIN 全文检索索引（title + content）
CREATE INDEX idx_cr_fts ON cs_conversation.cs_canned_response
    USING GIN (to_tsvector('simple', title || ' ' || content))
    WHERE deleted = FALSE;

CREATE INDEX idx_cr_scope_owner ON cs_conversation.cs_canned_response(scope, owner_id)
    WHERE deleted = FALSE;
```

- [ ] **Step 2: 在数据库执行 SQL**

```bash
psql -U aria -d aria_cs -f docs/sql/canned-response-schema.sql
```
期望输出：`CREATE TABLE` × 2，`CREATE INDEX` × 2，无报错。

- [ ] **Step 3: 创建 `CannedResponseGroupDO`**

```java
// infrastructure/canned/CannedResponseGroupDO.java
package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName(schema = "cs_conversation", value = "cs_canned_response_group")
public class CannedResponseGroupDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long parentId;
    private Integer sortOrder;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private Boolean deleted;
}
```

- [ ] **Step 4: 创建 `CannedResponseDO`**

```java
// infrastructure/canned/CannedResponseDO.java
package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName(schema = "cs_conversation", value = "cs_canned_response")
public class CannedResponseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String title;
    private String content;
    /** PUBLIC / PRIVATE */
    private String scope;
    /** PRIVATE 时的所属坐席 ID */
    private Long ownerId;
    private Integer useCount;
    private Integer sortOrder;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Boolean deleted;
}
```

- [ ] **Step 5: 创建 `CannedResponseGroupMapper`**

```java
// infrastructure/canned/CannedResponseGroupMapper.java
package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface CannedResponseGroupMapper extends BaseMapper<CannedResponseGroupDO> {

    /** 查询所有未删除分组，按 sort_order 升序 */
    default List<CannedResponseGroupDO> selectAllActive() {
        return selectList(Wrappers.lambdaQuery(CannedResponseGroupDO.class)
                .eq(CannedResponseGroupDO::getDeleted, false)
                .orderByAsc(CannedResponseGroupDO::getSortOrder));
    }
}
```

- [ ] **Step 6: 创建 `CannedResponseMapper`**

```java
// infrastructure/canned/CannedResponseMapper.java
package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface CannedResponseMapper extends BaseMapper<CannedResponseDO> {

    /**
     * 全文检索快捷回复（title + content），结合权限过滤（PUBLIC 或本人 PRIVATE）。
     * 按 use_count 倒序，限制返回条数。
     */
    @Select("""
        SELECT * FROM cs_conversation.cs_canned_response
        WHERE deleted = FALSE
          AND to_tsvector('simple', title || ' ' || content) @@ plainto_tsquery('simple', #{q})
          AND (scope = 'PUBLIC' OR (scope = 'PRIVATE' AND owner_id = #{agentId}))
          AND (#{groupId} IS NULL OR group_id = #{groupId})
        ORDER BY use_count DESC
        LIMIT #{limit}
        """)
    List<CannedResponseDO> searchByKeyword(@Param("q") String q,
                                           @Param("agentId") Long agentId,
                                           @Param("groupId") Long groupId,
                                           @Param("limit") int limit);

    /** 原子递增 use_count（无需分布式锁，数据库原子操作保证） */
    @Update("UPDATE cs_conversation.cs_canned_response SET use_count = use_count + 1 WHERE id = #{id}")
    void incrementUseCount(@Param("id") Long id);

    /** 查询指定坐席的私人快捷回复列表 */
    default List<CannedResponseDO> selectPrivateByAgent(Long agentId) {
        return selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getScope, "PRIVATE")
                .eq(CannedResponseDO::getOwnerId, agentId)
                .eq(CannedResponseDO::getDeleted, false)
                .orderByAsc(CannedResponseDO::getSortOrder));
    }
}
```

- [ ] **Step 7: 确认编译通过**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service compile -q
```
期望：`BUILD SUCCESS`，无错误。

- [ ] **Step 8: 追加 schema 到快照**

```bash
cat docs/sql/canned-response-schema.sql >> docs/sql/conversation-service-schema.sql
```

- [ ] **Step 9: Commit**

```bash
git add docs/sql/canned-response-schema.sql docs/sql/conversation-service-schema.sql \
        ai-conversation/conversation-service/src/main/java/com/aria/conversation/infrastructure/canned/
git commit -m "feat(canned): Task1 — SQL schema + DO + Mapper"
```

---

### Task 2: CannedResponseAppService（业务逻辑层）

**Files:**
- Create: `application/service/CannedResponseAppService.java`

**Interfaces:**
- Consumes: `CannedResponseGroupMapper`, `CannedResponseMapper`
- Produces:
  - `listGroups() → List<CannedResponseGroupDO>`
  - `createGroup(name, parentId, sortOrder, createdBy) → CannedResponseGroupDO`
  - `updateGroup(id, name, parentId, sortOrder)`
  - `deleteGroup(id)` — 有子分组或有效模板时抛 `BusinessException`
  - `listPublic(groupId, page, size) → List<CannedResponseDO>`
  - `createPublic(title, content, groupId, sortOrder, createdBy) → CannedResponseDO`
  - `updatePublic(id, title, content, groupId, sortOrder)`
  - `deletePublic(id)` — 软删除
  - `listPrivate(agentId) → List<CannedResponseDO>`
  - `createPrivate(title, content, groupId, agentId) → CannedResponseDO`
  - `updatePrivate(id, title, content, agentId)` — 仅 owner 可改
  - `deletePrivate(id, agentId)` — 仅 owner 可删
  - `search(q, agentId, groupId, limit) → List<CannedResponseDO>`
  - `recordUse(id)` — 异步递增 use_count

- [ ] **Step 1: 编写 AppService 测试（先写失败的测试）**

```java
// test/.../application/service/CannedResponseAppServiceTest.java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupMapper;
import com.aria.conversation.infrastructure.canned.CannedResponseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseAppServiceTest {

    @Mock CannedResponseGroupMapper groupMapper;
    @Mock CannedResponseMapper cannedMapper;
    @InjectMocks CannedResponseAppService service;

    @Test
    void search_withBlankQuery_returnsMostUsed() {
        // given: 空关键词时走 fallback（不调用 searchByKeyword）
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(1L); cr.setTitle("常用语"); cr.setScope("PUBLIC"); cr.setUseCount(100);
        when(cannedMapper.selectList(any())).thenReturn(List.of(cr));
        // when
        List<CannedResponseDO> result = service.search("  ", 99L, null, 10);
        // then
        assertThat(result).hasSize(1);
        verify(cannedMapper, never()).searchByKeyword(any(), any(), any(), anyInt());
    }

    @Test
    void search_withKeyword_delegatesToFullTextSearch() {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(2L); cr.setTitle("退款流程"); cr.setScope("PUBLIC");
        when(cannedMapper.searchByKeyword("退款", 99L, null, 10)).thenReturn(List.of(cr));
        List<CannedResponseDO> result = service.search("退款", 99L, null, 10);
        assertThat(result).hasSize(1);
        verify(cannedMapper).searchByKeyword("退款", 99L, null, 10);
    }

    @Test
    void deleteGroup_withChildren_throwsBusinessException() {
        CannedResponseGroupDO group = new CannedResponseGroupDO();
        group.setId(1L); group.setDeleted(false);
        when(groupMapper.selectById(1L)).thenReturn(group);
        // 子分组存在
        when(groupMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteGroup(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("子分组");
    }

    @Test
    void updatePrivate_byNonOwner_throwsBusinessException() {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(5L); cr.setScope("PRIVATE"); cr.setOwnerId(100L); cr.setDeleted(false);
        when(cannedMapper.selectById(5L)).thenReturn(cr);
        // agentId=999 不是 owner
        assertThatThrownBy(() -> service.updatePrivate(5L, "new title", "new content", 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权限");
    }
}
```

- [ ] **Step 2: 运行测试，确认全部失败**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test \
    -Dtest=CannedResponseAppServiceTest -q 2>&1 | tail -10
```
期望：`FAILED` 或编译错误（类不存在）。

- [ ] **Step 3: 实现 CannedResponseAppService**

```java
// application/service/CannedResponseAppService.java
package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.canned.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CannedResponseAppService {

    private static final int NOT_FOUND = 40400;
    private static final int FORBIDDEN  = 40300;

    private final CannedResponseGroupMapper groupMapper;
    private final CannedResponseMapper cannedMapper;

    // ── 分组 ──────────────────────────────────────────────

    public List<CannedResponseGroupDO> listGroups() {
        return groupMapper.selectAllActive();
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseGroupDO createGroup(String name, Long parentId,
                                              int sortOrder, Long createdBy) {
        CannedResponseGroupDO g = new CannedResponseGroupDO();
        g.setName(name); g.setParentId(parentId);
        g.setSortOrder(sortOrder); g.setCreatedBy(createdBy);
        g.setCreatedAt(OffsetDateTime.now()); g.setDeleted(false);
        groupMapper.insert(g);
        return g;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long id, String name, Long parentId, int sortOrder) {
        CannedResponseGroupDO g = requireGroup(id);
        g.setName(name); g.setParentId(parentId); g.setSortOrder(sortOrder);
        groupMapper.updateById(g);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long id) {
        requireGroup(id);
        long childCount = groupMapper.selectCount(Wrappers.lambdaQuery(CannedResponseGroupDO.class)
                .eq(CannedResponseGroupDO::getParentId, id)
                .eq(CannedResponseGroupDO::getDeleted, false));
        if (childCount > 0) {
            throw new BusinessException(40001, "该分组下存在子分组，请先删除子分组");
        }
        long crCount = cannedMapper.selectCount(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getGroupId, id)
                .eq(CannedResponseDO::getDeleted, false));
        if (crCount > 0) {
            throw new BusinessException(40001, "该分组下存在快捷回复，请先删除或移出");
        }
        groupMapper.update(Wrappers.lambdaUpdate(CannedResponseGroupDO.class)
                .set(CannedResponseGroupDO::getDeleted, true)
                .eq(CannedResponseGroupDO::getId, id));
    }

    // ── 公共快捷回复（管理员） ─────────────────────────────

    public List<CannedResponseDO> listPublic(Long groupId, int page, int size) {
        return cannedMapper.selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getScope, "PUBLIC")
                .eq(CannedResponseDO::getDeleted, false)
                .eq(groupId != null, CannedResponseDO::getGroupId, groupId)
                .orderByAsc(CannedResponseDO::getSortOrder)
                .last("LIMIT " + size + " OFFSET " + (long)(page - 1) * size));
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseDO createPublic(String title, String content,
                                          Long groupId, int sortOrder, Long createdBy) {
        CannedResponseDO cr = buildCr(title, content, groupId, sortOrder, createdBy);
        cr.setScope("PUBLIC");
        cannedMapper.insert(cr);
        return cr;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePublic(Long id, String title, String content,
                              Long groupId, int sortOrder) {
        CannedResponseDO cr = requireCr(id);
        cr.setTitle(title); cr.setContent(content);
        cr.setGroupId(groupId); cr.setSortOrder(sortOrder);
        cr.setUpdatedAt(OffsetDateTime.now());
        cannedMapper.updateById(cr);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePublic(Long id) {
        requireCr(id);
        softDelete(id);
    }

    // ── 私人快捷回复（坐席自己） ──────────────────────────

    public List<CannedResponseDO> listPrivate(Long agentId) {
        return cannedMapper.selectPrivateByAgent(agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseDO createPrivate(String title, String content,
                                           Long groupId, Long agentId) {
        CannedResponseDO cr = buildCr(title, content, groupId, 0, agentId);
        cr.setScope("PRIVATE"); cr.setOwnerId(agentId);
        cannedMapper.insert(cr);
        return cr;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePrivate(Long id, String title, String content, Long agentId) {
        CannedResponseDO cr = requireCr(id);
        requireOwner(cr, agentId);
        cr.setTitle(title); cr.setContent(content);
        cr.setUpdatedAt(OffsetDateTime.now());
        cannedMapper.updateById(cr);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePrivate(Long id, Long agentId) {
        CannedResponseDO cr = requireCr(id);
        requireOwner(cr, agentId);
        softDelete(id);
    }

    // ── 搜索 ──────────────────────────────────────────────

    /**
     * 关键词搜索：空关键词时返回 use_count 最高的前 limit 条，
     * 非空时走 pg 全文检索。
     */
    public List<CannedResponseDO> search(String q, Long agentId, Long groupId, int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 30);
        if (q == null || q.isBlank()) {
            return cannedMapper.selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                    .eq(CannedResponseDO::getDeleted, false)
                    .and(w -> w.eq(CannedResponseDO::getScope, "PUBLIC")
                            .or().and(inner -> inner
                                    .eq(CannedResponseDO::getScope, "PRIVATE")
                                    .eq(CannedResponseDO::getOwnerId, agentId)))
                    .eq(groupId != null, CannedResponseDO::getGroupId, groupId)
                    .orderByDesc(CannedResponseDO::getUseCount)
                    .last("LIMIT " + safeLimit));
        }
        return cannedMapper.searchByKeyword(q.trim(), agentId, groupId, safeLimit);
    }

    /** 异步递增使用次数，不阻塞调用方 */
    @Async
    public void recordUse(Long id) {
        cannedMapper.incrementUseCount(id);
    }

    // ── 私有工具方法 ──────────────────────────────────────

    private CannedResponseGroupDO requireGroup(Long id) {
        CannedResponseGroupDO g = groupMapper.selectById(id);
        if (g == null || Boolean.TRUE.equals(g.getDeleted())) {
            throw new BusinessException(NOT_FOUND, "分组不存在: " + id);
        }
        return g;
    }

    private CannedResponseDO requireCr(Long id) {
        CannedResponseDO cr = cannedMapper.selectById(id);
        if (cr == null || Boolean.TRUE.equals(cr.getDeleted())) {
            throw new BusinessException(NOT_FOUND, "快捷回复不存在: " + id);
        }
        return cr;
    }

    private void requireOwner(CannedResponseDO cr, Long agentId) {
        if (!agentId.equals(cr.getOwnerId())) {
            throw new BusinessException(FORBIDDEN, "无权限操作他人快捷回复");
        }
    }

    private void softDelete(Long id) {
        cannedMapper.update(Wrappers.lambdaUpdate(CannedResponseDO.class)
                .set(CannedResponseDO::getDeleted, true)
                .eq(CannedResponseDO::getId, id));
    }

    private CannedResponseDO buildCr(String title, String content,
                                      Long groupId, int sortOrder, Long createdBy) {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setTitle(title); cr.setContent(content);
        cr.setGroupId(groupId); cr.setSortOrder(sortOrder);
        cr.setCreatedBy(createdBy); cr.setUseCount(0);
        cr.setCreatedAt(OffsetDateTime.now());
        cr.setUpdatedAt(OffsetDateTime.now());
        cr.setDeleted(false);
        return cr;
    }
}
```

- [ ] **Step 4: 运行测试，确认全部通过**

```bash
mvn -pl ai-conversation/conversation-service test \
    -Dtest=CannedResponseAppServiceTest -q 2>&1 | tail -5
```
期望：`Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/CannedResponseAppService.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/application/service/CannedResponseAppServiceTest.java
git commit -m "feat(canned): Task2 — CannedResponseAppService + 单元测试"
```

---

### Task 3: 管理端 Controller（分组 + 公共快捷回复 CRUD）

**Files:**
- Create: `interfaces/rest/CannedResponseAdminController.java`

**Interfaces:**
- Consumes: `CannedResponseAppService`
- Produces:
  - `GET  /api/v1/admin/canned-response-groups` → `R<List<CannedResponseGroupDO>>`
  - `POST /api/v1/admin/canned-response-groups` → `R<CannedResponseGroupDO>`
  - `PUT  /api/v1/admin/canned-response-groups/{id}` → `R<Void>`
  - `DELETE /api/v1/admin/canned-response-groups/{id}` → `R<Void>`
  - `GET  /api/v1/admin/canned-responses` → `R<List<CannedResponseDO>>`
  - `POST /api/v1/admin/canned-responses` → `R<CannedResponseDO>`
  - `PUT  /api/v1/admin/canned-responses/{id}` → `R<Void>`
  - `DELETE /api/v1/admin/canned-responses/{id}` → `R<Void>`

- [ ] **Step 1: 创建 Admin Controller**

```java
// interfaces/rest/CannedResponseAdminController.java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupDO;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 快捷回复管理端接口（管理员）。
 * 分组 CRUD + 公共快捷回复 CRUD。
 * 权限校验依赖 Sa-Token 拦截器，controller 层不重复鉴权。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class CannedResponseAdminController {

    private final CannedResponseAppService service;

    // ── 分组接口 ──────────────────────────────────────────

    @GetMapping("/canned-response-groups")
    public R<List<CannedResponseGroupDO>> listGroups() {
        return R.ok(service.listGroups());
    }

    @PostMapping("/canned-response-groups")
    public R<CannedResponseGroupDO> createGroup(@RequestBody @Valid GroupRequest req) {
        Long createdBy = StpUtil.getLoginIdAsLong();
        return R.ok(service.createGroup(req.getName(), req.getParentId(),
                req.getSortOrder() != null ? req.getSortOrder() : 0, createdBy));
    }

    @PutMapping("/canned-response-groups/{id}")
    public R<Void> updateGroup(@PathVariable Long id,
                               @RequestBody @Valid GroupRequest req) {
        service.updateGroup(id, req.getName(), req.getParentId(),
                req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok();
    }

    @DeleteMapping("/canned-response-groups/{id}")
    public R<Void> deleteGroup(@PathVariable Long id) {
        service.deleteGroup(id);
        return R.ok();
    }

    // ── 公共快捷回复接口 ──────────────────────────────────

    @GetMapping("/canned-responses")
    public R<List<CannedResponseDO>> listPublic(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.listPublic(groupId, page, Math.min(size, 100)));
    }

    @PostMapping("/canned-responses")
    public R<CannedResponseDO> createPublic(@RequestBody @Valid CannedRequest req) {
        Long createdBy = StpUtil.getLoginIdAsLong();
        return R.ok(service.createPublic(req.getTitle(), req.getContent(),
                req.getGroupId(), req.getSortOrder() != null ? req.getSortOrder() : 0,
                createdBy));
    }

    @PutMapping("/canned-responses/{id}")
    public R<Void> updatePublic(@PathVariable Long id,
                                @RequestBody @Valid CannedRequest req) {
        service.updatePublic(id, req.getTitle(), req.getContent(),
                req.getGroupId(), req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok();
    }

    @DeleteMapping("/canned-responses/{id}")
    public R<Void> deletePublic(@PathVariable Long id) {
        service.deletePublic(id);
        return R.ok();
    }

    // ── 请求体 DTO ────────────────────────────────────────

    @Data
    public static class GroupRequest {
        @NotBlank @Size(max = 64) private String name;
        private Long parentId;
        private Integer sortOrder;
    }

    @Data
    public static class CannedRequest {
        @NotBlank @Size(max = 128) private String title;
        @NotBlank private String content;
        private Long groupId;
        private Integer sortOrder;
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
mvn -pl ai-conversation/conversation-service compile -q
```
期望：`BUILD SUCCESS`。

- [ ] **Step 3: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/CannedResponseAdminController.java
git commit -m "feat(canned): Task3 — Admin Controller (分组+公共快捷回复 CRUD)"
```

---

### Task 4: 坐席端 Controller（搜索 + 私人模板 + 使用记录）

**Files:**
- Create: `interfaces/rest/CannedResponseController.java`

**Interfaces:**
- Consumes: `CannedResponseAppService`
- Produces:
  - `GET  /api/v1/canned-responses/search?q=&group_id=&limit=` → `R<List<SearchVO>>`
  - `GET  /api/v1/canned-responses/mine` → `R<List<CannedResponseDO>>`
  - `POST /api/v1/canned-responses/mine` → `R<CannedResponseDO>`
  - `PUT  /api/v1/canned-responses/mine/{id}` → `R<Void>`
  - `DELETE /api/v1/canned-responses/mine/{id}` → `R<Void>`
  - `POST /api/v1/canned-responses/{id}/use` → `R<Void>`（异步，记录使用次数）

- [ ] **Step 1: 创建坐席端 Controller**

```java
// interfaces/rest/CannedResponseController.java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 快捷回复坐席端接口。
 * 搜索（/ 触发）、私人模板管理、使用次数上报。
 */
@RestController
@RequestMapping("/api/v1/canned-responses")
@RequiredArgsConstructor
public class CannedResponseController {

    private final CannedResponseAppService service;

    /**
     * 搜索快捷回复。
     * q 为空时按 use_count 倒序返回热门结果；非空时走全文检索。
     * 同时返回当前坐席的 PRIVATE 模板（通过 agentId 过滤）。
     */
    @GetMapping("/search")
    public R<List<SearchVO>> search(
            @RequestParam(required = false) String q,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(defaultValue = "10") int limit) {
        Long agentId = StpUtil.getLoginIdAsLong();
        List<CannedResponseDO> results = service.search(q, agentId, groupId,
                Math.min(limit, 30));
        List<SearchVO> vos = results.stream().map(SearchVO::from).toList();
        return R.ok(vos);
    }

    // ── 私人快捷回复 ──────────────────────────────────────

    @GetMapping("/mine")
    public R<List<CannedResponseDO>> listMine() {
        Long agentId = StpUtil.getLoginIdAsLong();
        return R.ok(service.listPrivate(agentId));
    }

    @PostMapping("/mine")
    public R<CannedResponseDO> createMine(@RequestBody @Valid MineRequest req) {
        Long agentId = StpUtil.getLoginIdAsLong();
        return R.ok(service.createPrivate(req.getTitle(), req.getContent(),
                req.getGroupId(), agentId));
    }

    @PutMapping("/mine/{id}")
    public R<Void> updateMine(@PathVariable Long id,
                               @RequestBody @Valid MineRequest req) {
        Long agentId = StpUtil.getLoginIdAsLong();
        service.updatePrivate(id, req.getTitle(), req.getContent(), agentId);
        return R.ok();
    }

    @DeleteMapping("/mine/{id}")
    public R<Void> deleteMine(@PathVariable Long id) {
        Long agentId = StpUtil.getLoginIdAsLong();
        service.deletePrivate(id, agentId);
        return R.ok();
    }

    /**
     * 坐席使用快捷回复时上报，use_count +1（异步，不影响插入速度）。
     */
    @PostMapping("/{id}/use")
    public R<Void> recordUse(@PathVariable Long id) {
        service.recordUse(id);
        return R.ok();
    }

    // ── VO & 请求 DTO ─────────────────────────────────────

    /** 搜索结果 VO，屏蔽无关字段，附加 groupName 供前端显示 */
    public record SearchVO(Long id, String title, String content,
                           String scope, Integer useCount) {
        static SearchVO from(CannedResponseDO cr) {
            return new SearchVO(cr.getId(), cr.getTitle(), cr.getContent(),
                    cr.getScope(), cr.getUseCount());
        }
    }

    @Data
    public static class MineRequest {
        @NotBlank @Size(max = 128) private String title;
        @NotBlank private String content;
        private Long groupId;
    }
}
```

- [ ] **Step 2: 编写 Controller 层测试**

```java
// test/.../interfaces/rest/CannedResponseControllerTest.java
package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseControllerTest {

    @Mock CannedResponseAppService service;
    @InjectMocks CannedResponseController controller;

    @Test
    void search_capsLimitAt30() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            when(service.search(any(), eq(1L), isNull(), eq(30)))
                    .thenReturn(List.of());
            // limit=999 应被截断为 30
            controller.search(null, null, 999);
            verify(service).search(any(), eq(1L), isNull(), eq(30));
        }
    }

    @Test
    void recordUse_delegatesToServiceAsync() {
        try (MockedStatic<StpUtil> ignored = mockStatic(StpUtil.class)) {
            controller.recordUse(42L);
            verify(service).recordUse(42L);
        }
    }

    @Test
    void deleteMine_passesAgentIdToService() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(55L);
            controller.deleteMine(7L);
            verify(service).deletePrivate(7L, 55L);
        }
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -pl ai-conversation/conversation-service test \
    -Dtest=CannedResponseControllerTest -q 2>&1 | tail -5
```
期望：`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/CannedResponseController.java \
        ai-conversation/conversation-service/src/test/java/com/aria/conversation/interfaces/rest/CannedResponseControllerTest.java
git commit -m "feat(canned): Task4 — 坐席端 Controller（搜索+私人模板+使用记录）"
```

---

### Task 5: 集成验证 & 收尾

**Files:**
- Check/Modify: `ConversationApplication.java`（确认 `@EnableAsync`）
- Modify: `docs/superpowers/plans/2026-07-16-canned-response.md`（勾选完成状态）

**Interfaces:**
- Consumes: 前四个 Task 的全部产出

- [ ] **Step 1: 确认 `@EnableAsync` 已开启**

`CannedResponseAppService.recordUse()` 使用了 `@Async`，需要 Spring 异步支持。
检查主启动类：

```bash
grep -r "@EnableAsync" \
  ai-conversation/conversation-service/src/main/java/com/aria/conversation/
```

若无输出，在 `ConversationApplication.java` 顶部添加注解：

```java
// ai-conversation/conversation-service/src/main/java/com/aria/conversation/ConversationApplication.java
// 在已有 @SpringBootApplication 之后追加
@EnableAsync   // 启用 @Async，供 CannedResponseAppService.recordUse() 使用
```

对应 import：
```java
import org.springframework.scheduling.annotation.EnableAsync;
```

若已存在则跳过此 Step。

- [ ] **Step 2: 运行全量单元测试，确保无回归**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -15
```

期望：
```
Tests run: NNN, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

若有失败，查看具体报错逐一修复，不进入下一 Step。

- [ ] **Step 3: 验证 API 可以在本地启动后调用（可选，有本地环境时执行）**

```bash
# 启动服务（需要本地 PG + Redis 运行）
mvn -pl ai-conversation/conversation-service spring-boot:run &
sleep 15

# 管理员登录拿 token（替换为实际 token）
TOKEN="<admin_token>"

# 创建分组
curl -s -X POST http://localhost:8082/api/v1/admin/canned-response-groups \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"name":"通用问候","sortOrder":0}' | jq .

# 创建公共快捷回复
curl -s -X POST http://localhost:8082/api/v1/admin/canned-responses \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"title":"感谢等待","content":"您好 {{visitor_name}}，感谢耐心等待！","sortOrder":0}' | jq .

# 搜索（坐席 token）
curl -s "http://localhost:8082/api/v1/canned-responses/search?q=感谢" \
  -H "Authorization: $TOKEN" | jq .
```

期望：三个请求均返回 `"code": 200`，`data` 不为空。

- [ ] **Step 4: 最终 Commit（如 Step 1 有修改）**

```bash
# 只有 Step 1 有实际改动时才执行
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/ConversationApplication.java
git commit -m "feat(canned): Task5 — 开启 @EnableAsync，完成快捷回复功能集成"
```

若 Step 1 无改动，跳过此 Step。

- [ ] **Step 5: 创建功能分支 PR（可选）**

```bash
# 当前在 docs/fix-ws-cluster-desc，先确认是否需要新开分支
# 建议：cherry-pick 快捷回复的几个 commit 到独立 feature 分支
git checkout -b feature/canned-response main
git cherry-pick <task1-sha> <task2-sha> <task3-sha> <task4-sha> <task5-sha>
gh pr create \
  --title "feat(canned): 坐席快捷回复功能" \
  --body "实现快捷回复分组管理、公共/私人模板 CRUD、全文检索、使用次数统计。详见 docs/design/cs-features-design.md §5"
```
