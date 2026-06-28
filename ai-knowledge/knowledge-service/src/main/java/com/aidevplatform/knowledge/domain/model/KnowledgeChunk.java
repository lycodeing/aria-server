package com.aidevplatform.knowledge.domain.model;

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
}
