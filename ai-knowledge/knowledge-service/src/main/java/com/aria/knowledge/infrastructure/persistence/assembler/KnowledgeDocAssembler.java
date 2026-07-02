package com.aria.knowledge.infrastructure.persistence.assembler;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.infrastructure.persistence.entity.KnowledgeDocEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文档对象转换器（基础设施层）。
 * 职责：KnowledgeDocEntity ↔ KnowledgeDoc 双向转换。
 */
@Slf4j
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
            .status(safeParseStatus(e.getStatus()))
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
            // createdAt/updatedAt 由 MyBatis-Plus AutoFill 自动处理，不在此映射
            .build();
    }

    /**
     * 容错解析 DocStatus，DB 中存在历史脏数据或枚举改名时降级为 DRAFT，
     * 避免 valueOf 抛 IllegalArgumentException 导致整批查询失败。
     */
    private DocStatus safeParseStatus(String raw) {
        if (raw == null) return DocStatus.DRAFT;
        try {
            return DocStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("[Assembler] 未知 DocStatus={}，降级为 DRAFT", raw);
            return DocStatus.DRAFT;
        }
    }
}
