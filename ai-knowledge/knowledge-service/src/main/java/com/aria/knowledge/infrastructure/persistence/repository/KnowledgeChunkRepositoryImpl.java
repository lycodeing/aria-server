package com.aria.knowledge.infrastructure.persistence.repository;

import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.persistence.assembler.KnowledgeChunkAssembler;
import com.aria.knowledge.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.aria.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import com.aria.common.core.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Chunk Repository 实现（基础设施层）。
 * 向量检索和全文检索 SQL 委托给 KnowledgeChunkMapper.xml（CDATA 包裹）。
 * MyBatis-Plus 标准 CRUD 通过 BaseMapper 提供。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeChunkRepositoryImpl implements KnowledgeChunkRepository {

    private final KnowledgeChunkMapper    chunkMapper;
    private final KnowledgeChunkAssembler assembler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        // I-02：改为批量 INSERT（单条 SQL 多 VALUES），避免 N 次单行 INSERT 的性能瓶颈。
        // 每批最多 64 条，防止 SQL 过长超出数据库限制。
        int batchSize = 64;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<KnowledgeChunkEntity> batch = chunks
                .subList(i, Math.min(i + batchSize, chunks.size()))
                .stream()
                .map(assembler::toEntity)
                .toList();
            chunkMapper.insertBatch(batch);
        }
        log.info("Chunk 批量写入完成，总数={}", chunks.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        int deleted = chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
            .eq(KnowledgeChunkEntity::getDocId, docId));
        log.info("Chunk 删除完成，docId={}，删除数={}", docId, deleted);
    }

    @Override
    public List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId) {
        String vec = VectorUtils.toStr(queryVector);
        return chunkMapper.selectByVector(vec, kbId, topK)
            .stream()
            .map(do_ -> assembler.toChunkHit(do_, ChunkHit.HitSource.VECTOR))
            .toList();
    }

    @Override
    public List<ChunkHit> fullTextSearch(String query, int topK, String kbId) {
        return chunkMapper.selectByFullText(query, kbId, topK)
            .stream()
            .map(do_ -> assembler.toChunkHit(do_, ChunkHit.HitSource.FULL_TEXT))
            .toList();
    }

    @Override
    public KnowledgeChunk findById(String chunkId) {
        // I-14：加 null 判断，chunkId 不存在时返回 null 而非向 toDomain 传 null 触发 NPE
        KnowledgeChunkEntity entity = chunkMapper.selectById(chunkId);
        if (entity == null) {
            return null;
        }
        return assembler.toDomain(entity);
    }

    @Override
    public List<KnowledgeChunk> findByDocId(String docId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId)
                .orderByAsc(KnowledgeChunkEntity::getPageNum)
                .orderByAsc(KnowledgeChunkEntity::getId))
            .stream()
            .map(assembler::toDomain)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWeight(String chunkId, java.math.BigDecimal weight) {
        chunkMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getId, chunkId)
                .set(KnowledgeChunkEntity::getRetrievalWeight, weight));
        log.info("Chunk 权重已更新，chunkId={}，weight={}", chunkId, weight);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContent(String chunkId, String content, Integer tokenCount) {
        chunkMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getId, chunkId)
                .set(KnowledgeChunkEntity::getContent, content)
                .set(KnowledgeChunkEntity::getTokenCount, tokenCount));
        log.info("Chunk 内容已更新，chunkId={}", chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVector(String chunkId, String vectorStr) {
        chunkMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getId, chunkId)
                .set(KnowledgeChunkEntity::getContentVector, vectorStr));
        log.info("Chunk 向量已更新，chunkId={}", chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(KnowledgeChunk chunk) {
        chunkMapper.insertBatch(List.of(assembler.toEntity(chunk)));
        log.info("新 Chunk 已保存，chunkId={}", chunk.getId());
    }

    @Override
    public java.util.Map<String, Long> countStatsByKbId(String kbId) {
        long chunkCount = chunkMapper.selectCount(
            new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getKbId, kbId)
                .eq(KnowledgeChunkEntity::getDocStatus, "PUBLISHED")
                .gt(KnowledgeChunkEntity::getRetrievalWeight, 0));
        Long tokenSum = chunkMapper.selectTokenSumByKbId(kbId);
        return java.util.Map.of(
            "chunkCount", chunkCount,
            "tokenSum",   tokenSum != null ? tokenSum : 0L
        );
    }
}
