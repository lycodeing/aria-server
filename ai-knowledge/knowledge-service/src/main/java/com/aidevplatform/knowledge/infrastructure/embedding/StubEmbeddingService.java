package com.aidevplatform.knowledge.infrastructure.embedding;

import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Embedding 服务 Stub 实现（默认降级，BGE-M3 服务不可用时使用）。
 *
 * <p>当未配置真实 EmbeddingService 实现时，此 Stub 自动生效。
 * 返回全零向量，使服务能正常启动，但向量检索结果无意义。
 * 生产环境必须替换为真实的 BGE-M3 实现。
 *
 * @author aidevplatform
 */
@Slf4j
@Service
@ConditionalOnMissingBean(name = "realEmbeddingService")
public class StubEmbeddingService implements EmbeddingService {

    /** BGE-M3 输出维度 */
    private static final int VECTOR_DIM = 1024;

    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        log.warn("【Stub】EmbeddingService 未配置真实实现，返回全零向量，共 {} 个 chunk", chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            chunk.setVector(new float[VECTOR_DIM]);
        }
    }

    @Override
    public float[] encode(String text) {
        log.warn("【Stub】EmbeddingService 未配置真实实现，返回全零向量，text={}", text);
        return new float[VECTOR_DIM];
    }
}
