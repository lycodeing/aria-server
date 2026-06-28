package com.aidevplatform.knowledge.infrastructure.persistence.repository;

import com.aidevplatform.knowledge.domain.model.ChunkHit;
import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;
import com.aidevplatform.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aidevplatform.knowledge.infrastructure.persistence.assembler.KnowledgeChunkAssembler;
import com.aidevplatform.knowledge.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.aidevplatform.knowledge.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import com.aidevplatform.common.core.util.VectorUtils;
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
        // MyBatis-Plus 批量插入，每次处理 32 条
        int batchSize = 32;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<KnowledgeChunk> batch = chunks.subList(i,
                Math.min(i + batchSize, chunks.size()));
            batch.stream()
                .map(assembler::toEntity)
                .forEach(chunkMapper::insert);
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
        return assembler.toDomain(chunkMapper.selectById(chunkId));
    }
}
