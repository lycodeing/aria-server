package com.aidevplatform.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Chunk 数据库对象（基础设施层）。
 * contentVector 以 pgvector 字符串格式 [0.1,0.2,...] 存储，由 VectorUtils 互转。
 * kb_id / doc_status 为冗余字段，避免检索时 JOIN knowledge_doc。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId(type = IdType.INPUT)
    private String     id;
    private String     docId;
    /** 冗余字段，来自 knowledge_doc.kb_id */
    private String     kbId;
    /** 冗余字段，与 knowledge_doc.status 同步，文档状态变更时一并更新 */
    private String     docStatus;
    private String     parentChunkId;
    private String     breadcrumb;
    private String     content;
    /** pgvector 格式字符串 [0.1,0.2,...]，由 VectorUtils.toStr()/fromStr() 转换 */
    private String     contentVector;
    private Integer    tokenCount;
    private BigDecimal retrievalWeight;
    private Integer    feedbackDownvotes;
    /** JSON 数组字符串，存储假设性问题列表 */
    private String     hypotheticalQuestions;
    /** JSON 字符串，存储 source_url、doc_version 等元数据 */
    private String     metadata;

    @TableField(fill = FieldFill.INSERT)
    private Instant    createdAt;
}
