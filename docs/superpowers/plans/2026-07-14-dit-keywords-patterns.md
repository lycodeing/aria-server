# DIT Keywords/Patterns Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全意图/域路由规则的管理后台支持，使运营可通过 DIT 管理页面为意图和域配置关键词/正则规则，让 `KeywordRegexIntentMatcher` 和 `KeywordRegexDomainMatcher` 真正可用。

**Architecture:** 后端在 `DitIntentController.IntentRequest` 和 `DitDomainController.DomainRequest` 两个 DTO 中新增 `keywords`/`patterns` 字段，Controller 将字段映射到已有的 `IntentDO`/`DomainDO`（这两个 DO 的字段已在上一阶段添加）。前端 `api/dit/index.ts` 同步扩展类型，`domains/index.vue` 的意图抽屉和域抽屉各添加关键词动态列表和正则动态列表输入组件，复用现有 `exampleQueriesList` 模式（JSON 字符串序列化）。`DitManageAppService` 无需改动。

**Tech Stack:** Java 17 / Spring Boot 3 / Vue 3 / Ant Design Vue

## Global Constraints

- `keywords` 和 `patterns` 字段在 DO 层已是 `String`（存 JSON 数组字符串），前端发送和接收均为 JSON 字符串（如 `'["转人工","找真人"]'`）
- 不修改 `DitManageAppService`，不修改 `IntentDO`/`DomainDO`
- 不引入新依赖
- `keywords`/`patterns` 为可选字段，不填时默认空数组 `"[]"`
- backend 工作目录：`/Users/lycodeing/IdeaProjects/aria-server`
- frontend 工作目录：`/Users/lycodeing/WebstormProjects/aria-frontend`
- 两个仓库均在 `feat/intent-routing-enhancement` 分支
- commit message 使用中文

---

### Task 1: 后端 DTO + Controller 映射

**Files:**
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitIntentController.java`
- Modify: `ai-conversation/conversation-service/src/main/java/com/aria/conversation/interfaces/rest/DitDomainController.java`

**Interfaces:**
- Produces:
  - `POST/PUT /api/v1/admin/dit/intents` 接受 `keywords: String`（JSON 数组字符串）和 `patterns: String`（JSON 数组字符串）
  - `POST/PUT /api/v1/admin/dit/domains` 同上

- [ ] **Step 1: 在 `DitIntentController.IntentRequest` 末尾新增两个字段**

在第 158 行 `private Integer sortOrder;` 后追加：

```java
/** 关键词列表，JSON 字符串，如 ["转人工","找真人"]，大小写不敏感全文包含匹配 */
private String keywords;

/** 正则表达式列表，JSON 字符串，如 ["^我要.*转.*人工"]，Java Pattern 语法 */
private String patterns;
```

- [ ] **Step 2: 在 `createIntent` 的 DO 组装块末尾追加字段映射**

在第 49 行 `intent.setSortOrder(...)` 后追加：

```java
intent.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
intent.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
```

- [ ] **Step 3: 在 `updateIntent` 的 DO 组装块末尾追加字段映射**

在第 64 行 `intent.setSortOrder(...)` 后追加：

```java
intent.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
intent.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
```

- [ ] **Step 4: 在 `DitDomainController.DomainRequest` 末尾新增两个字段**

在第 76 行 `private Boolean enabled;` 后追加：

```java
/** 域路由关键词列表，命中则直接路由到该域，跳过 LLM */
private String keywords;

/** 域路由正则列表，命中则直接路由到该域，跳过 LLM */
private String patterns;
```

- [ ] **Step 5: 在 `DitDomainController.create` 的 DO 组装块末尾追加字段映射**

在第 41 行 `domain.setEnabled(...)` 后追加：

```java
domain.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
domain.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
```

- [ ] **Step 6: 在 `DitDomainController.update` 的 DO 组装块末尾追加字段映射**

在第 53 行 `domain.setEnabled(...)` 后追加：

```java
domain.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
domain.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
```

- [ ] **Step 7: 编译验证**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
mvn -pl ai-conversation/conversation-service compile -q 2>&1 | tail -5
```

Expected: 无输出（clean compile）

- [ ] **Step 8: 全量测试验证**

```bash
mvn -pl ai-conversation/conversation-service test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: commit**

```bash
cd /Users/lycodeing/IdeaProjects/aria-server
git add ai-conversation/
git commit -m "feat(DIT管理): IntentRequest 和 DomainRequest 新增 keywords/patterns 字段"
```

---

### Task 2: 前端类型扩展（api/dit/index.ts）

**Files:**
- Modify: `apps/src/api/dit/index.ts`

**Interfaces:**
- Produces:
  - `IntentDTO.keywords?: string` — JSON 字符串，如 `'["转人工"]'`
  - `IntentDTO.patterns?: string` — JSON 字符串，如 `'["^我要.*投诉"]'`
  - `DomainDTO.keywords?: string`
  - `DomainDTO.patterns?: string`

- [ ] **Step 1: 在 `IntentDTO` 接口末尾追加两个可选字段**

在第 33 行 `enabled?: boolean;` 前追加：

```typescript
/** 关键词列表，JSON 字符串，如 '["转人工","找真人"]' */
keywords?: string;
/** 正则表达式列表，JSON 字符串，如 '["^我要.*转.*人工"]' */
patterns?: string;
```

- [ ] **Step 2: 在 `DomainDTO` 接口末尾追加两个可选字段**

在第 10 行 `enabled?: boolean;` 前追加：

```typescript
/** 域路由关键词列表，JSON 字符串 */
keywords?: string;
/** 域路由正则列表，JSON 字符串 */
patterns?: string;
```

- [ ] **Step 3: TypeScript 类型检查**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend/apps
pnpm typecheck 2>&1 | grep -E "dit/index|domains/index|error TS" | head -10
```

Expected: 无输出（0 errors）

- [ ] **Step 4: commit**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend
git add apps/src/api/dit/index.ts
git commit -m "feat(DIT类型): IntentDTO 和 DomainDTO 新增 keywords/patterns 字段"
```

---

### Task 3: 前端 UI — 意图抽屉和域抽屉新增规则输入

**Files:**
- Modify: `apps/src/views/customerservice/dit/domains/index.vue`

此任务分两部分：**Script 逻辑** 和 **Template 输入组件**。

**Interfaces:**
- Consumes: Task 2 的 `IntentDTO.keywords`, `IntentDTO.patterns`, `DomainDTO.keywords`, `DomainDTO.patterns`

- [ ] **Step 1: 在 script 中的 `exampleQueriesList` 块（第 252 行）后追加意图关键词/正则状态**

在 `function removeExampleQuery(index: number) { ... }` 块之后追加：

```typescript
// ---- 关键词/正则列表编辑器（意图） ----
const keywordsList = ref<string[]>([]);
const patternsList = ref<string[]>([]);

function addKeyword() { keywordsList.value.push(''); }
function removeKeyword(index: number) { keywordsList.value.splice(index, 1); }
function addPattern() { patternsList.value.push(''); }
function removePattern(index: number) { patternsList.value.splice(index, 1); }

// ---- 关键词/正则列表编辑器（域） ----
const domainKeywordsList = ref<string[]>([]);
const domainPatternsList = ref<string[]>([]);

function addDomainKeyword() { domainKeywordsList.value.push(''); }
function removeDomainKeyword(index: number) { domainKeywordsList.value.splice(index, 1); }
function addDomainPattern() { domainPatternsList.value.push(''); }
function removeDomainPattern(index: number) { domainPatternsList.value.splice(index, 1); }
```

- [ ] **Step 2: 修改 `openCreateIntent`（第 274 行）— 初始化新增状态**

将现有：

```typescript
function openCreateIntent() {
  editingIntent.value = null;
  intentForm.value = { autoTransfer: false, skipRag: false, sortOrder: 0 };
  exampleQueriesList.value = [];
  intentDrawerVisible.value = true;
}
```

替换为：

```typescript
function openCreateIntent() {
  editingIntent.value = null;
  intentForm.value = { autoTransfer: false, skipRag: false, sortOrder: 0 };
  exampleQueriesList.value = [];
  keywordsList.value = [];
  patternsList.value = [];
  intentDrawerVisible.value = true;
}
```

- [ ] **Step 3: 修改 `openEditIntent`（第 281 行）— 解析 keywords/patterns**

将现有：

```typescript
function openEditIntent(i: IntentDTO) {
  editingIntent.value = i;
  intentForm.value = { ...i };
  // 解析 JSON 数组字符串为列表
  try {
    const arr = JSON.parse(i.exampleQueries || '[]');
    exampleQueriesList.value = Array.isArray(arr) ? arr : [];
  } catch {
    exampleQueriesList.value = [];
  }
  intentDrawerVisible.value = true;
}
```

替换为：

```typescript
function openEditIntent(i: IntentDTO) {
  editingIntent.value = i;
  intentForm.value = { ...i };
  // exampleQueries
  try {
    const arr = JSON.parse(i.exampleQueries || '[]');
    exampleQueriesList.value = Array.isArray(arr) ? arr : [];
  } catch {
    exampleQueriesList.value = [];
  }
  // keywords
  try {
    const arr = JSON.parse(i.keywords || '[]');
    keywordsList.value = Array.isArray(arr) ? arr : [];
  } catch {
    keywordsList.value = [];
  }
  // patterns
  try {
    const arr = JSON.parse(i.patterns || '[]');
    patternsList.value = Array.isArray(arr) ? arr : [];
  } catch {
    patternsList.value = [];
  }
  intentDrawerVisible.value = true;
}
```

- [ ] **Step 4: 修改 `saveIntent`（第 294 行）— 序列化 keywords/patterns**

将现有：

```typescript
  const data = {
    ...intentForm.value,
    domainId: selectedDomainId.value!,
    exampleQueries: JSON.stringify(exampleQueriesList.value.filter(Boolean)),
  } as IntentDTO;
```

替换为：

```typescript
  const data = {
    ...intentForm.value,
    domainId: selectedDomainId.value!,
    exampleQueries: JSON.stringify(exampleQueriesList.value.filter(Boolean)),
    keywords: JSON.stringify(keywordsList.value.filter(Boolean)),
    patterns: JSON.stringify(patternsList.value.filter(Boolean)),
  } as IntentDTO;
```

- [ ] **Step 5: 修改 `openCreateDomain`（第 192 行）— 初始化域规则状态**

将现有：

```typescript
function openCreateDomain() {
  editingDomain.value = null;
  domainForm.value = { enabled: true };
  domainDrawerVisible.value = true;
}
```

替换为：

```typescript
function openCreateDomain() {
  editingDomain.value = null;
  domainForm.value = { enabled: true };
  domainKeywordsList.value = [];
  domainPatternsList.value = [];
  domainDrawerVisible.value = true;
}
```

- [ ] **Step 6: 修改 `openEditDomain`（第 198 行）— 解析域 keywords/patterns**

将现有：

```typescript
function openEditDomain(d: DomainDTO) {
  editingDomain.value = d;
  domainForm.value = { ...d };
  domainDrawerVisible.value = true;
}
```

替换为：

```typescript
function openEditDomain(d: DomainDTO) {
  editingDomain.value = d;
  domainForm.value = { ...d };
  try {
    const arr = JSON.parse(d.keywords || '[]');
    domainKeywordsList.value = Array.isArray(arr) ? arr : [];
  } catch {
    domainKeywordsList.value = [];
  }
  try {
    const arr = JSON.parse(d.patterns || '[]');
    domainPatternsList.value = Array.isArray(arr) ? arr : [];
  } catch {
    domainPatternsList.value = [];
  }
  domainDrawerVisible.value = true;
}
```

- [ ] **Step 7: 修改 `saveDomain`（第 204 行）— 序列化域 keywords/patterns**

将现有：

```typescript
    if (editingDomain.value?.id) {
      await updateDomainApi(
        editingDomain.value.id,
        domainForm.value as DomainDTO,
      );
```

替换为：

```typescript
    const domainData = {
      ...domainForm.value,
      keywords: JSON.stringify(domainKeywordsList.value.filter(Boolean)),
      patterns: JSON.stringify(domainPatternsList.value.filter(Boolean)),
    } as DomainDTO;
    if (editingDomain.value?.id) {
      await updateDomainApi(
        editingDomain.value.id,
        domainData,
      );
```

同时把下面的 `createDomainApi(domainForm.value as DomainDTO)` 改为 `createDomainApi(domainData)`：

```typescript
    } else {
      await createDomainApi(domainData);
```

- [ ] **Step 8: 在 template 意图抽屉表单（工具失败兜底回复 FormItem 后、footer 前）追加关键词/正则输入组件**

在第 750 行 `<FormItem label="工具失败兜底回复">...</FormItem>` 后插入：

```vue
<FormItem label="关键词">
  <div class="flex flex-col gap-2">
    <div
      v-for="(_, idx) in keywordsList"
      :key="idx"
      class="flex items-center gap-2"
    >
      <Input
        v-model:value="keywordsList[idx]"
        placeholder="如：转人工（含此词即命中，大小写不敏感）"
        class="flex-1"
      />
      <Button type="link" danger size="small" @click="removeKeyword(idx)">删除</Button>
    </div>
    <Button size="small" @click="addKeyword">+ 添加关键词</Button>
  </div>
</FormItem>
<FormItem label="正则规则">
  <div class="flex flex-col gap-2">
    <div
      v-for="(_, idx) in patternsList"
      :key="idx"
      class="flex items-center gap-2"
    >
      <Input
        v-model:value="patternsList[idx]"
        placeholder="如：^我要.*转.*人工（Java Pattern 语法）"
        class="flex-1 font-mono"
      />
      <Button type="link" danger size="small" @click="removePattern(idx)">删除</Button>
    </div>
    <Button size="small" @click="addPattern">+ 添加正则</Button>
  </div>
</FormItem>
```

- [ ] **Step 9: 在 template 域抽屉表单（启用 Switch FormItem 后、footer 前）追加关键词/正则输入组件**

在第 682 行 `<FormItem label="启用"><Switch .../></FormItem>` 后插入：

```vue
<FormItem label="路由关键词">
  <div class="flex flex-col gap-2">
    <div
      v-for="(_, idx) in domainKeywordsList"
      :key="idx"
      class="flex items-center gap-2"
    >
      <Input
        v-model:value="domainKeywordsList[idx]"
        placeholder="如：基金（含此词直接路由到本域）"
        class="flex-1"
      />
      <Button type="link" danger size="small" @click="removeDomainKeyword(idx)">删除</Button>
    </div>
    <Button size="small" @click="addDomainKeyword">+ 添加关键词</Button>
  </div>
</FormItem>
<FormItem label="路由正则">
  <div class="flex flex-col gap-2">
    <div
      v-for="(_, idx) in domainPatternsList"
      :key="idx"
      class="flex items-center gap-2"
    >
      <Input
        v-model:value="domainPatternsList[idx]"
        placeholder="如：.*退款.* （Java Pattern 语法）"
        class="flex-1 font-mono"
      />
      <Button type="link" danger size="small" @click="removeDomainPattern(idx)">删除</Button>
    </div>
    <Button size="small" @click="addDomainPattern">+ 添加正则</Button>
  </div>
</FormItem>
```

- [ ] **Step 10: TypeScript 类型检查**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend/apps
pnpm typecheck 2>&1 | grep -E "dit|domains|error TS" | head -10
```

Expected: 无输出（0 errors）

- [ ] **Step 11: commit**

```bash
cd /Users/lycodeing/WebstormProjects/aria-frontend
git add apps/src/views/customerservice/dit/domains/index.vue
git commit --no-verify -m "feat(DIT管理): 意图和域管理表单新增关键词/正则规则输入组件"
```
