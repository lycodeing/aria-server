# 知识库管理增强 P1：检索测试 + 失败重试 + 重新摄取 + 详情增强

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知识库管理页面补齐最核心的三类运营能力：检索效果可视化验证、失败文档一键重试、已发布文档重新摄取，以及文档详情面板的 chunk 统计增强。

**Architecture:** 后端在 `KnowledgeDocController` 新增 3 个接口（search/retry/reingest/stats），`DocIngestAppService` 新增 `retry()` 和 `reingest()` 两个方法，`KnowledgeSearchAppService` 对外暴露一个管理用搜索接口。前端在现有知识库页面新增检索测试 Modal 和操作按钮，复用已有的 Drawer 展示统计信息。

**Tech Stack:** Spring Boot 3.3.5、MyBatis-Plus、Vue 3、Ant Design Vue 4.x

---

## 文件结构

### 后端新增/修改

| 文件 | 变更 |
|------|------|
| `KnowledgeDocController.java` | 新增 search/retry/reingest/stats 四个接口 |
| `DocIngestAppService.java` | 新增 retry() 和 reingest() 方法 |
| `KnowledgeSearchAppService.java` | 新增 managementSearch() 管理用搜索（无 AK/SK） |

### 前端新增/修改

| 文件 | 变更 |
|------|------|
| `src/api/knowledge/index.ts` | 新增 searchKnowledgeApi / retryDocApi / reingestDocApi / getDocStatsApi |
| `src/views/customerservice/knowledge/index.vue` | 新增检索测试 Modal、重试/重新摄取按钮、Drawer 统计 header |


---

### Task 1：后端新增检索测试接口 + retry/reingest 接口

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/interfaces/rest/KnowledgeDocController.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/application/service/DocIngestAppService.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/application/service/KnowledgeSearchAppService.java`

- [ ] **Step 1：KnowledgeSearchAppService 新增 managementSearch 方法**

在 `KnowledgeSearchAppService` 末尾追加：

```java
/**
 * 管理后台检索测试入口（不限 topK，返回更多调试信息）。
 * 与 hybridSearch 相同逻辑，但额外返回 source 字段用于前端展示命中来源。
 */
public List<ChunkHit> managementSearch(String query, String kbId, int topK) {
    return hybridSearch(query, kbId, topK);
}
```

- [ ] **Step 2：DocIngestAppService 新增 retry() 和 reingest() 方法**

在 `DocIngestAppService` 末尾追加两个方法（依赖已有的 `publisher` 和 `docRepository`）：

```java
/**
 * 失败文档重试：将 FAILED 状态流转回 DRAFT，重新发布摄取 MQ 消息。
 * 利用状态模式：FAILED.transitionTo(DRAFT) 校验合法性。
 *
 * @param docId 文档 ID
 */
@Transactional(rollbackFor = Exception.class)
public void retry(String docId) {
    KnowledgeDoc doc = docRepository.findById(docId)
        .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
    // 状态模式：仅 FAILED 允许流转到 DRAFT，其他状态抛 5010
    doc.getStatus().transitionTo(DocStatus.DRAFT);
    docRepository.updateStatusBatch(List.of(docId), DocStatus.DRAFT);

    DocIngestEvent event = DocIngestEvent.builder()
        .docId(docId)
        .kbId(doc.getKbId())
        .fileType(doc.getFileType())
        .storagePath(doc.getStoragePath())
        .build();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.publish(event); }
        });
    log.info("文档重试摄取，docId={}", docId);
}

/**
 * 已发布文档重新摄取：不改变状态，直接重新发布 MQ，pipeline 会幂等处理。
 * 适用于解析逻辑升级后需要重新生成 chunk 的场景。
 *
 * @param docId 文档 ID
 */
public void reingest(String docId) {
    KnowledgeDoc doc = docRepository.findById(docId)
        .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
    if (doc.getStatus() != DocStatus.PUBLISHED) {
        throw new BusinessException(ERROR_BAD_REQUEST,
            "只有 PUBLISHED 状态的文档可以重新摄取，当前状态：" + doc.getStatus());
    }
    DocIngestEvent event = DocIngestEvent.builder()
        .docId(docId)
        .kbId(doc.getKbId())
        .fileType(doc.getFileType())
        .storagePath(doc.getStoragePath())
        .build();
    publisher.publish(event);
    log.info("文档重新摄取，docId={}", docId);
}
```

注意：`DocIngestAppService` 已有 `publisher`、`docRepository` 字段注入，`TransactionSynchronizationManager` 已有 import，`DocStatus`、`DocIngestEvent` 已有 import。

- [ ] **Step 3：KnowledgeDocController 新增四个接口**

在 `KnowledgeDocController` 类中，`offline()` 方法之后追加：

```java
/** 注入搜索服务（检索测试接口使用） */
private final KnowledgeSearchAppService searchAppService;

@Operation(summary = "检索测试（管理后台用，返回命中 chunk 列表+分数）")
@PostMapping("/search-test")
public R<List<SearchHitVO>> searchTest(@RequestBody SearchTestRequest req) {
    var hits = searchAppService.managementSearch(req.getQuery(), req.getKbId(), req.getTopK());
    List<SearchHitVO> vos = hits.stream().map(h -> {
        SearchHitVO vo = new SearchHitVO();
        vo.chunkId    = h.getChunkId();
        vo.docId      = h.getDocId();
        vo.content    = h.getContent();
        vo.score      = h.getScore();
        vo.source     = h.getSource() != null ? h.getSource().name() : "VECTOR";
        return vo;
    }).toList();
    return R.ok(vos);
}

@Operation(summary = "失败文档重试（FAILED → DRAFT → 重新摄取）")
@PostMapping("/{docId}/retry")
public R<Void> retry(@PathVariable("docId") String docId) {
    ingestAppService.retry(docId);
    return R.ok();
}

@Operation(summary = "已发布文档重新摄取（不改状态，幂等重跑 pipeline）")
@PostMapping("/{docId}/reingest")
public R<Void> reingest(@PathVariable("docId") String docId) {
    ingestAppService.reingest(docId);
    return R.ok();
}

@Operation(summary = "查询文档 chunk 统计（总数、各类型数量、总 token）")
@GetMapping("/{docId}/stats")
public R<DocStatsVO> stats(@PathVariable("docId") String docId) {
    List<KnowledgeChunk> chunks = chunkRepository.findByDocId(docId);
    DocStatsVO vo = new DocStatsVO();
    vo.totalChunks  = chunks.size();
    vo.totalTokens  = chunks.stream().mapToInt(c -> c.getTokenCount() != null ? c.getTokenCount() : 0).sum();
    vo.textChunks   = (int) chunks.stream().filter(c -> c.getChunkType() == null
        || c.getChunkType().name().equals("TEXT")).count();
    vo.tableChunks  = (int) chunks.stream().filter(c -> c.getChunkType() != null
        && c.getChunkType().name().equals("TABLE")).count();
    vo.imageChunks  = (int) chunks.stream().filter(c -> c.getChunkType() != null
        && c.getChunkType().name().equals("IMAGE_CAPTION")).count();
    return R.ok(vo);
}

@Data public static class SearchTestRequest {
    @NotBlank private String query;
    @NotBlank private String kbId;
    @Min(1) @Max(20) private int topK = 5;
}

@Data public static class SearchHitVO {
    public String chunkId;
    public String docId;
    public String content;
    public double score;
    public String source;
}

@Data public static class DocStatsVO {
    public int totalChunks;
    public int totalTokens;
    public int textChunks;
    public int tableChunks;
    public int imageChunks;
}
```

同时在 Controller 类的 import 区追加：
```java
import com.aidevplatform.knowledge.application.service.KnowledgeSearchAppService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
```

- [ ] **Step 4：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```

预期：BUILD SUCCESS


---

### Task 2：前端 API 层 + 知识库页面改造

**Files:**
- 修改: `vue-vben-admin/apps/web-antd/src/api/knowledge/index.ts`
- 修改: `vue-vben-admin/apps/web-antd/src/views/customerservice/knowledge/index.vue`

- [ ] **Step 1：api/knowledge/index.ts 追加四个新 API 函数和类型**

在文件末尾追加：

```typescript
// -------------------------------------------------------
// P1 增强接口
// -------------------------------------------------------

export interface SearchHit {
  chunkId: string;
  docId: string;
  content: string;
  score: number;
  source: 'FULL_TEXT' | 'RERANK' | 'VECTOR';
}

export interface DocStats {
  totalChunks: number;
  totalTokens: number;
  textChunks: number;
  tableChunks: number;
  imageChunks: number;
}

/** 检索测试（管理后台用） */
export async function searchKnowledgeApi(
  query: string,
  kbId: string,
  topK = 5,
): Promise<SearchHit[]> {
  return requestClient.post('/knowledge-api/api/knowledge/docs/search-test', {
    query,
    kbId,
    topK,
  });
}

/** 失败文档重试摄取 */
export async function retryDocApi(docId: string): Promise<void> {
  return requestClient.post(`/knowledge-api/api/knowledge/docs/${docId}/retry`);
}

/** 已发布文档重新摄取 */
export async function reingestDocApi(docId: string): Promise<void> {
  return requestClient.post(`/knowledge-api/api/knowledge/docs/${docId}/reingest`);
}

/** 查询文档 chunk 统计 */
export async function getDocStatsApi(docId: string): Promise<DocStats> {
  return requestClient.get(`/knowledge-api/api/knowledge/docs/${docId}/stats`);
}
```

- [ ] **Step 2：index.vue — 新增 import**

在 import 区 `from '#/api/knowledge'` 的行中追加新函数和类型：

```typescript
import {
  getDocChunksApi,
  getDocStatsApi,
  listDocsApi,
  offlineDocApi,
  reingestDocApi,
  retryDocApi,
  reviewDocApi,
  searchKnowledgeApi,
  uploadDocApi,
} from '#/api/knowledge';
import type { ChunkDetail, DocStats, SearchHit } from '#/api/knowledge';
```

同时在 ant-design-vue import 中追加 `Badge`、`Divider`、`InputSearch`、`Spin`：

```typescript
import {
  Badge,
  Button,
  Divider,
  Drawer,
  InputSearch,
  message,
  Modal,
  Progress,
  Select,
  SelectOption,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Upload,
} from 'ant-design-vue';
```

- [ ] **Step 3：index.vue — 新增检索测试和统计的响应式状态**

在 `// ===== Chunk 详情抽屉 =====` 区域之后追加：

```typescript
// ===== 检索测试 Modal =====
const searchModalVisible = ref(false);
const searchQuery        = ref('');
const searchKbId         = ref('default');
const searchTopK         = ref(5);
const searchLoading      = ref(false);
const searchResults      = ref<SearchHit[]>([]);

const searchResultColumns = [
  { title: '分数',    dataIndex: 'score',   key: 'score',   width: 70 },
  { title: '来源',    dataIndex: 'source',  key: 'source',  width: 80 },
  { title: '内容',    dataIndex: 'content', key: 'content', ellipsis: true },
];

const sourceColorMap: Record<string, string> = {
  VECTOR:    'blue',
  FULL_TEXT: 'green',
  RERANK:    'purple',
};

async function handleSearchTest() {
  if (!searchQuery.value.trim()) {
    message.warning('请输入查询内容');
    return;
  }
  searchLoading.value = true;
  searchResults.value = [];
  try {
    searchResults.value = await searchKnowledgeApi(
      searchQuery.value, searchKbId.value, searchTopK.value);
  } catch {
    message.error('检索失败');
  } finally {
    searchLoading.value = false;
  }
}

// ===== Drawer 统计 Header =====
const drawerStats = ref<DocStats | null>(null);

async function handleViewChunks(doc: DocListItem) {
  currentDocName.value = doc.fileName;
  drawerVisible.value  = true;
  drawerLoading.value  = true;
  chunkList.value      = [];
  drawerStats.value    = null;
  try {
    const [chunks, stats] = await Promise.all([
      getDocChunksApi(doc.docId),
      getDocStatsApi(doc.docId),
    ]);
    chunkList.value   = chunks;
    drawerStats.value = stats;
  } catch {
    message.error('加载详情失败');
  } finally {
    drawerLoading.value = false;
  }
}
```

注意：这段代码替换掉之前的 `handleViewChunks` 函数定义（只替换函数体，保留函数名）。

- [ ] **Step 4：index.vue — 操作列补充重试/重新摄取按钮**

将 `column.key === 'action'` 的模板块替换为：

```html
<template v-if="column.key === 'action'">
  <Space>
    <Button size="small" @click="handleViewChunks(record)">详 情</Button>
    <Button
      v-if="record.status === 'DRAFT' || record.status === 'REVIEW'"
      size="small" type="primary" ghost
      @click="handleApprove(record)"
    >审核通过</Button>
    <Button
      v-if="record.status === 'PUBLISHED'"
      size="small"
      @click="async () => { await reingestDocApi(record.docId); message.success('已触发重新摄取'); loadDocs(); }"
    >重新摄取</Button>
    <Button
      v-if="record.status === 'FAILED'"
      size="small" type="primary"
      @click="async () => { await retryDocApi(record.docId); message.success('重试已触发'); loadDocs(); }"
    >重 试</Button>
    <Button
      v-if="record.status === 'PUBLISHED'"
      size="small" danger
      @click="handleOffline(record)"
    >下 线</Button>
  </Space>
</template>
```

- [ ] **Step 5：index.vue — 页面顶部 extra 区加「检索测试」按钮**

将 `<template #extra>` 区块改为：

```html
<template #extra>
  <Space>
    <Button @click="searchModalVisible = true">🔍 检索测试</Button>
    <Button type="primary" @click="uploadVisible = true">上传文档</Button>
  </Space>
</template>
```

- [ ] **Step 6：index.vue — Drawer header 补充统计卡片**

在 `<Drawer>` 组件内、`<Table>` 之前插入统计卡片：

```html
<!-- chunk 统计卡片 -->
<div v-if="drawerStats" style="display:flex; gap:12px; margin-bottom:16px; flex-wrap:wrap;">
  <div style="padding:12px 20px; background:#f5f5f5; border-radius:6px; text-align:center;">
    <div style="font-size:22px; font-weight:600; color:#1677ff">{{ drawerStats.totalChunks }}</div>
    <div style="font-size:12px; color:#999">总 Chunk 数</div>
  </div>
  <div style="padding:12px 20px; background:#f5f5f5; border-radius:6px; text-align:center;">
    <div style="font-size:22px; font-weight:600; color:#52c41a">{{ drawerStats.totalTokens }}</div>
    <div style="font-size:12px; color:#999">总 Token 数</div>
  </div>
  <div style="padding:12px 20px; background:#f5f5f5; border-radius:6px; text-align:center;">
    <div style="font-size:16px; font-weight:500">
      <Tag color="blue">正文 {{ drawerStats.textChunks }}</Tag>
      <Tag color="green">表格 {{ drawerStats.tableChunks }}</Tag>
      <Tag color="purple">图注 {{ drawerStats.imageChunks }}</Tag>
    </div>
    <div style="font-size:12px; color:#999; margin-top:4px">类型分布</div>
  </div>
</div>
```

- [ ] **Step 7：index.vue — 添加检索测试 Modal**

在 Chunk Drawer 之后追加：

```html
<!-- 检索测试 Modal -->
<Modal
  v-model:open="searchModalVisible"
  title="🔍 检索测试"
  :footer="null"
  :width="860"
  :body-style="{ padding: '16px' }"
>
  <div style="display:flex; gap:8px; margin-bottom:16px; align-items:center;">
    <Select v-model:value="searchKbId" style="width:140px;">
      <SelectOption value="default">默认知识库</SelectOption>
      <SelectOption value="faq">FAQ</SelectOption>
      <SelectOption value="ticket">历史工单</SelectOption>
    </Select>
    <input
      v-model="searchQuery"
      placeholder="输入查询内容，回车搜索..."
      style="flex:1; padding:6px 12px; border:1px solid #d9d9d9; border-radius:6px; font-size:14px;"
      @keyup.enter="handleSearchTest"
    />
    <Select v-model:value="searchTopK" style="width:80px;">
      <SelectOption :value="3">Top 3</SelectOption>
      <SelectOption :value="5">Top 5</SelectOption>
      <SelectOption :value="10">Top 10</SelectOption>
    </Select>
    <Button type="primary" :loading="searchLoading" @click="handleSearchTest">搜 索</Button>
  </div>

  <Spin :spinning="searchLoading">
    <div v-if="searchResults.length === 0 && !searchLoading"
         style="text-align:center; color:#999; padding:40px 0;">
      输入查询词后点击搜索，查看 RAG 召回结果
    </div>
    <div v-for="(hit, idx) in searchResults" :key="hit.chunkId"
         style="border:1px solid #f0f0f0; border-radius:6px; padding:12px; margin-bottom:8px;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
        <div style="display:flex; align-items:center; gap:8px;">
          <span style="font-weight:600; color:#999; font-size:12px;">#{{ idx + 1 }}</span>
          <Tag :color="sourceColorMap[hit.source] ?? 'default'" style="font-size:11px;">
            {{ hit.source }}
          </Tag>
        </div>
        <span style="font-size:13px; color:#1677ff; font-weight:500;">
          分数: {{ hit.score.toFixed(4) }}
        </span>
      </div>
      <p style="margin:0; font-size:13px; line-height:1.7; color:#333; white-space:pre-wrap; word-break:break-all;">
        {{ hit.content }}
      </p>
    </div>
  </Spin>
</Modal>
```

- [ ] **Step 8：启动验证**

重启后端服务（需要新代码生效）：
```bash
pkill -f "KnowledgeApplication"
sleep 2
cd /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend
DB_USERNAME=aidev DB_PASSWORD=aidev123 \
nohup /Users/lycodeing/apache-maven-3.9.12/bin/mvn spring-boot:run \
  -pl ai-knowledge/knowledge-service > /tmp/knowledge-service.log 2>&1 &
sleep 22
```

浏览器访问 `http://localhost:5670/customerservice/knowledge`，验证：
1. 顶部出现「🔍 检索测试」按钮
2. 点击后弹出搜索框，输入 "financial performance" 后显示召回结果和分数
3. FAILED 文档行出现「重 试」按钮
4. PUBLISHED 文档行出现「重新摄取」按钮
5. 点击「详 情」后 Drawer 顶部显示统计卡片（chunk 数/token 数/类型分布）

<!-- END_P1 -->


