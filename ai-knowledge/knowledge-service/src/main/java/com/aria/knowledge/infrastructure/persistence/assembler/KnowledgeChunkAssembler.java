package com.aria.knowledge.infrastructure.persistence.assembler;

import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.model.ChunkType;
import com.aria.knowledge.infrastructure.persistence.do_.ChunkHitDO;
import com.aria.knowledge.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.aria.common.core.util.VectorUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Chunk 对象转换器（基础设施层）。
 * 职责：Entity ↔ Domain Object ↔ ChunkHit 三层转换。
 * 禁止在 domain/application 层直接做字段赋值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeChunkAssembler {

    private final ObjectMapper objectMapper;

    /** Entity → 领域对象（向上传递给 application / domain 层） */
    public KnowledgeChunk toDomain(KnowledgeChunkEntity e) {
        if (e == null) return null;
        return KnowledgeChunk.builder()
            .id(e.getId())
            .docId(e.getDocId())
            .kbId(e.getKbId())
            .docStatus(e.getDocStatus())
            .parentChunkId(e.getParentChunkId())
            .breadcrumb(e.getBreadcrumb())
            .content(e.getContent())
            .vector(e.getContentVector() != null
                ? VectorUtils.fromStr(e.getContentVector()) : null)
            .tokenCount(e.getTokenCount())
            .retrievalWeight(e.getRetrievalWeight())
            .feedbackDownvotes(e.getFeedbackDownvotes())
            .hypotheticalQuestions(parseJsonList(e.getHypotheticalQuestions()))
            .createdAt(e.getCreatedAt())
            .pageNum(e.getPageNum())
            .sectionTitle(e.getSectionTitle())
            // 容错处理：DB 中存在历史脏数据或枚举改名时降级为 TEXT，避免整批查询失败
            .chunkType(safeParseChunkType(e.getChunkType()))
            .build();
    }

    /** 领域对象 → Entity（向下持久化到数据库） */
    public KnowledgeChunkEntity toEntity(KnowledgeChunk d) {
        if (d == null) return null;
        return KnowledgeChunkEntity.builder()
            .id(d.getId())
            .docId(d.getDocId())
            .kbId(d.getKbId())
            // 默认 DRAFT 而非 PUBLISHED：chunk 初始状态应跟随文档状态，
            // 由 StatusUpdateHandler 在 pipeline 完成后统一改为 PUBLISHED
            .docStatus(d.getDocStatus() != null ? d.getDocStatus() : "DRAFT")
            .parentChunkId(d.getParentChunkId())
            .breadcrumb(d.getBreadcrumb())
            .content(d.getContent())
            .contentVector(d.getVector() != null
                ? VectorUtils.toStr(d.getVector()) : null)
            .tokenCount(d.getTokenCount())
            .retrievalWeight(d.getRetrievalWeight() != null
                ? d.getRetrievalWeight() : BigDecimal.ONE)
            .feedbackDownvotes(d.getFeedbackDownvotes() != null
                ? d.getFeedbackDownvotes() : 0)
            .pageNum(d.getPageNum())
            .sectionTitle(d.getSectionTitle())
            .chunkType(d.getChunkType() != null
                ? d.getChunkType().name() : ChunkType.TEXT.name())
            .build();
    }

    /** ChunkHitDO（Mapper 查询结果） → 领域 ChunkHit */
    public ChunkHit toChunkHit(ChunkHitDO do_, ChunkHit.HitSource source) {
        if (do_ == null) return null;
        return ChunkHit.builder()
            .chunkId(do_.getChunkId())
            .docId(do_.getDocId())
            .kbId(do_.getKbId())
            .content(do_.getContent())
            .breadcrumb(do_.getBreadcrumb())
            .parentChunkId(do_.getParentChunkId())
            .score(do_.getScore() != null ? do_.getScore() : 0.0)
            .source(source)
            .pageNum(do_.getPageNum())
            .sectionTitle(do_.getSectionTitle())
            .chunkType(do_.getChunkType())
            .build();
    }

    /**
     * 容错解析 ChunkType 枚举，DB 中存在历史脏数据或枚举值改名时降级为 TEXT，
     * 避免 valueOf 抛 IllegalArgumentException 导致整批查询失败。
     */
    private ChunkType safeParseChunkType(String raw) {
        if (raw == null) return ChunkType.TEXT;
        try {
            return ChunkType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("[Assembler] 未知 chunkType={}，降级为 TEXT", raw);
            return ChunkType.TEXT;
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 hypotheticalQuestions JSON 失败，原始值={}", json);
            return Collections.emptyList();
        }
    }
}
