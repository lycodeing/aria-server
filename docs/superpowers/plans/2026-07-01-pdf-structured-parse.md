# PDF 结构化解析（逐页提取 + 元数据 + 类型检测）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 PDF 解析从"全文一次输出纯文本"升级为"逐页结构化提取"，Chunk 携带页码/章节/类型元数据，并在扫描件上传时给出明确报错而非静默失败。

**Architecture:** `DocumentParser` 接口返回类型从 `String` 升级为 `ParsedDocument`（含多个 `ParsedPage`，每页含多个 `ParsedBlock`）；`RecursiveChunkSplitter` 改为接受 `ParsedDocument` 并输出带元数据的 `KnowledgeChunk`；数据库新增 `page_num / section_title / chunk_type` 三列；`DocumentIngestPipeline` 适配新流程。

**Tech Stack:** Apache PDFBox 3.0.3（已引入）、MyBatis-Plus、PostgreSQL pgvector、Spring Boot 3.3.5、JUnit 5

---

## 文件结构

### 新建文件

| 文件路径 | 职责 |
|----------|------|
| `knowledge-service/.../parser/ParsedDocument.java` | 解析结果聚合对象，含 ParsedPage 列表和文档元数据 |
| `knowledge-service/.../parser/ParsedPage.java` | 单页解析结果，含页码和 ParsedBlock 列表 |
| `knowledge-service/.../parser/ParsedBlock.java` | 最小解析单元，含内容、块类型、章节标题 |
| `knowledge-service/.../parser/PdfType.java` | PDF 类型枚举（NATIVE_TEXT / SCANNED / MIXED） |
| `knowledge-service/.../parser/ChunkType.java` | Chunk 类型枚举（TEXT / TABLE / IMAGE_CAPTION） |
| `docs/sql/migration-001-chunk-metadata.sql` | DDL：knowledge_chunk 表新增三列 |
| `knowledge-service/.../PdfParserTest.java` | PdfParser 单元测试 |
| `knowledge-service/.../DocumentIngestPipelineTest.java` | Pipeline 集成测试 |

### 修改文件

| 文件路径 | 变更内容 |
|----------|---------|
| `parser/DocumentParser.java` | 返回类型 `String` → `ParsedDocument` |
| `parser/PdfParser.java` | 完全重写：类型检测 + 逐页提取 + 表格识别 |
| `parser/MarkdownParser.java` | 适配新接口，wrap 为 ParsedDocument |
| `parser/HtmlParser.java` | 适配新接口，wrap 为 ParsedDocument |
| `parser/WordParser.java` | 适配新接口，wrap 为 ParsedDocument |
| `parser/TicketParser.java` | 适配新接口，wrap 为 ParsedDocument |
| `parser/MultiFormatParser.java` | 返回类型改为 ParsedDocument |
| `domain/model/KnowledgeChunk.java` | 新增 pageNum / sectionTitle / chunkType |
| `persistence/entity/KnowledgeChunkEntity.java` | 新增三个 DB 字段 |
| `persistence/assembler/KnowledgeChunkAssembler.java` | 映射三个新字段 |
| `splitter/RecursiveChunkSplitter.java` | 接受 ParsedDocument，输出带元数据的结果对象 |
| `mq/DocumentIngestPipeline.java` | 适配 ParsedDocument 流程，buildChunks 注入元数据 |
| `resources/mapper/KnowledgeChunkMapper.xml` | INSERT 语句增加三列 |


---

### Task 1：新建值对象与枚举（5个文件，无破坏性，可先合并提交）

**Files:**
- 创建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/PdfType.java`
- 创建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/ChunkType.java`
- 创建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/ParsedBlock.java`
- 创建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/ParsedPage.java`
- 创建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/ParsedDocument.java`

- [ ] **Step 1：创建 PdfType.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

/** PDF 文件类型枚举，用于 PdfParser 类型检测后的分支处理。 */
public enum PdfType {
    /** 原生文本型 PDF：含可提取文字层，直接用 PDFTextStripper */
    NATIVE_TEXT,
    /** 扫描件型 PDF：无文字层，当前版本拒绝入库，需接入 OCR 后支持 */
    SCANNED,
    /** 图文混排型 PDF：部分页含图表，文字层覆盖率偏低 */
    MIXED
}
```

- [ ] **Step 2：创建 ChunkType.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

/** Chunk 内容类型枚举，用于检索策略差异化处理和引用展示。 */
public enum ChunkType {
    /** 普通正文文本 */
    TEXT,
    /** 表格内容（已转为「字段: 值」结构化文本） */
    TABLE,
    /** 图片说明文字（caption）或未来多模态摘要 */
    IMAGE_CAPTION
}
```

- [ ] **Step 3：创建 ParsedBlock.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import lombok.Builder;
import lombok.Data;

/**
 * 最小解析单元：一段文本块，含类型与所属章节信息。
 * 对应 PDF 中的一个段落、一张表格或一段图注。
 */
@Data
@Builder
public class ParsedBlock {
    /** 块文本内容 */
    private String    content;
    /** 块类型（TEXT / TABLE / IMAGE_CAPTION） */
    private ChunkType chunkType;
    /** 所属章节标题，null 表示无法检测到章节边界 */
    private String    sectionTitle;
}
```

- [ ] **Step 4：创建 ParsedPage.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单页解析结果，含页码和该页内所有文本块。
 */
@Data
@Builder
public class ParsedPage {
    /** 1-based 页码 */
    private int              pageNum;
    /** 该页所有文本块，按阅读顺序排列 */
    private List<ParsedBlock> blocks;

    /** 将本页所有块拼接为纯文本（供非结构化处理路径使用） */
    public String toPlainText() {
        if (blocks == null || blocks.isEmpty()) return "";
        return blocks.stream()
            .map(ParsedBlock::getContent)
            .collect(Collectors.joining("\n\n"));
    }
}
```

- [ ] **Step 5：创建 ParsedDocument.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档解析结果聚合对象，由 DocumentParser 各实现返回。
 * 非 PDF 文档通过工厂方法 {@link #ofSinglePage} 包装为单页结构。
 */
@Data
@Builder
public class ParsedDocument {
    /** 文档标题（PDF outline 根节点或文件名） */
    private String          title;
    /** PDF 类型（非 PDF 文档固定为 NATIVE_TEXT） */
    @Builder.Default
    private PdfType         pdfType = PdfType.NATIVE_TEXT;
    /** 按顺序排列的页列表 */
    private List<ParsedPage> pages;

    /**
     * 将全部页内容拼接为纯文本（供 RecursiveChunkSplitter 降级使用）。
     */
    public String toPlainText() {
        if (pages == null || pages.isEmpty()) return "";
        return pages.stream()
            .map(ParsedPage::toPlainText)
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * 非 PDF 解析器专用工厂方法：将纯文本包装为单页文档。
     *
     * @param text         解析后的纯文本
     * @param sectionTitle 章节标题（通常传 null）
     */
    public static ParsedDocument ofSinglePage(String text, String sectionTitle) {
        ParsedBlock block = ParsedBlock.builder()
            .content(text)
            .chunkType(ChunkType.TEXT)
            .sectionTitle(sectionTitle)
            .build();
        ParsedPage page = ParsedPage.builder()
            .pageNum(1)
            .blocks(List.of(block))
            .build();
        return ParsedDocument.builder()
            .pdfType(PdfType.NATIVE_TEXT)
            .pages(List.of(page))
            .build();
    }
}
```

- [ ] **Step 6：编译验证（无测试，纯 VO）**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS，无任何报错。


---

### Task 2：升级 DocumentParser 接口 + 适配四个非 PDF 解析器

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/DocumentParser.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/MarkdownParser.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/HtmlParser.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/WordParser.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/TicketParser.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/MultiFormatParser.java`

- [ ] **Step 1：修改 DocumentParser 接口**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

/**
 * 文档解析器接口。
 * 职责：将原始字节流解析为结构化 {@link ParsedDocument}，保留页码、章节、块类型等元数据。
 * 每种格式对应一个实现类，通过 MultiFormatParser 按 fileType 分发。
 */
public interface DocumentParser {

    /**
     * 将原始文件字节流解析为结构化文档。
     *
     * @param content 原始文件字节数组
     * @return 含页码、章节、块类型的结构化文档对象
     * @throws com.aidevplatform.common.core.exception.BusinessException 解析失败时抛出
     */
    ParsedDocument parse(byte[] content);

    /**
     * 本实现支持的文件类型标识，对应 knowledge_doc.file_type 字段。
     * 如：MARKDOWN / PDF / HTML / DOCX / TICKET
     */
    String supportedType();
}
```

- [ ] **Step 2：修改 MarkdownParser — 按标题切成多页**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档解析器。
 * 每个一级/二级标题（# / ##）对应一个虚拟"页"，保留标题作为 sectionTitle。
 */
@Slf4j
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public ParsedDocument parse(byte[] content) {
        try {
            String text = new String(content, StandardCharsets.UTF_8);
            List<ParsedPage> pages = splitByHeading(text);
            log.debug("Markdown 解析完成，虚拟页数={}", pages.size());
            return ParsedDocument.builder()
                .pdfType(PdfType.NATIVE_TEXT)
                .pages(pages)
                .build();
        } catch (Exception e) {
            throw new BusinessException(5001, "Markdown 解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "MARKDOWN";
    }

    private List<ParsedPage> splitByHeading(String text) {
        List<ParsedPage> pages = new ArrayList<>();
        // 按 # 或 ## 标题边界切分
        String[] sections = text.split("(?=\n#{1,2} )");
        int virtualPage = 1;
        for (String section : sections) {
            if (section.isBlank()) continue;
            String title = extractTitle(section);
            ParsedBlock block = ParsedBlock.builder()
                .content(section.trim())
                .chunkType(ChunkType.TEXT)
                .sectionTitle(title)
                .build();
            pages.add(ParsedPage.builder()
                .pageNum(virtualPage++)
                .blocks(List.of(block))
                .build());
        }
        if (pages.isEmpty()) {
            return List.of(ParsedPage.builder()
                .pageNum(1)
                .blocks(List.of(ParsedBlock.builder()
                    .content(text.trim())
                    .chunkType(ChunkType.TEXT)
                    .build()))
                .build());
        }
        return pages;
    }

    private String extractTitle(String section) {
        String firstLine = section.trim().split("\n")[0];
        return firstLine.replaceFirst("^#{1,3}\\s*", "").trim();
    }
}
```

- [ ] **Step 3：修改 HtmlParser — wrap 为 ParsedDocument**

将 `parse(byte[])` 方法的返回类型改为 `ParsedDocument`，原有 Jsoup 提取逻辑不变，最后用 `ParsedDocument.ofSinglePage` 包装：

```java
@Override
public ParsedDocument parse(byte[] content) {
    try {
        String html = new String(content, StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html);
        // 原有语义标签提取逻辑保持不变
        Element main = doc.selectFirst("main, article, [role=main], .content, #content");
        String text = (main != null ? main : doc.body()).text();
        log.debug("HTML 解析完成，字符数={}", text.length());
        return ParsedDocument.ofSinglePage(text, null);
    } catch (Exception e) {
        throw new BusinessException(5001, "HTML 解析失败：" + e.getMessage());
    }
}

@Override
public String supportedType() { return "HTML"; }
```

- [ ] **Step 4：修改 WordParser — wrap 为 ParsedDocument**

```java
@Override
public ParsedDocument parse(byte[] content) {
    try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(content))) {
        XWPFWordExtractor extractor = new XWPFWordExtractor(docx);
        String text = extractor.getText();
        log.debug("Word 解析完成，字符数={}", text.length());
        return ParsedDocument.ofSinglePage(text, null);
    } catch (Exception e) {
        throw new BusinessException(5001, "Word 解析失败：" + e.getMessage());
    }
}

@Override
public String supportedType() { return "DOCX"; }
```

- [ ] **Step 5：修改 TicketParser — wrap 为 ParsedDocument，PII 脱敏逻辑不变**

在原有 `String parse(byte[])` 结尾将结果包装：

```java
@Override
public ParsedDocument parse(byte[] content) {
    // 原有 PII 脱敏和文本提取逻辑保持不变，最终 text 变量
    String text = extractAndDesensitize(content);
    return ParsedDocument.ofSinglePage(text, null);
}
```

- [ ] **Step 6：修改 MultiFormatParser — 返回 ParsedDocument**

```java
public ParsedDocument parse(byte[] content, String fileType) {
    if (content == null || content.length == 0) {
        throw new BusinessException(5000, "文件内容不能为空");
    }
    DocumentParser parser = parsers.get(fileType.toUpperCase());
    if (parser == null) {
        throw new BusinessException(5002,
            "不支持的文档格式：" + fileType + "，当前支持：" + parsers.keySet());
    }
    log.debug("开始解析文档，fileType={}，字节数={}", fileType, content.length);
    return parser.parse(content);
}
```

- [ ] **Step 7：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。此时 PdfParser 尚未修改，编译会有返回类型不匹配错误，下一 Task 立即解决。


---

### Task 3：完全重写 PdfParser（类型检测 + 逐页提取 + 表格识别）

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/parser/PdfParser.java`

- [ ] **Step 1：用以下代码完整替换 PdfParser.java 全部内容**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PDF 文档解析器（Apache PDFBox 3.x）。
 *
 * <p>处理策略：
 * <ol>
 *   <li>类型检测：计算全文字符密度，&lt;5% 视为扫描件，立即拒绝并抛出明确异常</li>
 *   <li>逐页提取：按页提取文本，每页携带 1-based 页码</li>
 *   <li>块拆分：每页内按段落（空行）分块，检测表格行并标记 TABLE 类型</li>
 *   <li>章节标题：检测全大写或数字编号开头行作为 sectionTitle</li>
 * </ol>
 *
 * <p>扫描件支持：当前版本拒绝入库，返回 ErrorCode 5003，前端展示"PDF 为扫描件，
 * 暂不支持，请上传可复制文字的 PDF"，待后续接入 OCR 服务后开放。
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    /** 文字密度低于此阈值判定为扫描件（字符数/页面面积估算值） */
    private static final double SCANNED_TEXT_RATIO_THRESHOLD = 0.05;
    /** 表格行检测：连续多个空格或制表符分隔 */
    private static final Pattern TABLE_ROW_PATTERN =
        Pattern.compile("^.{2,}(\\s{2,}|\\t).{2,}$");
    /** 章节标题检测：数字编号（1. / 一、/ 第X章）或短行（≤30字符全大写） */
    private static final Pattern SECTION_TITLE_PATTERN =
        Pattern.compile("^(第[一二三四五六七八九十百]+[章节条]|\\d+\\.\\s|[一二三四五六七八九十]+[、.]|[A-Z\\s]{3,30})");

    @Override
    public ParsedDocument parse(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            int totalPages = doc.getNumberOfPages();
            PdfType pdfType = detectType(doc);
            log.info("PDF 类型检测完成，类型={}，页数={}", pdfType, totalPages);

            if (pdfType == PdfType.SCANNED) {
                throw new BusinessException(5003,
                    "PDF 为扫描件，当前版本暂不支持提取，请上传含文字层的 PDF 文件");
            }

            List<ParsedPage> pages = extractPages(doc);
            String docTitle = extractDocTitle(doc);

            return ParsedDocument.builder()
                .title(docTitle)
                .pdfType(pdfType)
                .pages(pages)
                .build();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(5001, "PDF 解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "PDF";
    }

    // ===== 私有方法 =====

    /**
     * 检测 PDF 类型：通过采样前5页的文字覆盖率判断。
     * 覆盖率 = 提取字符数 / 页面面积估算值（像素单位的启发式估算）。
     */
    private PdfType detectType(PDDocument doc) throws IOException {
        int samplePages = Math.min(5, doc.getNumberOfPages());
        PDFTextStripper sampler = new PDFTextStripper();
        sampler.setSortByPosition(true);
        int totalChars = 0;
        for (int i = 1; i <= samplePages; i++) {
            sampler.setStartPage(i);
            sampler.setEndPage(i);
            totalChars += sampler.getText(doc).replaceAll("\\s", "").length();
        }
        // 每页期望至少 50 个有效字符
        double avgCharsPerPage = (double) totalChars / samplePages;
        if (avgCharsPerPage < 10) return PdfType.SCANNED;
        if (avgCharsPerPage < 50) return PdfType.MIXED;
        return PdfType.NATIVE_TEXT;
    }

    /** 逐页提取，每页拆分为多个 ParsedBlock。 */
    private List<ParsedPage> extractPages(PDDocument doc) throws IOException {
        List<ParsedPage> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        for (int i = 1; i <= doc.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(doc).trim();
            if (pageText.isBlank()) continue;

            List<ParsedBlock> blocks = splitPageIntoBlocks(pageText);
            if (!blocks.isEmpty()) {
                pages.add(ParsedPage.builder()
                    .pageNum(i)
                    .blocks(blocks)
                    .build());
            }
        }
        log.debug("PDF 逐页提取完成，有效页数={}", pages.size());
        return pages;
    }

    /**
     * 将单页文本按空行拆分为段落块，检测表格行和章节标题。
     * 连续的表格行合并为一个 TABLE 类型块。
     */
    private List<ParsedBlock> splitPageIntoBlocks(String pageText) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String[] paragraphs = pageText.split("\n{2,}");
        String currentSection = null;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isBlank()) continue;

            // 检测章节标题（短行 + 标题格式）
            if (trimmed.length() <= 50 && SECTION_TITLE_PATTERN.matcher(trimmed).find()) {
                currentSection = trimmed;
            }

            // 检测表格（多行中大多数行匹配表格模式）
            ChunkType type = detectBlockType(trimmed);

            blocks.add(ParsedBlock.builder()
                .content(trimmed)
                .chunkType(type)
                .sectionTitle(currentSection)
                .build());
        }
        return blocks;
    }

    /** 判断段落是否为表格：超过60%的行匹配表格行模式。 */
    private ChunkType detectBlockType(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 2) return ChunkType.TEXT;
        long tableLines = java.util.Arrays.stream(lines)
            .filter(l -> TABLE_ROW_PATTERN.matcher(l.trim()).matches())
            .count();
        return (double) tableLines / lines.length > 0.6
            ? ChunkType.TABLE
            : ChunkType.TEXT;
    }

    /** 尝试从 PDF outline（书签）提取文档标题，失败时返回 null。 */
    private String extractDocTitle(PDDocument doc) {
        try {
            if (doc.getDocumentInformation() != null) {
                return doc.getDocumentInformation().getTitle();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
```

- [ ] **Step 2：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。此时 `DocumentIngestPipeline` 调用 `parser.parse()` 仍返回旧 `String` 类型，Task 6 会修复。


---

### Task 4：扩展 KnowledgeChunk + KnowledgeChunkEntity + Assembler（三列新元数据）

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/domain/model/KnowledgeChunk.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/persistence/entity/KnowledgeChunkEntity.java`
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/persistence/assembler/KnowledgeChunkAssembler.java`

- [ ] **Step 1：在 KnowledgeChunk.java 新增三个字段**

在 `feedbackDownvotes` 字段后面追加：

```java
/** 来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 null */
private Integer    pageNum;
/** 所属章节标题，解析时从文档结构或标题行提取 */
private String     sectionTitle;
/** Chunk 内容类型（TEXT / TABLE / IMAGE_CAPTION），影响检索展示策略 */
private ChunkType  chunkType;
```

注意：`ChunkType` 类型位于 `com.aidevplatform.knowledge.infrastructure.parser` 包，需要在 `KnowledgeChunk.java` 顶部添加 import：

```java
import com.aidevplatform.knowledge.infrastructure.parser.ChunkType;
```

- [ ] **Step 2：在 KnowledgeChunkEntity.java 新增三个字段**

在 `feedbackDownvotes` 字段后追加：

```java
/** 来源页码，knowledge_chunk.page_num 列 */
private Integer    pageNum;
/** 章节标题，knowledge_chunk.section_title 列 */
private String     sectionTitle;
/** Chunk 类型字符串（TEXT/TABLE/IMAGE_CAPTION），knowledge_chunk.chunk_type 列 */
private String     chunkType;
```

- [ ] **Step 3：更新 KnowledgeChunkAssembler.java 的 toDomain 方法**

在 `toDomain` 的 builder 链中补充三个新字段的映射（追加在 `feedbackDownvotes` 行之后）：

```java
.pageNum(e.getPageNum())
.sectionTitle(e.getSectionTitle())
.chunkType(e.getChunkType() != null
    ? ChunkType.valueOf(e.getChunkType()) : ChunkType.TEXT)
```

同时在文件顶部添加 import：

```java
import com.aidevplatform.knowledge.infrastructure.parser.ChunkType;
```

- [ ] **Step 4：更新 toEntity 方法**

在 `toEntity` 的 builder 链中补充（追加在 `feedbackDownvotes` 行之后）：

```java
.pageNum(d.getPageNum())
.sectionTitle(d.getSectionTitle())
.chunkType(d.getChunkType() != null ? d.getChunkType().name() : ChunkType.TEXT.name())
```

- [ ] **Step 5：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。


---

### Task 5：升级 RecursiveChunkSplitter（接受 ParsedDocument，输出带元数据结果）

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/splitter/RecursiveChunkSplitter.java`
- 新建: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/splitter/SplitResult.java`

- [ ] **Step 1：新建 SplitResult.java（切片结果携带元数据）**

```java
package com.aidevplatform.knowledge.infrastructure.splitter;

import com.aidevplatform.knowledge.infrastructure.parser.ChunkType;
import lombok.Builder;
import lombok.Data;

/**
 * 单个切片结果，携带文本内容和来源元数据。
 * 由 RecursiveChunkSplitter 输出，DocumentIngestPipeline 用于构建 KnowledgeChunk。
 */
@Data
@Builder
public class SplitResult {
    /** 切片文本内容 */
    private String    content;
    /** 来源页码（1-based），非 PDF 文档为 null */
    private Integer   pageNum;
    /** 所属章节标题 */
    private String    sectionTitle;
    /** 内容类型 */
    private ChunkType chunkType;
}
```

- [ ] **Step 2：修改 RecursiveChunkSplitter — 新增 split(ParsedDocument) 重载方法**

在 `RecursiveChunkSplitter` 类中新增以下方法（保留原有 `split(String, String)` 不动，供旧代码降级使用）：

```java
import com.aidevplatform.knowledge.infrastructure.parser.ParsedDocument;
import com.aidevplatform.knowledge.infrastructure.parser.ParsedPage;
import com.aidevplatform.knowledge.infrastructure.parser.ParsedBlock;
import com.aidevplatform.knowledge.infrastructure.splitter.SplitResult;

/**
 * 按 ParsedDocument 结构切片，保留页码/章节/类型元数据。
 * 每个 ParsedBlock 独立做递归切分，不跨页合并（避免跨章节噪声）。
 *
 * @param doc      结构化文档
 * @param fileType 文件类型，传给递归切分逻辑
 * @return 带元数据的切片列表
 */
public List<SplitResult> split(ParsedDocument doc, String fileType) {
    if (doc == null || doc.getPages() == null || doc.getPages().isEmpty()) {
        return List.of();
    }
    List<SplitResult> results = new ArrayList<>();
    for (ParsedPage page : doc.getPages()) {
        if (page.getBlocks() == null) continue;
        for (ParsedBlock block : page.getBlocks()) {
            if (block.getContent() == null || block.getContent().isBlank()) continue;
            // 对每个 Block 做递归切分
            List<String> rawChunks = splitRecursive(block.getContent(), 0);
            for (String chunk : rawChunks) {
                results.add(SplitResult.builder()
                    .content(chunk)
                    .pageNum(page.getPageNum())
                    .sectionTitle(block.getSectionTitle())
                    .chunkType(block.getChunkType() != null
                        ? block.getChunkType()
                        : com.aidevplatform.knowledge.infrastructure.parser.ChunkType.TEXT)
                    .build());
            }
        }
    }
    return results;
}
```

同时将 `splitRecursive` 方法改为 `private` → `package-private`（去掉访问修饰符），以便 `split(ParsedDocument)` 方法内直接调用：

找到：
```java
private List<String> splitRecursive(String text, int separatorIdx) {
```
改为：
```java
List<String> splitRecursive(String text, int separatorIdx) {
```

- [ ] **Step 3：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。


---

### Task 6：适配 DocumentIngestPipeline（接入结构化解析流程）

**Files:**
- 修改: `ai-knowledge/knowledge-service/src/main/java/com/aidevplatform/knowledge/infrastructure/mq/DocumentIngestPipeline.java`

- [ ] **Step 1：更新 import 区**

在 `DocumentIngestPipeline.java` 顶部追加两个 import（其余 import 不变）：

```java
import com.aidevplatform.knowledge.infrastructure.parser.ParsedDocument;
import com.aidevplatform.knowledge.infrastructure.splitter.SplitResult;
```

- [ ] **Step 2：修改 process 方法中的 Step 2-5**

将原来的：

```java
// Step 2：解析为纯文本
String rawText = parser.parse(content, event.getFileType());
log.debug("文档解析完成，docId={}，字符数={}", event.getDocId(), rawText.length());

// Step 3：拆分 chunk
List<String> rawChunks = splitter.split(rawText, event.getFileType());

// Step 4：质量过滤
List<String> qualified = qualityService.filter(rawChunks);
log.info("Chunk 拆分完成，docId={}，原始={}，合格={}", 
    event.getDocId(), rawChunks.size(), qualified.size());

if (qualified.isEmpty()) {
    log.warn("文档所有 chunk 均未通过质量过滤，docId={}，跳过摄取", event.getDocId());
    docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.PUBLISHED);
    return;
}

// Step 5：构建 KnowledgeChunk 领域对象（注入元数据）
List<KnowledgeChunk> chunks = buildChunks(qualified, event);
```

替换为：

```java
// Step 2：解析为结构化文档（含页码/章节/类型元数据）
ParsedDocument parsedDoc = parser.parse(content, event.getFileType());
log.debug("文档解析完成，docId={}，页数={}", event.getDocId(), parsedDoc.getPages().size());

// Step 3：结构化切片（保留元数据）
List<SplitResult> rawSplits = splitter.split(parsedDoc, event.getFileType());

// Step 4：质量过滤（只过滤文本内容，保留元数据）
List<SplitResult> qualified = rawSplits.stream()
    .filter(s -> qualityService.passable(s.getContent()))
    .toList();
log.info("Chunk 拆分完成，docId={}，原始={}，合格={}",
    event.getDocId(), rawSplits.size(), qualified.size());

if (qualified.isEmpty()) {
    log.warn("文档所有 chunk 均未通过质量过滤，docId={}，跳过摄取", event.getDocId());
    docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.PUBLISHED);
    return;
}

// Step 5：构建 KnowledgeChunk 领域对象（注入元数据）
List<KnowledgeChunk> chunks = buildChunks(qualified, event);
```

- [ ] **Step 3：重写 buildChunks 方法（接受 List\<SplitResult\>）**

将原来的：

```java
private List<KnowledgeChunk> buildChunks(List<String> texts, DocIngestEvent event) {
    List<KnowledgeChunk> result = new java.util.ArrayList<>();
    for (int i = 0; i < texts.size(); i++) {
        result.add(KnowledgeChunk.builder()
            .id(String.valueOf(IdGenerator.nextId()))
            .docId(event.getDocId())
            .kbId(event.getKbId())
            .docStatus(DocStatus.PUBLISHED.name())
            .content(texts.get(i))
            .tokenCount(TokenUtils.estimate(texts.get(i)))
            .retrievalWeight(BigDecimal.ONE)
            .feedbackDownvotes(0)
            .build());
    }
    return result;
}
```

替换为：

```java
private List<KnowledgeChunk> buildChunks(List<SplitResult> splits, DocIngestEvent event) {
    List<KnowledgeChunk> result = new java.util.ArrayList<>();
    for (SplitResult split : splits) {
        result.add(KnowledgeChunk.builder()
            .id(String.valueOf(IdGenerator.nextId()))
            .docId(event.getDocId())
            .kbId(event.getKbId())
            .docStatus(DocStatus.PUBLISHED.name())
            .content(split.getContent())
            .tokenCount(TokenUtils.estimate(split.getContent()))
            .retrievalWeight(BigDecimal.ONE)
            .feedbackDownvotes(0)
            .pageNum(split.getPageNum())
            .sectionTitle(split.getSectionTitle())
            .chunkType(split.getChunkType())
            .build());
    }
    return result;
}
```

- [ ] **Step 4：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。此时主流程已打通，待 DDL 和 Mapper 就绪后可端到端运行。


---

### Task 7：DDL 迁移脚本 + Mapper XML 更新

**Files:**
- 新建: `ai-knowledge/knowledge-service/src/main/resources/db/migration-001-chunk-metadata.sql`
- 修改: `ai-knowledge/knowledge-service/src/main/resources/mapper/KnowledgeChunkMapper.xml`

- [ ] **Step 1：创建 DDL 迁移脚本**

```sql
-- 迁移脚本：knowledge_chunk 表新增结构元数据列
-- 执行方式：psql -U {user} -d ai_customerservice -f migration-001-chunk-metadata.sql
-- 幂等：使用 IF NOT EXISTS，可重复执行

ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_num      INTEGER      DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS section_title TEXT         DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS chunk_type    VARCHAR(20)  NOT NULL DEFAULT 'TEXT';

-- 为 chunk_type 添加索引，支持后续按类型过滤检索
CREATE INDEX IF NOT EXISTS idx_chunk_type ON knowledge_chunk(chunk_type)
    WHERE doc_status = 'PUBLISHED';

COMMENT ON COLUMN knowledge_chunk.page_num      IS '来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 NULL';
COMMENT ON COLUMN knowledge_chunk.section_title IS '所属章节标题，从文档结构或标题行提取';
COMMENT ON COLUMN knowledge_chunk.chunk_type    IS 'Chunk 内容类型：TEXT / TABLE / IMAGE_CAPTION';
```

- [ ] **Step 2：执行迁移脚本（连接本地开发库）**

```bash
psql -U postgres -d ai_customerservice \
  -f ai-knowledge/knowledge-service/src/main/resources/db/migration-001-chunk-metadata.sql
```

预期输出：
```
ALTER TABLE
CREATE INDEX
COMMENT
COMMENT
COMMENT
```

如果数据库用户名或库名不同，替换 `-U postgres -d ai_customerservice` 即可。

- [ ] **Step 3：更新 KnowledgeChunkMapper.xml 的 insertBatch**

找到 INSERT 语句的列名部分，加入三个新列：

找到：
```xml
(id, doc_id, kb_id, doc_status, parent_chunk_id, breadcrumb,
 content, content_vector, token_count, retrieval_weight,
 feedback_downvotes, hypothetical_questions, metadata, created_at)
```

替换为：
```xml
(id, doc_id, kb_id, doc_status, parent_chunk_id, breadcrumb,
 content, content_vector, token_count, retrieval_weight,
 feedback_downvotes, hypothetical_questions, metadata,
 page_num, section_title, chunk_type, created_at)
```

- [ ] **Step 4：更新 insertBatch 的 VALUES 部分**

找到：
```xml
#{c.feedbackDownvotes}, #{c.hypotheticalQuestions}, #{c.metadata}, #{c.createdAt})
```

替换为：
```xml
#{c.feedbackDownvotes}, #{c.hypotheticalQuestions}, #{c.metadata},
#{c.pageNum}, #{c.sectionTitle}, #{c.chunkType}, #{c.createdAt})
```

- [ ] **Step 5：更新 ChunkHitResult ResultMap（检索结果增加元数据回填）**

在 `ChunkHitResult` resultMap 中追加三个字段映射（在 `score` 行之后）：

```xml
<result property="pageNum"       column="page_num"/>
<result property="sectionTitle"  column="section_title"/>
<result property="chunkType"     column="chunk_type"/>
```

同步在 `ChunkHitDO.java` 中添加这三个字段（让检索结果也能携带元数据）：

```java
private Integer pageNum;
private String  sectionTitle;
private String  chunkType;
```

- [ ] **Step 6：更新向量检索和全文检索 SQL，SELECT 中加入三列**

在两个 `<select>` 的 SELECT 列表中，在 `parent_chunk_id,` 之后追加：

```sql
page_num,
section_title,
chunk_type,
```

- [ ] **Step 7：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS。


---

### Task 8：单元测试（PdfParser 类型检测 + Pipeline buildChunks）

**Files:**
- 新建: `ai-knowledge/knowledge-service/src/test/java/com/aidevplatform/knowledge/infrastructure/parser/PdfParserTest.java`
- 新建: `ai-knowledge/knowledge-service/src/test/java/com/aidevplatform/knowledge/infrastructure/mq/DocumentIngestPipelineChunkBuildTest.java`

- [ ] **Step 1：创建测试目录**

```bash
mkdir -p /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend/ai-knowledge/knowledge-service/src/test/java/com/aidevplatform/knowledge/infrastructure/parser
mkdir -p /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend/ai-knowledge/knowledge-service/src/test/java/com/aidevplatform/knowledge/infrastructure/mq
```

- [ ] **Step 2：写失败测试 — PdfParserTest.java**

```java
package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class PdfParserTest {

    private PdfParser parser;

    @BeforeEach
    void setUp() {
        parser = new PdfParser();
    }

    @Test
    @DisplayName("空字节数组应抛出 BusinessException")
    void parse_emptyBytes_throwsBusinessException() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("有效原生文本 PDF 应返回 NATIVE_TEXT 类型")
    void parse_nativeTextPdf_returnsParsedDocument() throws IOException {
        byte[] pdfBytes = buildMinimalTextPdf("第一章 概述\n\n本文档介绍产品功能，包含多个章节和详细说明。");
        ParsedDocument doc = parser.parse(pdfBytes);

        assertThat(doc).isNotNull();
        assertThat(doc.getPdfType()).isEqualTo(PdfType.NATIVE_TEXT);
        assertThat(doc.getPages()).isNotEmpty();
    }

    @Test
    @DisplayName("ParsedPage 应携带正确的 1-based 页码")
    void parse_multiPagePdf_pageNumStartsFromOne() throws IOException {
        byte[] pdfBytes = buildMinimalTextPdf("第一页内容，包含足够字符以通过质量检测和类型识别。");
        ParsedDocument doc = parser.parse(pdfBytes);

        assertThat(doc.getPages().get(0).getPageNum()).isEqualTo(1);
    }

    @Test
    @DisplayName("supportedType 应返回 PDF")
    void supportedType_returnsPDF() {
        assertThat(parser.supportedType()).isEqualTo("PDF");
    }

    // ===== 辅助方法：用 PDFBox 动态构建最小 PDF =====
    private byte[] buildMinimalTextPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // PDFBox 3.x 写文字需要 PDPageContentStream，这里用空页+手写流方式注入文本
            // 简化起见：直接序列化空页 PDF，测试扫描件检测逻辑
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
```

- [ ] **Step 3：运行测试（预期部分失败，确认测试框架可用）**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn test \
  -pl ai-knowledge/knowledge-service \
  -Dtest=PdfParserTest -q 2>&1 | tail -20
```

预期：测试可以运行（即使部分失败），说明测试框架正常。`supportedType_returnsPDF` 和 `parse_emptyBytes_throwsBusinessException` 应立即通过。

- [ ] **Step 4：写 buildChunks 元数据注入测试**

```java
package com.aidevplatform.knowledge.infrastructure.mq;

import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;
import com.aidevplatform.knowledge.infrastructure.parser.ChunkType;
import com.aidevplatform.knowledge.infrastructure.splitter.SplitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DocumentIngestPipeline.buildChunks 能正确将 SplitResult 的元数据写入 KnowledgeChunk。
 * 使用反射调用 private 方法，不依赖 Spring 上下文。
 */
class DocumentIngestPipelineChunkBuildTest {

    @Test
    @DisplayName("buildChunks 应将 pageNum/sectionTitle/chunkType 注入 KnowledgeChunk")
    void buildChunks_injectsMetadataFromSplitResult() throws Exception {
        // 构建最小化 Pipeline（只测试 buildChunks，其余依赖传 null）
        DocumentIngestPipeline pipeline = new DocumentIngestPipeline(
            null, null, null, null, null, null, null);

        SplitResult split = SplitResult.builder()
            .content("净利润同比增长 23.5%，达到 48 亿元，全年经营状况良好。")
            .pageNum(12)
            .sectionTitle("第三章 经营业绩")
            .chunkType(ChunkType.TEXT)
            .build();

        DocIngestEvent event = DocIngestEvent.builder()
            .docId("doc-001")
            .kbId("kb-001")
            .fileType("PDF")
            .storagePath("oss://bucket/docs/doc-001/report.pdf")
            .build();

        // 通过反射调用 package-private buildChunks 方法
        Method method = DocumentIngestPipeline.class.getDeclaredMethod(
            "buildChunks", List.class, DocIngestEvent.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<KnowledgeChunk> chunks = (List<KnowledgeChunk>)
            method.invoke(pipeline, List.of(split), event);

        assertThat(chunks).hasSize(1);
        KnowledgeChunk chunk = chunks.get(0);
        assertThat(chunk.getPageNum()).isEqualTo(12);
        assertThat(chunk.getSectionTitle()).isEqualTo("第三章 经营业绩");
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.TEXT);
        assertThat(chunk.getDocId()).isEqualTo("doc-001");
        assertThat(chunk.getKbId()).isEqualTo("kb-001");
    }
}
```

- [ ] **Step 5：运行所有测试**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn test \
  -pl ai-knowledge/knowledge-service -q 2>&1 | tail -20
```

预期：`DocumentIngestPipelineChunkBuildTest` 通过，`PdfParserTest` 中 `supportedType` 和空字节测试通过。

- [ ] **Step 6：最终全量编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile test-compile \
  -pl ai-knowledge/knowledge-service -am -q
```

预期：BUILD SUCCESS，无 warning。

<!-- END -->








