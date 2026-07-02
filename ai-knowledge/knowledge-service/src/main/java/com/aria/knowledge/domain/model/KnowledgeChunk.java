package com.aria.knowledge.domain.model;

// TODO(Phase-2): ChunkType 当前位于 infrastructure.parser 包，违反 DDD 分层原则（domain 不应依赖 infra）。
// 计划将 ChunkType 迁移到 domain.model 包，同步更新 PdfParser/SplitResult 等 infra 层的引用。
import com.aria.knowledge.infrastructure.parser.ChunkType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 知识库 Chunk 领域实体（无框架依赖）。
 * Parent-Child 架构：小 chunk 用于检索（精确），父 chunk 用于生成（上下文完整）。
 */
@Data
@Builder
public class KnowledgeChunk {
    private String     id;
    private String     docId;
    /** 冗余字段，来自 knowledge_doc.kb_id，避免检索时 JOIN */
    private String     kbId;
    /** 冗余字段，与 knowledge_doc.status 同步 */
    private String     docStatus;
    /** 父 chunk ID，null 表示顶层 chunk */
    private String     parentChunkId;
    /** 面包屑路径，如：产品手册 > 快速开始 > 安装配置 */
    private String     breadcrumb;
    private String     content;
    /** BGE-M3 生成的 1024 维 embedding */
    private float[]    vector;
    private Integer    tokenCount;
    /** 检索权重 0~1.0，被反馈踩多次时下调 */
    private BigDecimal retrievalWeight;
    private Integer    feedbackDownvotes;
    /** LLM 生成的假设性问题列表，用于 HyDE 检索增强 */
    private List<String> hypotheticalQuestions;
    private Instant    createdAt;
    /** 来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 null */
    private Integer    pageNum;
    /** 所属章节标题，从文档结构或标题行提取，无法检测时为 null */
    private String     sectionTitle;
    /** Chunk 内容类型（TEXT / TABLE / IMAGE_CAPTION） */
    private ChunkType  chunkType;
}
