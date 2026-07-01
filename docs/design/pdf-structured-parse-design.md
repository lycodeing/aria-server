# PDF 结构化解析技术实现文档

> 版本：v1.0 | 创建日期：2026-07-01 | 模块：ai-knowledge/knowledge-service

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [整体架构](#2-整体架构)
3. [核心数据结构](#3-核心数据结构)
4. [模块详细设计](#4-模块详细设计)
5. [数据流](#5-数据流)
6. [数据库变更](#6-数据库变更)
7. [错误码规范](#7-错误码规范)
8. [扩展性设计](#8-扩展性设计)
9. [性能与约束](#9-性能与约束)

---

## 1. 背景与目标

### 1.1 问题描述

原有 `PdfParser` 实现将 PDF 全文一次性 dump 为纯字符串，导致：

- 所有页码、章节、表格结构信息在解析阶段全部丢失
- 扫描件 PDF 上传后静默产生空 chunk，用户无任何错误提示
- RAG 检索结果无法溯源到原文页码，LLM 容易生成无来源的内容
- 表格数据被拉平为无序文本，财报/合同数值语义严重失真

### 1.2 改造目标

| 目标 | 验收标准 |
|------|---------|
| 逐页提取 + 页码保留 | 每个 Chunk 携带 `pageNum` 字段，检索结果可展示来源页 |
| 扫描件明确拒绝 | 文字覆盖率 < 阈值时抛出 ErrorCode 5003，前端展示友好提示 |
| 章节标题感知 | 每个 Chunk 携带 `sectionTitle`，支持章节级引用 |
| 表格块类型标记 | 表格内容标记为 `TABLE` 类型，与正文 `TEXT` 区分 |
| 接口向后不兼容 | `DocumentParser.parse()` 返回 `ParsedDocument`，破坏性升级，要求所有实现同步修改 |


---

## 2. 整体架构

### 2.1 改造前后对比

```
改造前：
  MinIO → PdfParser(PDFTextStripper) → String
                                         ↓
                              RecursiveChunkSplitter(String, fileType)
                                         ↓
                              List<String> → KnowledgeChunk（无页码/章节/类型）

改造后：
  MinIO → PdfParser(逐页+类型检测) → ParsedDocument(pages[{pageNum, blocks[{content, chunkType, sectionTitle}]}])
                                         ↓
                              RecursiveChunkSplitter(ParsedDocument, fileType)
                                         ↓
                              List<SplitResult>(content + pageNum + sectionTitle + chunkType)
                                         ↓
                              KnowledgeChunk（携带完整元数据）→ pgvector
```

### 2.2 组件职责划分

| 组件 | 改造前职责 | 改造后职责 |
|------|-----------|-----------|
| `PdfParser` | 全文 dump 为 String | 类型检测 → 逐页提取 → 段落/表格拆块 |
| `DocumentParser` 接口 | `parse() → String` | `parse() → ParsedDocument` |
| `MultiFormatParser` | 分发 + 返回 String | 分发 + 返回 ParsedDocument |
| `RecursiveChunkSplitter` | 接收 String，输出 `List<String>` | 新增重载：接收 ParsedDocument，输出 `List<SplitResult>` |
| `DocumentIngestPipeline` | 处理 `List<String>` | 处理 `List<SplitResult>`，buildChunks 注入三列元数据 |
| `KnowledgeChunk` | 无位置信息 | 新增 `pageNum / sectionTitle / chunkType` |


---

## 3. 核心数据结构

### 3.1 类层次关系

```
ParsedDocument
  ├── title: String            // PDF 书签根节点标题，可 null
  ├── pdfType: PdfType         // NATIVE_TEXT / SCANNED / MIXED
  └── pages: List<ParsedPage>
        ├── pageNum: int       // 1-based 页码
        └── blocks: List<ParsedBlock>
              ├── content: String       // 段落/表格文本内容
              ├── chunkType: ChunkType  // TEXT / TABLE / IMAGE_CAPTION
              └── sectionTitle: String  // 所属章节标题，可 null
```

### 3.2 枚举定义

**PdfType** — PDF 文件类型

| 值 | 说明 | 处理策略 |
|----|------|---------|
| `NATIVE_TEXT` | 含文字层，可直接提取 | 正常逐页解析 |
| `SCANNED` | 扫描件，无文字层 | 抛出 ErrorCode 5003，拒绝入库 |
| `MIXED` | 图文混排，文字覆盖率偏低 | 正常解析，图片部分丢失（待 OCR 支持后补全） |

**ChunkType** — Chunk 内容类型

| 值 | 说明 | 检索展示策略 |
|----|------|------------|
| `TEXT` | 普通正文段落 | 默认展示 |
| `TABLE` | 表格内容（已转结构化文本） | 可加「表格」标签提示 |
| `IMAGE_CAPTION` | 图片说明/多模态摘要（预留） | 可加「图表」标签提示 |

### 3.3 SplitResult — 切片结果

```java
SplitResult {
  content:      String     // 切片文本内容
  pageNum:      Integer    // 来源页码，非 PDF 文档为 null
  sectionTitle: String     // 所属章节标题，可 null
  chunkType:    ChunkType  // 内容类型
}
```

由 `RecursiveChunkSplitter.split(ParsedDocument, fileType)` 输出，直接传入 `DocumentIngestPipeline.buildChunks()`。


---

## 4. 模块详细设计

### 4.1 PdfParser — 类型检测算法

**检测逻辑**：采样前 5 页（或全部页，取较小值），计算平均有效字符数：

```
avgCharsPerPage = 去除空白字符的总字符数 / 采样页数

avgCharsPerPage < 10  → SCANNED（极少文字，判定为扫描件）
avgCharsPerPage < 50  → MIXED（文字覆盖率偏低，图文混排）
avgCharsPerPage ≥ 50  → NATIVE_TEXT（正常文本型 PDF）
```

**为何用字符数而非页面面积比**：PDFBox 无法可靠获取页面像素级渲染结果，用面积比计算依赖字体大小推算，误差大。字符数阈值经验来自：正常排版中文段落每页约 500-1500 字符，标题页/空白页约 20-50 字符，扫描件为 0。

### 4.2 PdfParser — 逐页提取流程

```
for page in [1..totalPages]:
    stripper.setStartPage(page)
    stripper.setEndPage(page)
    pageText = stripper.getText(doc).trim()
    
    if pageText.isBlank() → 跳过（纯图片页/空白页）
    
    blocks = splitPageIntoBlocks(pageText)
    pages.add(ParsedPage(pageNum=page, blocks=blocks))
```

**多列排版处理**：`setSortByPosition(true)` 让 PDFBox 按 x/y 坐标排序文字，将多列文字按视觉阅读顺序重组，避免左右两列文字交错混排。

### 4.3 PdfParser — 段落/表格块拆分

每页文本按双空行（`\n{2,}`）拆为段落，逐段判断类型：

**表格检测规则**：段落内超过 60% 的行匹配正则 `^.{2,}(\s{2,}|\t).{2,}$`（即含有多个连续空格或制表符分隔的行），则判定为 `TABLE` 类型。

**章节标题检测规则**：行长度 ≤ 50 字符，且匹配以下任一模式：
- `第X章 / 第X节 / 第X条`（中文章节编号）
- `1. / 2. / 3.`（数字序号）
- `一、二、三、`（中文序号）
- 纯大写英文（3-30 字符，常见于英文报告标题）

检测到标题行后，后续所有块的 `sectionTitle` 更新为该标题，直到下一个标题出现。

### 4.4 RecursiveChunkSplitter — 新旧接口共存

新增 `split(ParsedDocument, fileType)` 方法，保留原有 `split(String, fileType)` 不动（供降级和测试使用），两者共享底层 `splitRecursive` 逻辑：

```
split(ParsedDocument doc, String fileType):
  for each page in doc.pages:
    for each block in page.blocks:
      rawChunks = splitRecursive(block.content, 0)  // 复用原有递归切分
      for each chunk in rawChunks:
        results.add(SplitResult(
          content      = chunk,
          pageNum      = page.pageNum,
          sectionTitle = block.sectionTitle,
          chunkType    = block.chunkType
        ))
```

**跨页不合并**：每个 Block 独立切分，不跨 Block 累积。这避免了跨章节、跨页的语义混淆，代价是 Block 内容较短时可能产生 token 数偏少的 Chunk（由 `ChunkQualityDomainService.filter()` 兜底过滤）。

### 4.5 DocumentIngestPipeline — 流程变更对比

| 步骤 | 改造前 | 改造后 |
|------|--------|--------|
| Step 2 解析 | `String rawText = parser.parse(content, fileType)` | `ParsedDocument parsedDoc = parser.parse(content, fileType)` |
| Step 3 切片 | `List<String> rawChunks = splitter.split(rawText, fileType)` | `List<SplitResult> rawSplits = splitter.split(parsedDoc, fileType)` |
| Step 4 过滤 | `qualityService.filter(rawChunks)` | `rawSplits.stream().filter(s -> qualityService.passable(s.getContent()))` |
| Step 5 构建 | `buildChunks(List<String>, event)` | `buildChunks(List<SplitResult>, event)` |
| 元数据注入 | 无页码/章节/类型 | `pageNum / sectionTitle / chunkType` 从 SplitResult 注入 |


---

## 5. 数据流

### 5.1 正常原生文本 PDF 完整链路

```
用户上传 PDF
    ↓
DocIngestAppService.upload()
    → MinIO 上传
    → knowledge_doc 写 DRAFT 状态
    → 事务提交后 afterCommit() 发 RabbitMQ
    ↓
DocIngestConsumer 消费消息
    ↓
DocumentIngestPipeline.process()
    ↓
[Step 1] MinIO 下载字节流
    ↓
[Step 2] PdfParser.parse(bytes)
    → detectType(): avgCharsPerPage = 680 → NATIVE_TEXT
    → extractPages(): 逐页提取，共 15 页，12 页有效
    → splitPageIntoBlocks(): 每页按双空行拆块，识别 3 个 TABLE 块
    → 返回 ParsedDocument(pdfType=NATIVE_TEXT, pages=[12个ParsedPage])
    ↓
[Step 3] RecursiveChunkSplitter.split(ParsedDocument, "PDF")
    → 遍历每页每块，递归切分
    → 返回 List<SplitResult>[47个]，每个携带 pageNum/sectionTitle/chunkType
    ↓
[Step 4] ChunkQualityDomainService 过滤
    → 47 个 → 44 个通过
    ↓
[Step 5] buildChunks(): SplitResult → KnowledgeChunk（注入 pageNum/sectionTitle/chunkType）
    ↓
[Step 6] EmbeddingService.embed(): BGE-M3 批量向量化
    ↓
[Step 7] chunkRepository.deleteByDocId() + saveAll(): 写入 pgvector
    ↓
[Step 8] docRepository.updateStatus(PUBLISHED)
```

### 5.2 扫描件 PDF 处理链路

```
用户上传扫描件 PDF
    ↓
PdfParser.parse(bytes)
    → detectType(): avgCharsPerPage = 3 → SCANNED
    → 抛出 BusinessException(5003, "PDF 为扫描件，当前版本暂不支持...")
    ↓
DocumentIngestPipeline 捕获异常
    → docRepository.updateStatus(FAILED)
    → 异常向上传播
    ↓
DocIngestConsumer 重试 3 次后 nack → DLX → DLQ
    ↓
前端轮询文档状态
    → 展示"PDF 为扫描件，请上传含文字层的 PDF 文件"
```


---

## 6. 数据库变更

### 6.1 DDL 变更（knowledge_chunk 表）

```sql
-- 幂等执行，可重复运行
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_num      INTEGER      DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS section_title TEXT         DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS chunk_type    VARCHAR(20)  NOT NULL DEFAULT 'TEXT';

CREATE INDEX IF NOT EXISTS idx_chunk_type ON knowledge_chunk(chunk_type)
    WHERE doc_status = 'PUBLISHED';
```

### 6.2 字段说明

| 列名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page_num` | `INTEGER` | `NULL` | 来源页码（1-based）。PDF 逐页提取时填充，Markdown/HTML/DOCX 为 NULL（Markdown 填虚拟节序号） |
| `section_title` | `TEXT` | `NULL` | 所属章节标题，从文档结构或标题行提取，无法检测时为 NULL |
| `chunk_type` | `VARCHAR(20)` | `'TEXT'` | Chunk 内容类型：`TEXT` / `TABLE` / `IMAGE_CAPTION` |

### 6.3 对已有数据的影响

- 存量 chunk：`ADD COLUMN` 后，`page_num` 为 NULL，`section_title` 为 NULL，`chunk_type` 填 `'TEXT'`（DEFAULT）
- 存量数据**无需回填**，旧 chunk 在检索时 `page_num` 为 NULL 表示"来源页未知"，前端展示时做 null 判断即可
- 无需重新摄取存量文档，新文档上传后自动携带完整元数据

### 6.4 Mapper XML 变更

`KnowledgeChunkMapper.xml` 的 `insertBatch`、`selectByVector`、`selectByFullText` 三处 SQL 均需补充三列，详见 Task 7 实施步骤。


---

## 7. 错误码规范

| 错误码 | 触发场景 | 用户提示文案 |
|--------|---------|------------|
| `5000` | 文件内容为空字节 | 文件内容为空，请重新上传 |
| `5001` | PDF 文件损坏或格式不合法 | PDF 文件解析失败，请检查文件完整性 |
| `5002` | 上传了不支持的文件格式 | 不支持的文件格式，当前支持：PDF/MARKDOWN/HTML/DOCX/TICKET |
| `5003` | 上传了扫描件 PDF（无文字层） | PDF 为扫描件，暂不支持提取，请上传含文字层的 PDF 文件 |

5003 是本次新增错误码，需同步更新前端错误码映射表和文档上传错误提示逻辑。

---

## 8. 扩展性设计

### 8.1 后续 OCR 支持（Phase 2）

当 `PdfType == SCANNED` 时，当前抛出 5003 拒绝。后续接入 PaddleOCR HTTP 服务后，只需在 `PdfParser` 的 `detectType()` 分支中增加 OCR 路径，`ParsedDocument` 结构无需改变：

```java
if (pdfType == PdfType.SCANNED) {
    // 当前：throw new BusinessException(5003, ...)
    // Phase 2：return ocrService.recognize(doc);  // 同样返回 ParsedDocument
}
```

### 8.2 多模态图表理解（Phase 3）

`ChunkType.IMAGE_CAPTION` 已预留，后续从 PDF 提取嵌入图片后调用视觉模型生成摘要，填入 `ParsedBlock.content`，`chunkType` 设为 `IMAGE_CAPTION`，整个下游流程（切片、向量化、存储）无需修改。

### 8.3 新增文档格式

所有非 PDF 格式（Word/Markdown/HTML/TICKET）使用 `ParsedDocument.ofSinglePage()` 工厂方法包装为单页结构，之后可按需升级为逐章节多页结构，对 `RecursiveChunkSplitter` 和 `DocumentIngestPipeline` 完全透明。

### 8.4 Contextual Retrieval（未来方向）

在 `DocumentIngestPipeline` Step 5 和 Step 6 之间插入上下文注入步骤：为每个 `KnowledgeChunk` 调用轻量 LLM 生成 50-100 字的位置上下文摘要，填入预留的 `metadata` JSONB 字段（`contextualSummary` key），向量化时将摘要与正文拼接后 embed，可提升召回精度约 20-30%（Anthropic 数据）。

---

## 9. 性能与约束

### 9.1 解析耗时基准

| PDF 规格 | PDFBox 提取耗时（单线程） | 备注 |
|---------|------------------------|------|
| 10 页普通文档 | < 200ms | 正常情况 |
| 100 页报告 | 1-3s | 可接受，异步管道中运行 |
| 500 页合同 | 5-15s | 异步无影响，注意 MinIO 下载带宽 |
| 扫描件（任意页数） | < 100ms | 类型检测阶段即拒绝，无需全文扫描 |

类型检测仅采样前 5 页，保证了对大文件的快速失败能力。

### 9.2 内存占用

PDFBox 在 `Loader.loadPDF(bytes)` 时会将整个 PDF 加载到堆内存。对于超大 PDF（> 50MB），建议在 `DocIngestAppService.upload()` 入口增加文件大小限制（当前未限制）。推荐上限：单文件 ≤ 50MB，超出时返回业务错误而非让 JVM OOM。

### 9.3 并发安全

`PDFTextStripper` 不是线程安全的，当前实现在每次 `parse()` 调用时新建实例，配合 `DocumentIngestPipeline` 的并发数配置（`knowledge.ingest.consumer-concurrency`，默认 2）不存在竞态问题。

### 9.4 已知局限

| 局限 | 说明 |
|------|------|
| 扫描件不支持 | Phase 1 明确拒绝，待 OCR 服务接入后开放 |
| 表格检测为启发式 | 基于空格分隔检测，合并单元格/无边框表格可能误判为 TEXT |
| 图片内容丢失 | 嵌入图片的视觉内容不提取，IMAGE_CAPTION 类型预留但尚未填充 |
| 加密 PDF 不支持 | PDFBox 对密码保护 PDF 抛出异常，统一归入 5001 错误码 |
| 页眉页脚未过滤 | `setSortByPosition(true)` 后页眉页脚文字会混入正文，需后续基于位置坐标过滤 |

---

## 附录：涉及文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `parser/PdfType.java` | 新建 | PDF 类型枚举 |
| `parser/ChunkType.java` | 新建 | Chunk 类型枚举 |
| `parser/ParsedBlock.java` | 新建 | 最小解析单元 |
| `parser/ParsedPage.java` | 新建 | 单页解析结果 |
| `parser/ParsedDocument.java` | 新建 | 文档解析结果聚合 |
| `splitter/SplitResult.java` | 新建 | 切片结果 + 元数据 |
| `parser/DocumentParser.java` | 修改 | 返回类型 `String` → `ParsedDocument` |
| `parser/PdfParser.java` | 完全重写 | 类型检测 + 逐页提取 + 表格识别 |
| `parser/MarkdownParser.java` | 修改 | 按标题切虚拟页 + 返回 ParsedDocument |
| `parser/HtmlParser.java` | 修改 | wrap 为 ParsedDocument |
| `parser/WordParser.java` | 修改 | wrap 为 ParsedDocument |
| `parser/TicketParser.java` | 修改 | wrap 为 ParsedDocument |
| `parser/MultiFormatParser.java` | 修改 | 返回类型改为 ParsedDocument |
| `domain/model/KnowledgeChunk.java` | 修改 | 新增 pageNum/sectionTitle/chunkType |
| `persistence/entity/KnowledgeChunkEntity.java` | 修改 | 新增三个 DB 字段 |
| `persistence/assembler/KnowledgeChunkAssembler.java` | 修改 | 映射三个新字段 |
| `splitter/RecursiveChunkSplitter.java` | 修改 | 新增 split(ParsedDocument) 重载 |
| `mq/DocumentIngestPipeline.java` | 修改 | 适配新流程，buildChunks 注入元数据 |
| `mapper/KnowledgeChunkMapper.xml` | 修改 | INSERT/SELECT 补充三列 |
| `db/migration-001-chunk-metadata.sql` | 新建 | DDL 迁移脚本 |

*实施计划详见：`docs/superpowers/plans/2026-07-01-pdf-structured-parse.md`*






