package com.aria.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 知识库文档数据库对象（基础设施层，不在 domain 层使用）。
 * 使用 VARCHAR(36) 主键（IdType.INPUT），与 DDL 保持一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_doc")
public class KnowledgeDocEntity {

    @TableId(type = IdType.INPUT)
    private String  id;
    private String  kbId;
    private String  fileName;
    private String  fileType;
    private String  storagePath;
    private String  contentHash;
    private String  status;
    private String  version;
    private LocalDate effectiveFrom;
    private LocalDate expiresAt;
    private String  uploaderId;
    private String  reviewerId;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
