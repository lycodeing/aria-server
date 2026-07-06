# DIT 框架管理 API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 DIT 框架的领域、意图、槽位、工具增删改查提供完整 REST API，供前端管理后台调用。

**Architecture:** 在 conversation-service 新增 `DitManageController`，通过 MyBatis-Plus 直接操作已有 Entity/Mapper，遵循项目 DDD 分层规范（Interface → Application → Infrastructure）。所有接口需 Sa-Token 鉴权，响应统一包装为 `R<T>`。

**Tech Stack:** Spring Boot 3, MyBatis-Plus, Sa-Token, `R<T>` 统一响应, Jakarta Validation

---

## 文件改动总览

| 操作 | 文件 | 说明 |
|---|---|---|
| 新建 | `application/service/DitManageAppService.java` | DIT CRUD 业务编排 |
| 新建 | `interfaces/rest/DitDomainController.java` | 领域 CRUD |
| 新建 | `interfaces/rest/DitIntentController.java` | 意图 + 槽位 + 绑定 CRUD |
| 新建 | `interfaces/rest/DitToolController.java` | 工具注册 CRUD |
| 修改 | `infrastructure/dit/repository/DomainRepository.java` | 新增 save/update/delete |

---

## Task 1: DitManageAppService

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/DitManageAppService.java`

- [ ] **Step 1: 创建 DitManageAppService**

```java
package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.dit.domain.*;
import com.aria.conversation.infrastructure.dit.mapper.*;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DIT 框架管理应用服务。
 * 领域、意图、槽位、工具的 CRUD 编排，同时维护缓存一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DitManageAppService {

    private final DomainMapper domainMapper;
    private final IntentMapper intentMapper;
    private final IntentSlotMapper slotMapper;
    private final ToolMapper toolMapper;
    private final IntentToolMapper intentToolMapper;
    private final DomainRepository domainRepository; // 用于缓存失效

    // ---- 领域 ----

    public List<DomainDO> listDomains() {
        return domainMapper.selectList(null);
    }

    public DomainDO getDomain(Long id) {
        DomainDO domain = domainMapper.selectById(id);
        if (domain == null) throw new com.aria.common.core.exception.BusinessException(4004, "领域不存在: " + id);
        return domain;
    }

    public DomainDO createDomain(DomainDO domain) {
        domainMapper.insert(domain);
        return domain;
    }

    public void updateDomain(DomainDO domain) {
        if (domainMapper.updateById(domain) == 0)
            throw new com.aria.common.core.exception.BusinessException(4004, "领域不存在: " + domain.getId());
        domainRepository.evict(getDomain(domain.getId()).getCode());
    }

    public void deleteDomain(Long id) {
        DomainDO d = getDomain(id);
        domainMapper.deleteById(id);
        domainRepository.evict(d.getCode());
    }

    // ---- 意图 ----

    public List<IntentDO> listIntents(Long domainId) {
        return intentMapper.selectList(
                new LambdaQueryWrapper<IntentDO>().eq(IntentDO::getDomainId, domainId)
                        .orderByAsc(IntentDO::getSortOrder));
    }

    public IntentDO createIntent(IntentDO intent) {
        intentMapper.insert(intent);
        evictDomainByIntentId(intent.getId());
        return intent;
    }

    public void updateIntent(IntentDO intent) {
        intentMapper.updateById(intent);
        evictDomainByIntentId(intent.getId());
    }

    public void deleteIntent(Long intentId) {
        intentMapper.deleteById(intentId);
        // 级联删除槽位和绑定
        slotMapper.delete(new LambdaQueryWrapper<IntentSlotDO>().eq(IntentSlotDO::getIntentId, intentId));
        intentToolMapper.delete(new LambdaQueryWrapper<IntentToolDO>().eq(IntentToolDO::getIntentId, intentId));
    }

    // ---- 槽位 ----

    public List<IntentSlotDO> listSlots(Long intentId) {
        return slotMapper.findByIntentId(intentId);
    }

    public IntentSlotDO createSlot(IntentSlotDO slot) {
        slotMapper.insert(slot);
        evictDomainByIntentId(slot.getIntentId());
        return slot;
    }

    public void updateSlot(IntentSlotDO slot) {
        slotMapper.updateById(slot);
        evictDomainByIntentId(slot.getIntentId());
    }

    public void deleteSlot(Long slotId) {
        IntentSlotDO s = slotMapper.selectById(slotId);
        if (s != null) { slotMapper.deleteById(slotId); evictDomainByIntentId(s.getIntentId()); }
    }

    // ---- 工具 ----

    public List<ToolDO> listTools() {
        return toolMapper.selectList(null);
    }

    public ToolDO createTool(ToolDO tool) {
        toolMapper.insert(tool);
        return tool;
    }

    public void updateTool(ToolDO tool) {
        toolMapper.updateById(tool);
    }

    public void deleteTool(Long toolId) {
        toolMapper.deleteById(toolId);
    }

    // ---- 意图-工具绑定 ----

    public List<IntentToolDO> listBindings(Long intentId) {
        return intentToolMapper.findByIntentId(intentId);
    }

    public IntentToolDO createBinding(IntentToolDO binding) {
        intentToolMapper.insert(binding);
        evictDomainByIntentId(binding.getIntentId());
        return binding;
    }

    public void deleteBinding(Long bindingId) {
        IntentToolDO b = intentToolMapper.selectById(bindingId);
        if (b != null) { intentToolMapper.deleteById(bindingId); evictDomainByIntentId(b.getIntentId()); }
    }

    // ---- 内部工具 ----

    private void evictDomainByIntentId(Long intentId) {
        IntentDO intent = intentMapper.selectById(intentId);
        if (intent == null) return;
        DomainDO domain = domainMapper.selectById(intent.getDomainId());
        if (domain != null) domainRepository.evict(domain.getCode());
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
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/application/service/DitManageAppService.java
git commit -m "feat(DIT管理): 新增 DitManageAppService，CRUD编排含缓存失效"
```

---

## Task 2: DitDomainController（领域 CRUD REST API）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitDomainController.java`

- [ ] **Step 1: 创建控制器**

```java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 领域配置管理接口（需登录，仅管理员）。
 * GET    /admin/dit/domains          → 列出所有领域
 * POST   /admin/dit/domains          → 新建领域
 * PUT    /admin/dit/domains/{id}     → 更新领域
 * DELETE /admin/dit/domains/{id}     → 删除领域
 */
@RestController
@RequestMapping("/admin/dit/domains")
@RequiredArgsConstructor
public class DitDomainController {

    private final DitManageAppService manageService;

    @GetMapping
    public R<List<DomainDO>> list() {
        return R.ok(manageService.listDomains());
    }

    @PostMapping
    public R<DomainDO> create(@RequestBody @Valid DomainRequest req) {
        DomainDO domain = new DomainDO();
        domain.setCode(req.getCode());
        domain.setName(req.getName());
        domain.setDescription(req.getDescription());
        domain.setSystemPromptAddon(req.getSystemPromptAddon());
        domain.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        return R.ok(manageService.createDomain(domain));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid DomainRequest req) {
        DomainDO domain = new DomainDO();
        domain.setId(id);
        domain.setCode(req.getCode());
        domain.setName(req.getName());
        domain.setDescription(req.getDescription());
        domain.setSystemPromptAddon(req.getSystemPromptAddon());
        domain.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        manageService.updateDomain(domain);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manageService.deleteDomain(id);
        return R.ok();
    }

    @Data
    public static class DomainRequest {
        @NotBlank(message = "code 不能为空")
        @Size(max = 64)
        private String code;

        @NotBlank(message = "name 不能为空")
        @Size(max = 128)
        private String name;

        private String description;
        private String systemPromptAddon;
        private Boolean enabled;
    }
}
```

- [ ] **Step 2: 编译**

```bash
mvn compile -pl ai-conversation/conversation-service -q
```

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitDomainController.java
git commit -m "feat(DIT管理): 新增领域 CRUD REST API"
```

---

## Task 3: DitIntentController（意图 + 槽位 + 绑定）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitIntentController.java`

- [ ] **Step 1: 创建控制器**

```java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 意图/槽位/绑定管理接口。
 * 意图：  GET/POST /admin/dit/intents?domainId=  PUT/DELETE /admin/dit/intents/{id}
 * 槽位：  GET/POST /admin/dit/slots?intentId=    PUT/DELETE /admin/dit/slots/{id}
 * 绑定：  GET/POST /admin/dit/bindings?intentId= DELETE /admin/dit/bindings/{id}
 */
@RestController
@RequestMapping("/admin/dit")
@RequiredArgsConstructor
public class DitIntentController {

    private final DitManageAppService manageService;

    // ---- 意图 ----

    @GetMapping("/intents")
    public R<List<IntentDO>> listIntents(@RequestParam Long domainId) {
        return R.ok(manageService.listIntents(domainId));
    }

    @PostMapping("/intents")
    public R<IntentDO> createIntent(@RequestBody @Valid IntentRequest req) {
        IntentDO intent = new IntentDO();
        intent.setDomainId(req.getDomainId());
        intent.setCode(req.getCode());
        intent.setName(req.getName());
        intent.setDescription(req.getDescription());
        intent.setExampleQueries(req.getExampleQueries());
        intent.setAutoTransfer(req.getAutoTransfer() != null && req.getAutoTransfer());
        intent.setSkipRag(req.getSkipRag() != null && req.getSkipRag());
        intent.setFallbackReply(req.getFallbackReply());
        intent.setEnabled(true);
        intent.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok(manageService.createIntent(intent));
    }

    @PutMapping("/intents/{id}")
    public R<Void> updateIntent(@PathVariable Long id, @RequestBody @Valid IntentRequest req) {
        IntentDO intent = new IntentDO();
        intent.setId(id);
        intent.setCode(req.getCode());
        intent.setName(req.getName());
        intent.setDescription(req.getDescription());
        intent.setExampleQueries(req.getExampleQueries());
        intent.setAutoTransfer(req.getAutoTransfer() != null && req.getAutoTransfer());
        intent.setSkipRag(req.getSkipRag() != null && req.getSkipRag());
        intent.setFallbackReply(req.getFallbackReply());
        intent.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        manageService.updateIntent(intent);
        return R.ok();
    }

    @DeleteMapping("/intents/{id}")
    public R<Void> deleteIntent(@PathVariable Long id) {
        manageService.deleteIntent(id);
        return R.ok();
    }

    // ---- 槽位 ----

    @GetMapping("/slots")
    public R<List<IntentSlotDO>> listSlots(@RequestParam Long intentId) {
        return R.ok(manageService.listSlots(intentId));
    }

    @PostMapping("/slots")
    public R<IntentSlotDO> createSlot(@RequestBody @Valid SlotRequest req) {
        IntentSlotDO slot = new IntentSlotDO();
        slot.setIntentId(req.getIntentId());
        slot.setSlotName(req.getSlotName());
        slot.setSlotType(req.getSlotType() != null ? req.getSlotType() : "string");
        slot.setDescription(req.getDescription());
        slot.setRequired(req.getRequired() != null && req.getRequired());
        slot.setResolveStrategy(req.getResolveStrategy());
        slot.setSessionKey(req.getSessionKey());
        slot.setDiscoverToolCode(req.getDiscoverToolCode());
        slot.setDiscoverFixedParams(req.getDiscoverFixedParams());
        slot.setAskUserPrompt(req.getAskUserPrompt());
        slot.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok(manageService.createSlot(slot));
    }

    @PutMapping("/slots/{id}")
    public R<Void> updateSlot(@PathVariable Long id, @RequestBody @Valid SlotRequest req) {
        IntentSlotDO slot = new IntentSlotDO();
        slot.setId(id);
        slot.setSlotName(req.getSlotName());
        slot.setSlotType(req.getSlotType() != null ? req.getSlotType() : "string");
        slot.setDescription(req.getDescription());
        slot.setRequired(req.getRequired() != null && req.getRequired());
        slot.setResolveStrategy(req.getResolveStrategy());
        slot.setSessionKey(req.getSessionKey());
        slot.setDiscoverToolCode(req.getDiscoverToolCode());
        slot.setDiscoverFixedParams(req.getDiscoverFixedParams());
        slot.setAskUserPrompt(req.getAskUserPrompt());
        slot.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        manageService.updateSlot(slot);
        return R.ok();
    }

    @DeleteMapping("/slots/{id}")
    public R<Void> deleteSlot(@PathVariable Long id) {
        manageService.deleteSlot(id);
        return R.ok();
    }

    // ---- 意图-工具绑定 ----

    @GetMapping("/bindings")
    public R<List<IntentToolDO>> listBindings(@RequestParam Long intentId) {
        return R.ok(manageService.listBindings(intentId));
    }

    @PostMapping("/bindings")
    public R<IntentToolDO> createBinding(@RequestBody @Valid BindingRequest req) {
        IntentToolDO binding = new IntentToolDO();
        binding.setIntentId(req.getIntentId());
        binding.setToolId(req.getToolId());
        binding.setExecutionMode(req.getExecutionMode() != null ? req.getExecutionMode() : "OPTIONAL");
        binding.setExecutionOrder(req.getExecutionOrder() != null ? req.getExecutionOrder() : 0);
        binding.setParamMappings(req.getParamMappings() != null ? req.getParamMappings() : "{}");
        return R.ok(manageService.createBinding(binding));
    }

    @DeleteMapping("/bindings/{id}")
    public R<Void> deleteBinding(@PathVariable Long id) {
        manageService.deleteBinding(id);
        return R.ok();
    }

    // ---- Request 类 ----

    @Data public static class IntentRequest {
        @NotNull Long domainId;
        @NotBlank String code;
        @NotBlank String name;
        @NotBlank String description;
        String exampleQueries;
        Boolean autoTransfer;
        Boolean skipRag;
        String fallbackReply;
        Integer sortOrder;
    }

    @Data public static class SlotRequest {
        @NotNull Long intentId;
        @NotBlank String slotName;
        String slotType;
        @NotBlank String description;
        Boolean required;
        String resolveStrategy;
        String sessionKey;
        String discoverToolCode;
        String discoverFixedParams;
        String askUserPrompt;
        Integer sortOrder;
    }

    @Data public static class BindingRequest {
        @NotNull Long intentId;
        @NotNull Long toolId;
        String executionMode;
        Integer executionOrder;
        String paramMappings;
    }
}
```

- [ ] **Step 2: 编译**

```bash
mvn compile -pl ai-conversation/conversation-service -q
```

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitIntentController.java
git commit -m "feat(DIT管理): 新增意图/槽位/绑定 CRUD REST API"
```

---

## Task 4: DitToolController（工具注册）

**Files:**
- Create: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitToolController.java`

- [ ] **Step 1: 创建控制器**

```java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 工具注册管理接口。
 * GET    /admin/dit/tools        → 列出所有工具
 * POST   /admin/dit/tools        → 注册新工具
 * PUT    /admin/dit/tools/{id}   → 更新工具
 * DELETE /admin/dit/tools/{id}   → 删除工具
 */
@RestController
@RequestMapping("/admin/dit/tools")
@RequiredArgsConstructor
public class DitToolController {

    private final DitManageAppService manageService;

    @GetMapping
    public R<List<ToolDO>> list() {
        return R.ok(manageService.listTools());
    }

    @PostMapping
    public R<ToolDO> create(@RequestBody @Valid ToolRequest req) {
        return R.ok(manageService.createTool(toToolDO(null, req)));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid ToolRequest req) {
        ToolDO tool = toToolDO(id, req);
        manageService.updateTool(tool);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manageService.deleteTool(id);
        return R.ok();
    }

    private ToolDO toToolDO(Long id, ToolRequest req) {
        ToolDO tool = new ToolDO();
        tool.setId(id);
        tool.setCode(req.getCode());
        tool.setName(req.getName());
        tool.setDescription(req.getDescription());
        tool.setToolType(req.getToolType() != null ? req.getToolType() : "HTTP");
        tool.setHttpMethod(req.getHttpMethod());
        tool.setUrlTemplate(req.getUrlTemplate());
        tool.setHeadersTemplate(req.getHeadersTemplate());
        tool.setBodyTemplate(req.getBodyTemplate());
        tool.setParamSchema(req.getParamSchema() != null ? req.getParamSchema() : "{}");
        tool.setResponseJsonpath(req.getResponseJsonpath());
        tool.setAuthType(req.getAuthType() != null ? req.getAuthType() : "NONE");
        tool.setAuthConfig(req.getAuthConfig());
        tool.setTimeoutMs(req.getTimeoutMs() != null ? req.getTimeoutMs() : 5000);
        tool.setIsDiscoverTool(req.getIsDiscoverTool() != null && req.getIsDiscoverTool());
        tool.setEnabled(true);
        return tool;
    }

    @Data
    public static class ToolRequest {
        @NotBlank String code;
        @NotBlank String name;
        @NotBlank String description;
        String toolType;
        String httpMethod;
        String urlTemplate;
        String headersTemplate;
        String bodyTemplate;
        String paramSchema;
        String responseJsonpath;
        String authType;
        String authConfig;
        Integer timeoutMs;
        Boolean isDiscoverTool;
    }
}
```

- [ ] **Step 2: 编译 + 全量测试**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice-backend
mvn test -pl ai-conversation/conversation-service -q
```

预期：`BUILD SUCCESS`，74 个测试通过

- [ ] **Step 3: 提交**

```bash
git add ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitToolController.java
git commit -m "feat(DIT管理): 新增工具注册 CRUD REST API"
```

---

## Task 5: DB 菜单迁移 SQL

**Files:**
- Create: `docs/sql/migration-003-dit-menus.sql`

- [ ] **Step 1: 创建 SQL 文件**

```sql
-- migration-003-dit-menus.sql
-- DIT 框架管理菜单及权限迁移

-- DIT 配置目录（智能客服子目录，id=104）
INSERT INTO cs_auth.sys_menu
    (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order)
VALUES
(104, 100, 'DIRECTORY', 'DIT配置', 'CustomerServiceDIT',
    '/customerservice/dit', NULL, 'lucide:settings-2', 40),
(105, 104, 'MENU', '领域与意图', 'CustomerServiceDITDomains',
    '/customerservice/dit/domains', 'customerservice/dit/domains/index',
    'lucide:layers', 10),
(106, 104, 'MENU', '工具注册中心', 'CustomerServiceDITTools',
    '/customerservice/dit/tools', 'customerservice/dit/tools/index',
    'lucide:wrench', 20),
-- 按钮权限
(130, 105, 'BUTTON', '新建领域', 'dit:domain:create', NULL, NULL, NULL, 0),
(131, 105, 'BUTTON', '编辑领域', 'dit:domain:update', NULL, NULL, NULL, 0),
(132, 105, 'BUTTON', '删除领域', 'dit:domain:delete', NULL, NULL, NULL, 0),
(133, 105, 'BUTTON', '管理意图', 'dit:intent:manage', NULL, NULL, NULL, 0),
(134, 105, 'BUTTON', '管理槽位', 'dit:slot:manage', NULL, NULL, NULL, 0),
(135, 106, 'BUTTON', '注册工具', 'dit:tool:create', NULL, NULL, NULL, 0),
(136, 106, 'BUTTON', '编辑工具', 'dit:tool:update', NULL, NULL, NULL, 0),
(137, 106, 'BUTTON', '删除工具', 'dit:tool:delete', NULL, NULL, NULL, 0),
(138, 106, 'BUTTON', '测试工具', 'dit:tool:test', NULL, NULL, NULL, 0);

-- super_admin（id=10）：全部菜单
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(10,104),(10,105),(10,106),
(10,130),(10,131),(10,132),(10,133),(10,134),(10,135),(10,136),(10,137),(10,138);

-- kf_manager（id=11）：可管理意图/槽位/测试，不可新建/删除领域和工具
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id) VALUES
(11,104),(11,105),(11,106),(11,133),(11,134),(11,138);
```

- [ ] **Step 2: 在本地 PostgreSQL 执行**

```bash
psql -U postgres -d aria_db -f docs/sql/migration-003-dit-menus.sql
```

- [ ] **Step 3: 提交**

```bash
git add docs/sql/migration-003-dit-menus.sql
git commit -m "feat(DIT管理): 新增 DIT 菜单及角色权限 SQL 迁移"
```

---

## 验收标准

- [ ] `GET /admin/dit/domains` 返回领域列表
- [ ] `POST /admin/dit/domains` 创建领域，`GET /menus/me` 能看到新菜单（需重新登录）
- [ ] 全量测试 74 个通过
- [ ] 菜单迁移后 super_admin 登录可看到「DIT配置」目录
