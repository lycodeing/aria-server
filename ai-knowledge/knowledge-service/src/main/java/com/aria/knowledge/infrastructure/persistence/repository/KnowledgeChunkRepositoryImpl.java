package com.aria.knowledge.infrastructure.persistence.repository;

import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.persistence.assembler.KnowledgeChunkAssembler;
import com.aria.knowledge.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.aria.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import com.aria.common.core.util.VectorUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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
        if (chunks == null || chunks.isEmpty()) return;
        int batchSize = 64;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<KnowledgeChunkEntity> batch = chunks
                .subList(i, Math.min(i + batchSize, chunks.size()))
                .stream().map(assembler::toEntity).toList();
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
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return;
        int deleted = chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
            .in(KnowledgeChunkEntity::getDocId, docIds));
        log.info("Chunk 批量删除完成，docId 数={}，删除 chunk 数={}", docIds.size(), deleted);
    }

    @Override
    public List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId) {
        String vec = VectorUtils.toStr(queryVector);
        return chunkMapper.selectByVector(vec, kbId, topK)
            .stream().map(do_ -> assembler.toChunkHit(do_, ChunkHit.HitSource.VECTOR)).toList();
    }

    @Override
    public List<ChunkHit> fullTextSearch(String query, int topK, String kbId) {
        return chunkMapper.selectByFullText(query, kbId, topK)
            .stream().map(do_ -> assembler.toChunkHit(do_, ChunkHit.HitSource.FULL_TEXT)).toList();
    }

    @Override
    public KnowledgeChunk findById(String chunkId) {
        KnowledgeChunkEntity entity = chunkMapper.selectById(chunkId);
        return entity == null ? null : assembler.toDomain(entity);
    }

    @Override
    public List<KnowledgeChunk> findByDocId(String docId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId)
                .orderByAsc(KnowledgeChunkEntity::getPageNum)
                .orderByAsc(KnowledgeChunkEntity::getId))
            .stream().map(assembler::toDomain).toList();
    }

    @Override
    public java.util.Map<String, Long> countStatsByDocId(String docId) {
        // 5 次轻量 COUNT 查询替代全量加载内存统计
        var base = new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId);
        long total  = chunkMapper.selectCount(base.clone());
        long tokens = Optional.ofNullable(chunkMapper.selectTokenSumByDocId(docId)).orElse(0L);
        long text   = chunkMapper.selectCount(base.clone()
                .and(w -> w.isNull(KnowledgeChunkEntity::getChunkType)
                           .or().eq(KnowledgeChunkEntity::getChunkType, "TEXT")));
        long table  = chunkMapper.selectCount(base.clone()
                .eq(KnowledgeChunkEntity::getChunkType, "TABLE"));
        long image  = chunkMapper.selectCount(base.clone()
                .eq(KnowledgeChunkEntity::getChunkType, "IMAGE_CAPTION"));
        return java.util.Map.of(
            "totalChunks", total, "totalTokens", tokens,
            "textChunks",  text,  "tableChunks", table, "imageChunks", image);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWeight(String chunkId, java.math.BigDecimal weight) {
        chunkMapper.update(null, new LambdaUpdateWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getId, chunkId)
                .set(KnowledgeChunkEntity::getRetrievalWeight, weight));
        log.info("Chunk 权重已更新，chunkId={}，weight={}", chunkId, weight);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContentAndVector(String chunkId, String content, int tokenCount, String vectorStr) {
        // 单次 UPDATE 同时更新 content、token_count、vector，保证三者原子同步
        chunkMapper.update(null, new LambdaUpdateWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getId, chunkId)
                .set(KnowledgeChunkEntity::getContent,       content)
                .set(KnowledgeChunkEntity::getTokenCount,    tokenCount)
                .set(KnowledgeChunkEntity::getContentVector, vectorStr));
        log.info("Chunk 内容和向量已原子更新，chunkId={}", chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(KnowledgeChunk chunk) {
        chunkMapper.insertBatch(List.of(assembler.toEntity(chunk)));
        log.info("新 Chunk 已保存，chunkId={}", chunk.getId());
    }

    @Override
    public java.util.Map<String, Long> countStatsByKbId(String kbId) {
        long chunkCount = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunkEntity>()
            .eq(KnowledgeChunkEntity::getKbId, kbId)
            .eq(KnowledgeChunkEntity::getDocStatus, "PUBLISHED")
            .gt(KnowledgeChunkEntity::getRetrievalWeight, 0));
        Long tokenSum = chunkMapper.selectTokenSumByKbId(kbId);
        return java.util.Map.of(
            "chunkCount", chunkCount,
            "tokenSum",   tokenSum != null ? tokenSum : 0L);
    }
}
