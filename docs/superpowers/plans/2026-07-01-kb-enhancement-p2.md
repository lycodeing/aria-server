# 知识库管理增强 P2：Chunk 精细管理（启用/禁用 + 编辑 + Q&A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对已入库的 Chunk 提供精细化管理能力：单条启用/禁用（不删除，调整 retrieval_weight）、直接编辑 Chunk 文本并重新向量化、手动添加 Q&A 问答对作为特殊 Chunk。

**Architecture:** 后端在 `KnowledgeDocController` 新增三类 chunk 操作接口（toggle/update/qa），操作逻辑在 `KnowledgeChunkAppService`（新建）中实现，复用 `EmbeddingService` 完成编辑后的重新向量化。前端在现有 Chunk 详情 Drawer 中为每行添加操作按钮，以及顶部「添加 Q&A」入口。

**Tech Stack:** Spring Boot 3.3.5、MyBatis-Plus、Vue 3、Ant Design Vue 4.x

---

## 文件结构

### 后端新增/修改

| 文件 | 变更 |
|------|------|
| `KnowledgeChunkAppService.java`（新建） | Chunk 启用/禁用、编辑、添加 Q&A 的用例编排 |
| `KnowledgeChunkController.java`（新建） | 暴露 chunk 操作 REST 接口 |
| `KnowledgeChunkRepository.java` | 新增 updateWeight / updateContent / save 方法 |
| `KnowledgeChunkRepositoryImpl.java` | 实现上述方法 |

### 前端新增/修改

| 文件 | 变更 |
|------|------|
| `src/api/knowledge/chunk.ts`（新建） | chunk 操作 API 函数 |
| `src/views/customerservice/knowledge/index.vue` | Drawer 内每行加操作列，顶部加 Q&A 入口 |

---

### Task 1：后端 Repository 新增方法

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/domain/repository/KnowledgeChunkRepository.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/persistence/repository/KnowledgeChunkRepositoryImpl.java`

- [ ] **Step 1：KnowledgeChunkRepository 接口新增 3 个方法**

在 `findByDocId` 方法之后追加：

```java
/** 更新 chunk 的检索权重（0.0=禁用，1.0=启用） */
void updateWeight(String chunkId, java.math.BigDecimal weight);

/** 更新 chunk 内容（同步 token 数，向量由调用方负责重新 embed） */
void updateContent(String chunkId, String content, Integer tokenCount);

/** 保存单个新 chunk（Q&A 手动添加场景） */
void save(KnowledgeChunk chunk);
```

- [ ] **Step 2：KnowledgeChunkRepositoryImpl 实现 3 个方法**

在 `findByDocId` 实现之后追加：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void updateWeight(String chunkId, java.math.BigDecimal weight) {
    chunkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeChunkEntity>()
        .eq(KnowledgeChunkEntity::getId, chunkId)
        .set(KnowledgeChunkEntity::getRetrievalWeight, weight));
    log.info("Chunk 权重已更新，chunkId={}，weight={}", chunkId, weight);
}

@Override
@Transactional(rollbackFor = Exception.class)
public void updateContent(String chunkId, String content, Integer tokenCount) {
    chunkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeChunkEntity>()
        .eq(KnowledgeChunkEntity::getId, chunkId)
        .set(KnowledgeChunkEntity::getContent, content)
        .set(KnowledgeChunkEntity::getTokenCount, tokenCount));
    log.info("Chunk 内容已更新，chunkId={}", chunkId);
}

@Override
@Transactional(rollbackFor = Exception.class)
public void save(KnowledgeChunk chunk) {
    chunkMapper.insertBatch(List.of(assembler.toEntity(chunk)));
    log.info("新 Chunk 已保存，chunkId={}", chunk.getId());
}
```

- [ ] **Step 3：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```

预期：BUILD SUCCESS

---

### Task 2：新建 KnowledgeChunkAppService + KnowledgeChunkController

**Files:**
- 新建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/application/service/KnowledgeChunkAppService.java`
- 新建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/interfaces/rest/KnowledgeChunkController.java`

- [ ] **Step 1：创建 KnowledgeChunkAppService.java**

```java
package com.aidevplatform.knowledge.application.service;

import com.aidevplatform.common.core.exception.BusinessException;
import com.aidevplatform.common.core.util.IdGenerator;
import com.aidevplatform.common.core.util.TokenUtils;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;
import com.aidevplatform.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aidevplatform.knowledge.infrastructure.embedding.EmbeddingService;
import com.aidevplatform.knowledge.infrastructure.parser.ChunkType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Chunk 精细管理应用服务。
 * 职责：启用/禁用、内容编辑（含重新向量化）、手动添加 Q&A 对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkAppService {

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;

    /** 禁用 chunk（将 retrieval_weight 设为 0，不从向量库删除） */
    public void disable(String chunkId) {
        assertChunkExists(chunkId);
        chunkRepository.updateWeight(chunkId, BigDecimal.ZERO);
    }

    /** 启用 chunk（恢复 retrieval_weight 为 1.0） */
    public void enable(String chunkId) {
        assertChunkExists(chunkId);
        chunkRepository.updateWeight(chunkId, BigDecimal.ONE);
    }

    /**
     * 编辑 chunk 内容并重新向量化。
     * 更新文本 → 重新 embed → 更新 DB（content + vector + tokenCount）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateContent(String chunkId, String newContent) {
        if (newContent == null || newContent.isBlank()) {
            throw new BusinessException(400, "Chunk 内容不能为空");
        }
        KnowledgeChunk chunk = assertChunkExists(chunkId);
        chunk.setContent(newContent);
        chunk.setTokenCount(TokenUtils.estimate(newContent));
        // 重新向量化
        embeddingService.embed(List.of(chunk));
        // 更新 DB
        chunkRepository.updateContent(chunkId, newContent, chunk.getTokenCount());
        // 同步更新向量（通过 save 覆盖写入）
        chunkRepository.updateWeight(chunkId, chunk.getRetrievalWeight() != null
            ? chunk.getRetrievalWeight() : BigDecimal.ONE);
        log.info("Chunk 内容已更新并重新向量化，chunkId={}", chunkId);
    }

    /**
     * 手动添加 Q&A Chunk。
     * Q&A 以「Q：xxx\nA：xxx」格式存入 content，chunkType 标记为 TEXT。
     *
     * @param docId   归属文档 ID（可为虚拟 QA 文档）
     * @param kbId    归属知识库 ID
     * @param question 问题
     * @param answer   答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void addQA(String docId, String kbId, String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            throw new BusinessException(400, "问题和答案不能为空");
        }
        String content = "Q：" + question.trim() + "\nA：" + answer.trim();
        KnowledgeChunk chunk = KnowledgeChunk.builder()
            .id(String.valueOf(IdGenerator.nextId()))
            .docId(docId)
            .kbId(kbId)
            .docStatus(DocStatus.PUBLISHED.name())
            .content(content)
            .tokenCount(TokenUtils.estimate(content))
            .retrievalWeight(BigDecimal.ONE)
            .chunkType(ChunkType.TEXT)
            .feedbackDownvotes(0)
            .build();
        // 向量化
        embeddingService.embed(List.of(chunk));
        chunkRepository.save(chunk);
        log.info("Q&A Chunk 已添加，docId={}，chunkId={}", docId, chunk.getId());
    }

    private KnowledgeChunk assertChunkExists(String chunkId) {
        KnowledgeChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) {
            throw new BusinessException(4004, "Chunk 不存在：" + chunkId);
        }
        return chunk;
    }
}
```

- [ ] **Step 2：创建 KnowledgeChunkController.java**

```java
package com.aidevplatform.knowledge.interfaces.rest;

import com.aidevplatform.common.web.response.R;
import com.aidevplatform.knowledge.application.service.KnowledgeChunkAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Chunk 精细管理接口（启用/禁用/编辑/Q&A）。
 */
@RestController
@RequestMapping("/api/knowledge/chunks")
@RequiredArgsConstructor
@Tag(name = "Chunk 管理", description = "Chunk 启用/禁用/编辑/Q&A 添加")
public class KnowledgeChunkController {

    private final KnowledgeChunkAppService chunkAppService;

    @Operation(summary = "禁用 Chunk（retrieval_weight=0，不物理删除）")
    @PostMapping("/{chunkId}/disable")
    public R<Void> disable(@PathVariable String chunkId) {
        chunkAppService.disable(chunkId);
        return R.ok();
    }

    @Operation(summary = "启用 Chunk（retrieval_weight=1.0）")
    @PostMapping("/{chunkId}/enable")
    public R<Void> enable(@PathVariable String chunkId) {
        chunkAppService.enable(chunkId);
        return R.ok();
    }

    @Operation(summary = "编辑 Chunk 内容并重新向量化")
    @PutMapping("/{chunkId}/content")
    public R<Void> updateContent(
            @PathVariable String chunkId,
            @RequestBody UpdateContentRequest req) {
        chunkAppService.updateContent(chunkId, req.getContent());
        return R.ok();
    }

    @Operation(summary = "手动添加 Q&A Chunk")
    @PostMapping("/qa")
    public R<Void> addQA(@RequestBody AddQARequest req) {
        chunkAppService.addQA(req.getDocId(), req.getKbId(),
            req.getQuestion(), req.getAnswer());
        return R.ok();
    }

    @Data
    public static class UpdateContentRequest {
        @NotBlank private String content;
    }

    @Data
    public static class AddQARequest {
        @NotBlank private String docId;
        @NotBlank private String kbId;
        @NotBlank private String question;
        @NotBlank private String answer;
    }
}
```

- [ ] **Step 3：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```

预期：BUILD SUCCESS

---

### Task 3：前端 API + Drawer 操作列改造

**Files:**
- 新建: `vue-vben-admin/apps/web-antd/src/api/knowledge/chunk.ts`
- 修改: `vue-vben-admin/apps/web-antd/src/views/customerservice/knowledge/index.vue`

- [ ] **Step 1：创建 src/api/knowledge/chunk.ts**

```typescript
import { rawRequestClient } from '#/api/request';

const requestClient = rawRequestClient;

/** 禁用 Chunk */
export async function disableChunkApi(chunkId: string): Promise<void> {
  return requestClient.post(`/knowledge-api/api/knowledge/chunks/${chunkId}/disable`);
}

/** 启用 Chunk */
export async function enableChunkApi(chunkId: string): Promise<void> {
  return requestClient.post(`/knowledge-api/api/knowledge/chunks/${chunkId}/enable`);
}

/** 编辑 Chunk 内容 */
export async function updateChunkContentApi(
  chunkId: string,
  content: string,
): Promise<void> {
  return requestClient.put(
    `/knowledge-api/api/knowledge/chunks/${chunkId}/content`,
    { content },
  );
}

/** 添加 Q&A Chunk */
export async function addQAChunkApi(
  docId: string,
  kbId: string,
  question: string,
  answer: string,
): Promise<void> {
  return requestClient.post('/knowledge-api/api/knowledge/chunks/qa', {
    docId,
    kbId,
    question,
    answer,
  });
}
```

- [ ] **Step 2：index.vue — 新增 chunk 操作状态和函数**

在 `drawerStats` 之后追加：

```typescript
// ===== Chunk 编辑 =====
const editingChunkId  = ref<null | string>(null);
const editingContent  = ref('');
const editLoading     = ref(false);

// ===== Q&A 添加 =====
const qaModalVisible  = ref(false);
const qaDocId         = ref('');
const qaKbId          = ref('');
const qaQuestion      = ref('');
const qaAnswer        = ref('');
const qaLoading       = ref(false);

async function handleToggleChunk(chunk: ChunkDetail) {
  const isEnabled = chunk.retrievalWeight !== 0;
  try {
    if (isEnabled) {
      await disableChunkApi(chunk.chunkId);
      message.success('Chunk 已禁用');
    } else {
      await enableChunkApi(chunk.chunkId);
      message.success('Chunk 已启用');
    }
    // 重新加载当前文档的 chunk 列表
    const doc = docs.value.find((d) => chunkList.value.some((c) => c.chunkId === chunk.chunkId));
    if (doc) await handleViewChunks(doc);
  } catch {
    message.error('操作失败');
  }
}

async function handleSaveEdit() {
  if (!editingChunkId.value || !editingContent.value.trim()) return;
  editLoading.value = true;
  try {
    await updateChunkContentApi(editingChunkId.value, editingContent.value);
    message.success('Chunk 已更新并重新向量化');
    editingChunkId.value = null;
    editingContent.value = '';
  } catch {
    message.error('更新失败');
  } finally {
    editLoading.value = false;
  }
}

async function handleAddQA() {
  if (!qaQuestion.value.trim() || !qaAnswer.value.trim()) {
    message.warning('问题和答案不能为空');
    return;
  }
  qaLoading.value = true;
  try {
    await addQAChunkApi(qaDocId.value, qaKbId.value,
      qaQuestion.value, qaAnswer.value);
    message.success('Q&A 已添加');
    qaModalVisible.value = false;
    qaQuestion.value = '';
    qaAnswer.value = '';
  } catch {
    message.error('添加失败');
  } finally {
    qaLoading.value = false;
  }
}
```

同时在 import 区追加：

```typescript
import {
  addQAChunkApi,
  disableChunkApi,
  enableChunkApi,
  updateChunkContentApi,
} from '#/api/knowledge/chunk';
```

- [ ] **Step 3：index.vue — Drawer 内 chunk 表格加操作列**

在 `chunkColumns` 定义末尾追加操作列：

```typescript
const chunkColumns = [
  { title: '页码',   dataIndex: 'pageNum',      key: 'pageNum',      width: 60 },
  { title: '类型',   dataIndex: 'chunkType',    key: 'chunkType',    width: 70 },
  { title: '章节',   dataIndex: 'sectionTitle', key: 'sectionTitle', width: 140, ellipsis: true },
  { title: 'Token', dataIndex: 'tokenCount',   key: 'tokenCount',   width: 65 },
  { title: '内容',  dataIndex: 'content',       key: 'content',      ellipsis: true },
  { title: '操作',  key: 'chunkAction',                              width: 120 },
];
```

在 Drawer 内 Table 的 `#bodyCell` 模板末尾追加：

```html
<template v-if="column.key === 'chunkAction'">
  <Space>
    <Button size="small"
      @click="editingChunkId = record.chunkId; editingContent = record.content">
      编辑
    </Button>
    <Button size="small"
      :type="record.retrievalWeight === 0 ? 'primary' : 'default'"
      @click="handleToggleChunk(record)">
      {{ record.retrievalWeight === 0 ? '启用' : '禁用' }}
    </Button>
  </Space>
</template>
```

- [ ] **Step 4：index.vue — Drawer 顶部加「添加 Q&A」按钮 + 编辑内联区**

在 Drawer 组件的统计卡片之后追加按钮和内联编辑区：

```html
<div style="display:flex; justify-content:flex-end; margin-bottom:12px;">
  <Button type="dashed"
    @click="qaDocId = chunkList[0]?.chunkId ? 'current-doc' : 'default';
            qaKbId = 'default'; qaModalVisible = true">
    + 添加 Q&A
  </Button>
</div>

<!-- 内联编辑区 -->
<div v-if="editingChunkId"
     style="background:#fffbe6; border:1px solid #ffe58f; padding:12px; border-radius:6px; margin-bottom:12px;">
  <div style="font-size:13px; color:#ad8b00; margin-bottom:8px;">✏️ 正在编辑 Chunk</div>
  <textarea
    v-model="editingContent"
    rows="6"
    style="width:100%; padding:8px; border:1px solid #d9d9d9; border-radius:4px; font-size:13px; resize:vertical;"
  />
  <div style="display:flex; gap:8px; margin-top:8px; justify-content:flex-end;">
    <Button @click="editingChunkId = null">取消</Button>
    <Button type="primary" :loading="editLoading" @click="handleSaveEdit">保存并重新向量化</Button>
  </div>
</div>
```

- [ ] **Step 5：index.vue — 添加 Q&A Modal**

在检索测试 Modal 之后追加：

```html
<!-- 添加 Q&A Modal -->
<Modal
  v-model:open="qaModalVisible"
  title="添加 Q&A 问答对"
  :confirm-loading="qaLoading"
  @ok="handleAddQA"
  :width="560"
>
  <div style="display:flex; flex-direction:column; gap:12px; padding:8px 0;">
    <div>
      <div style="font-size:13px; margin-bottom:4px; font-weight:500;">问题 Q</div>
      <textarea
        v-model="qaQuestion"
        rows="3"
        placeholder="输入用户可能问的问题..."
        style="width:100%; padding:8px; border:1px solid #d9d9d9; border-radius:4px; font-size:13px; resize:none;"
      />
    </div>
    <div>
      <div style="font-size:13px; margin-bottom:4px; font-weight:500;">答案 A</div>
      <textarea
        v-model="qaAnswer"
        rows="5"
        placeholder="输入对应的标准答案..."
        style="width:100%; padding:8px; border:1px solid #d9d9d9; border-radius:4px; font-size:13px; resize:none;"
      />
    </div>
  </div>
</Modal>
```

- [ ] **Step 6：ChunkDetail 类型补充 retrievalWeight 字段**

在 `src/api/knowledge/index.ts` 的 `ChunkDetail` 接口中追加：

```typescript
export interface ChunkDetail {
  chunkId: string;
  pageNum: number | null;
  sectionTitle: string | null;
  chunkType: 'IMAGE_CAPTION' | 'TABLE' | 'TEXT';
  tokenCount: number;
  content: string;
  retrievalWeight: number;   // 新增：0=禁用，1=启用
}
```

同时后端 `KnowledgeDocController.ChunkVO` 中追加 `retrievalWeight` 字段：

```java
@Data public static class ChunkVO {
    public String  chunkId;
    public Integer pageNum;
    public String  sectionTitle;
    public String  chunkType;
    public Integer tokenCount;
    public String  content;
    public Double  retrievalWeight;  // 新增
}
```

并在 `chunks()` 方法映射处补充：

```java
vo.retrievalWeight = c.getRetrievalWeight() != null
    ? c.getRetrievalWeight().doubleValue() : 1.0;
```

- [ ] **Step 7：编译 + 启动验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q 2>&1 | tail -5
```

浏览器访问知识库页面，点击「详情」，验证：
1. Chunk 表格右侧出现「编辑」和「禁用/启用」按钮
2. 点击「编辑」弹出内联文本框，保存后显示「重新向量化」成功提示
3. 点击「禁用」后该 Chunk 的检索测试中不再出现
4. 点击「+ 添加 Q&A」后弹出 Modal，填写后入库
