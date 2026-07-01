package com.aria.knowledge.domain.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 知识库领域实体。
 * 无任何框架依赖（无 @TableName、无 Spring 注解），可在 domain 层自由使用。
 */
@Data
@Builder
public class KnowledgeDoc {
    private String    id;
    private String    kbId;
    private String    fileName;
    /** 文件类型：MARKDOWN / PDF / HTML / DOCX / TICKET */
    private String    fileType;
    private String    storagePath;
    /** SHA-256 内容哈希，变更检测 */
    private String    contentHash;
    private DocStatus status;
    private String    version;
    private LocalDate effectiveFrom;
    /** 过期日期，null=永久有效 */
    private LocalDate expiresAt;
    private String    uploaderId;
    private String    reviewerId;
    private Instant   createdAt;
    private Instant   updatedAt;
}
