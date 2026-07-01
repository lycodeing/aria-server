package com.aria.knowledge.infrastructure.persistence.entity;

import com.aria.knowledge.infrastructure.persistence.typehandler.PgVectorTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Chunk 数据库对象（基础设施层）。
 * contentVector 以 pgvector 字符串格式 [0.1,0.2,...] 存储，由 VectorUtils 互转。
 * kb_id / doc_status 为冗余字段，避免检索时 JOIN knowledge_doc。
 *
 * <p>{@code autoResultMap=true} 是让 mybatis-plus 在自动生成的 ResultMap 中应用字段级
 * typeHandler 的前提，缺它会导致 {@link PgVectorTypeHandler} 不生效，写入退回 String
 * 触发 Bug-P0-016：{@code column "content_vector" is of type vector but expression is of type character varying}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "knowledge_chunk", autoResultMap = true)
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
    /**
     * pgvector 格式字符串 [0.1,0.2,...]，由 VectorUtils.toStr()/fromStr() 转换。
     * 通过 {@link PgVectorTypeHandler} 包装为 PGobject(type=vector) 写入，
     * 否则 JDBC 推断为 character varying 与 vector(1024) 列不兼容。
     */
    @TableField(typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String     contentVector;
    private Integer    tokenCount;
    private BigDecimal retrievalWeight;
    private Integer    feedbackDownvotes;
    /** JSON 数组字符串，存储假设性问题列表 */
    private String     hypotheticalQuestions;
    /** JSON 字符串，存储 source_url、doc_version 等元数据 */
    private String     metadata;
    /** 来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 null */
    private Integer    pageNum;
    /** 所属章节标题，从文档结构或标题行提取 */
    private String     sectionTitle;
    /** Chunk 内容类型字符串：TEXT / TABLE / IMAGE_CAPTION */
    private String     chunkType;

    @TableField(fill = FieldFill.INSERT)
    private Instant    createdAt;
}
