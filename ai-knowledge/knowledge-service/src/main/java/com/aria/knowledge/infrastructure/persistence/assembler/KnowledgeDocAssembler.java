package com.aria.knowledge.infrastructure.persistence.assembler;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.infrastructure.persistence.entity.KnowledgeDocEntity;
import org.springframework.stereotype.Component;

/**
 * 文档对象转换器（基础设施层）。
 * 职责：KnowledgeDocEntity ↔ KnowledgeDoc 双向转换。
 */
@Component
public class KnowledgeDocAssembler {

    /** Entity → 领域对象 */
    public KnowledgeDoc toDomain(KnowledgeDocEntity e) {
        if (e == null) return null;
        return KnowledgeDoc.builder()
            .id(e.getId())
            .kbId(e.getKbId())
            .fileName(e.getFileName())
            .fileType(e.getFileType())
            .storagePath(e.getStoragePath())
            .contentHash(e.getContentHash())
            .status(DocStatus.valueOf(e.getStatus()))
            .version(e.getVersion())
            .effectiveFrom(e.getEffectiveFrom())
            .expiresAt(e.getExpiresAt())
            .uploaderId(e.getUploaderId())
            .reviewerId(e.getReviewerId())
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .build();
    }

    /** 领域对象 → Entity */
    public KnowledgeDocEntity toEntity(KnowledgeDoc d) {
        if (d == null) return null;
        return KnowledgeDocEntity.builder()
            .id(d.getId())
            .kbId(d.getKbId())
            .fileName(d.getFileName())
            .fileType(d.getFileType())
            .storagePath(d.getStoragePath())
            .contentHash(d.getContentHash() != null ? d.getContentHash() : "pending")
            .status(d.getStatus() != null ? d.getStatus().name() : DocStatus.DRAFT.name())
            .version(d.getVersion())
            .effectiveFrom(d.getEffectiveFrom())
            .expiresAt(d.getExpiresAt())
            .uploaderId(d.getUploaderId())
            .reviewerId(d.getReviewerId())
            .build();
    }
}
