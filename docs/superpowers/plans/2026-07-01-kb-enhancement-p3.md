# 知识库管理增强 P3：批量操作 + 知识库维度统计

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持文档列表多选批量下线/删除，以及知识库维度的汇总统计（每个知识库的文档数、Chunk 数、Token 总量）展示在统计卡片区域。

**Architecture:** 后端新增批量下线接口和知识库统计接口。前端列表改为支持行选择（rowSelection），统计卡片由文档数改为知识库汇总数据。

**Tech Stack:** Spring Boot 3.3.5、MyBatis-Plus、Vue 3、Ant Design Vue 4.x

---

## 文件结构

### 后端新增/修改

| 文件 | 变更 |
|------|------|
| `KnowledgeDocController.java` | 新增 batch-offline 接口和 kb-stats 接口 |
| `DocIngestAppService.java` | 新增 batchOffline() 方法 |
| `KnowledgeChunkRepository.java` | 新增 countStatsByKbId() 方法 |
| `KnowledgeChunkRepositoryImpl.java` | 实现 countStatsByKbId() |
| `KnowledgeChunkMapper.java` | 新增 selectStatsByKbId() 查询方法 |
| `KnowledgeChunkMapper.xml` | 新增汇总 SQL |

### 前端新增/修改

| 文件 | 变更 |
|------|------|
| `src/api/knowledge/index.ts` | 新增 batchOfflineApi / getKbStatsApi |
| `src/views/customerservice/knowledge/index.vue` | 表格 rowSelection + 批量操作栏 + 统计卡片改造 |

---

### Task 1：后端 — 批量下线 + 知识库统计接口

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/application/service/DocIngestAppService.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/interfaces/rest/KnowledgeDocController.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/domain/repository/KnowledgeChunkRepository.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/persistence/repository/KnowledgeChunkRepositoryImpl.java`

- [ ] **Step 1：DocIngestAppService 新增 batchOffline 方法**

在 `offline()` 方法之后追加：

```java
/**
 * 批量下线文档（每条文档单独走状态模式校验）。
 * 非 PUBLISHED 状态的文档静默跳过（不抛异常），避免批量操作中单条失败中断整批。
 *
 * @param docIds 文档 ID 列表，最多 50 条
 */
public void batchOffline(List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) return;
    if (docIds.size() > 50) {
        throw new BusinessException(ERROR_BAD_REQUEST, "批量操作最多支持 50 条");
    }
    List<String> publishedIds = docIds.stream()
        .map(id -> docRepository.findById(id).orElse(null))
        .filter(doc -> doc != null && doc.getStatus() == DocStatus.PUBLISHED)
        .map(KnowledgeDoc::getId)
        .toList();

    if (!publishedIds.isEmpty()) {
        docRepository.updateStatusBatch(publishedIds, DocStatus.DEPRECATED);
        log.info("批量下线完成，数量={}", publishedIds.size());
    }
}
```

- [ ] **Step 2：KnowledgeChunkRepository 新增统计方法**

在接口末尾追加：

```java
/**
 * 按知识库 ID 汇总 Chunk 统计（仅统计 PUBLISHED 状态）。
 * 返回 Map 结构：{ chunkCount, tokenSum }
 */
java.util.Map<String, Long> countStatsByKbId(String kbId);
```

- [ ] **Step 3：KnowledgeChunkRepositoryImpl 实现统计方法**

追加实现：

```java
@Override
public java.util.Map<String, Long> countStatsByKbId(String kbId) {
    com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeChunkEntity> wrapper =
        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
    wrapper.eq("kb_id", kbId)
           .eq("doc_status", "PUBLISHED")
           .gt("retrieval_weight", 0);
    long chunkCount = chunkMapper.selectCount(wrapper);
    // token 汇总通过 Mapper XML 自定义 SQL
    Long tokenSum = chunkMapper.selectTokenSumByKbId(kbId);
    return java.util.Map.of(
        "chunkCount", chunkCount,
        "tokenSum",   tokenSum != null ? tokenSum : 0L
    );
}
```

- [ ] **Step 4：KnowledgeChunkMapper 新增 selectTokenSumByKbId**

在 `KnowledgeChunkMapper.java` 接口中追加：

```java
/** 汇总指定知识库所有已发布 chunk 的 token 总量 */
Long selectTokenSumByKbId(@Param("kbId") String kbId);
```

在 `KnowledgeChunkMapper.xml` 末尾追加（在 `</mapper>` 之前）：

```xml
<!-- 汇总指定知识库所有已发布 chunk 的 token 总量 -->
<select id="selectTokenSumByKbId" resultType="java.lang.Long">
    SELECT COALESCE(SUM(token_count), 0)
    FROM knowledge_chunk
    WHERE kb_id      = #{kbId}
      AND doc_status = 'PUBLISHED'
      AND retrieval_weight > 0
</select>
```

- [ ] **Step 5：KnowledgeDocController 新增两个接口**

在 `stats()` 方法之后追加：

```java
@Operation(summary = "批量下线文档（最多 50 条，非 PUBLISHED 状态自动跳过）")
@PostMapping("/batch-offline")
public R<Void> batchOffline(@RequestBody BatchOfflineRequest req) {
    ingestAppService.batchOffline(req.getDocIds());
    return R.ok();
}

@Operation(summary = "查询指定知识库的 chunk/token 汇总统计")
@GetMapping("/kb-stats")
public R<java.util.Map<String, Object>> kbStats(
        @RequestParam("kbId") String kbId) {
    // 知识库文档数
    var docQuery = new com.aidevplatform.knowledge.application.query.DocPageQuery();
    docQuery.setKbId(kbId);
    docQuery.setStatus(DocStatus.PUBLISHED);
    docQuery.setPage(0);
    docQuery.setSize(1);
    long docCount = ingestAppService.listDocs(docQuery).total();
    // chunk 和 token 统计
    var chunkStats = chunkRepository.countStatsByKbId(kbId);
    return R.ok(java.util.Map.of(
        "kbId",       kbId,
        "docCount",   docCount,
        "chunkCount", chunkStats.get("chunkCount"),
        "tokenSum",   chunkStats.get("tokenSum")
    ));
}

@Data
public static class BatchOfflineRequest {
    private java.util.List<String> docIds;
}
```

- [ ] **Step 6：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```

预期：BUILD SUCCESS

---

### Task 2：前端 — 批量操作 + 统计卡片改造

**Files:**
- 修改: `vue-vben-admin/apps/web-antd/src/api/knowledge/index.ts`
- 修改: `vue-vben-admin/apps/web-antd/src/views/customerservice/knowledge/index.vue`

- [ ] **Step 1：api/knowledge/index.ts 追加两个 API**

在文件末尾追加：

```typescript
export interface KbStats {
  kbId: string;
  docCount: number;
  chunkCount: number;
  tokenSum: number;
}

/** 批量下线文档 */
export async function batchOfflineApi(docIds: string[]): Promise<void> {
  return requestClient.post('/knowledge-api/api/knowledge/docs/batch-offline', { docIds });
}

/** 查询知识库汇总统计 */
export async function getKbStatsApi(kbId: string): Promise<KbStats> {
  return requestClient.get('/knowledge-api/api/knowledge/docs/kb-stats', {
    params: { kbId },
  });
}
```

- [ ] **Step 2：index.vue — import 追加新 API 和 Checkbox**

在 import 区追加：

```typescript
import { batchOfflineApi, getKbStatsApi } from '#/api/knowledge';
import type { KbStats } from '#/api/knowledge';
import { Checkbox } from 'ant-design-vue';
```

- [ ] **Step 3：index.vue — 新增多选和批量操作状态**

在 `filterStatus` 定义之后追加：

```typescript
// ===== 多选批量操作 =====
const selectedRowKeys = ref<string[]>([]);
const batchLoading    = ref(false);

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: string[]) => { selectedRowKeys.value = keys; },
  getCheckboxProps: (record: DocListItem) => ({
    disabled: record.status === 'DEPRECATED',
  }),
}));

async function handleBatchOffline() {
  if (selectedRowKeys.value.length === 0) {
    message.warning('请先勾选要下线的文档');
    return;
  }
  batchLoading.value = true;
  try {
    await batchOfflineApi(selectedRowKeys.value);
    message.success(`已批量下线 ${selectedRowKeys.value.length} 条文档`);
    selectedRowKeys.value = [];
    loadDocs();
  } catch {
    message.error('批量下线失败');
  } finally {
    batchLoading.value = false;
  }
}

// ===== 知识库统计 =====
const kbStatsMap = ref<Record<string, KbStats>>({});

async function loadKbStats() {
  const kbIds = ['default', 'faq', 'ticket'];
  const results = await Promise.allSettled(kbIds.map((id) => getKbStatsApi(id)));
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') kbStatsMap.value[kbIds[i]!] = r.value;
  });
}
```

同时在 `onMounted` 中追加 `loadKbStats()`：

```typescript
onMounted(() => {
  loadDocs();
  loadKbStats();
});
```

- [ ] **Step 4：index.vue — 统计卡片区改为知识库汇总视图**

将原有四个统计卡片的 `<div>` 区块替换为：

```html
<!-- 知识库统计卡片 -->
<div style="display:grid; grid-template-columns: repeat(3,1fr); gap:16px; margin-bottom:16px;">
  <div
    v-for="kb in [
      { id:'default', label:'默认知识库', icon:'📚' },
      { id:'faq',     label:'FAQ 知识库', icon:'❓' },
      { id:'ticket',  label:'历史工单库', icon:'🎫' },
    ]"
    :key="kb.id"
    style="padding:20px; background:#fff; border-radius:8px; box-shadow:0 1px 4px #0001;"
  >
    <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px;">
      <span style="font-size:20px;">{{ kb.icon }}</span>
      <span style="font-weight:600; font-size:15px;">{{ kb.label }}</span>
    </div>
    <div v-if="kbStatsMap[kb.id]" style="display:grid; grid-template-columns:repeat(3,1fr); gap:8px;">
      <div style="text-align:center;">
        <div style="font-size:20px; font-weight:600; color:#1677ff">
          {{ kbStatsMap[kb.id]?.docCount ?? 0 }}
        </div>
        <div style="font-size:11px; color:#999;">文档数</div>
      </div>
      <div style="text-align:center;">
        <div style="font-size:20px; font-weight:600; color:#52c41a">
          {{ kbStatsMap[kb.id]?.chunkCount ?? 0 }}
        </div>
        <div style="font-size:11px; color:#999;">Chunk 数</div>
      </div>
      <div style="text-align:center;">
        <div style="font-size:20px; font-weight:600; color:#fa8c16">
          {{ ((kbStatsMap[kb.id]?.tokenSum ?? 0) / 1000).toFixed(1) }}K
        </div>
        <div style="font-size:11px; color:#999;">Token 总量</div>
      </div>
    </div>
    <div v-else style="text-align:center; color:#ccc; padding:8px 0; font-size:13px;">加载中...</div>
  </div>
</div>
```

- [ ] **Step 5：index.vue — 表格上方加批量操作栏**

在文档表格 `<div>` 的 `<Table>` 之前插入：

```html
<!-- 批量操作栏 -->
<div
  v-if="selectedRowKeys.length > 0"
  style="background:#e6f4ff; border:1px solid #91caff; border-radius:6px;
         padding:8px 16px; margin-bottom:12px; display:flex;
         align-items:center; justify-content:space-between;"
>
  <span style="color:#1677ff; font-size:13px;">
    已选择 <strong>{{ selectedRowKeys.length }}</strong> 条文档
  </span>
  <Space>
    <Button size="small" @click="selectedRowKeys = []">取消选择</Button>
    <Button size="small" danger :loading="batchLoading" @click="handleBatchOffline">
      批量下线
    </Button>
  </Space>
</div>
```

- [ ] **Step 6：index.vue — Table 组件绑定 rowSelection**

在 `<Table>` 标签上加 `:row-selection="rowSelection"`：

```html
<Table
  :columns="columns"
  :data-source="docs"
  row-key="docId"
  :loading="loading"
  :row-selection="rowSelection"
  :pagination="{
    current: pageNum + 1,
    pageSize,
    total: totalDocs,
    onChange: handlePageChange,
    showTotal: (total: number) => `共 ${total} 条`,
  }"
>
```

- [ ] **Step 7：重启后端 + 浏览器验证**

```bash
pkill -f "KnowledgeApplication"; sleep 2
cd /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend
DB_USERNAME=aidev DB_PASSWORD=aidev123 \
nohup /Users/lycodeing/apache-maven-3.9.12/bin/mvn spring-boot:run \
  -pl ai-knowledge/knowledge-service > /tmp/knowledge-service.log 2>&1 &
sleep 22
```

浏览器访问 `http://localhost:5670/customerservice/knowledge`，验证：
1. 统计卡片区显示三个知识库的文档数/Chunk 数/Token 总量
2. 文档列表左侧出现勾选框，勾选后顶部出现批量操作蓝色提示栏
3. 点击「批量下线」后勾选的 PUBLISHED 文档变为 DEPRECATED
4. 已是 DEPRECATED 的文档勾选框为禁用状态
