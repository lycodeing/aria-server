package com.aria.knowledge.infrastructure.embedding;

import com.aria.knowledge.domain.model.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Embedding 服务 Stub 实现（默认降级，BGE-M3 服务不可用时使用）。
 *
 * <p>当未配置真实 EmbeddingService 实现时，此 Stub 自动生效。
 * 生成随机单位向量（非全零），使 pgvector cosine 距离计算不产生 NaN。
 * 全零向量在余弦计算中分母为 0，导致所有 score=NaN，检索结果无法排序。
 *
 * <p>生产环境必须替换为真实的 BGE-M3 实现（配置 bean name = "realEmbeddingService"）。
 */
@Slf4j
@Service
// I-10：加 @Profile("!prod") 防止生产环境意外激活 Stub（配置缺失时随机向量不可用于生产检索）
@Profile("!prod")
@ConditionalOnMissingBean(name = "realEmbeddingService")
public class StubEmbeddingService implements EmbeddingService {

    @jakarta.annotation.PostConstruct
    public void warnOnActivation() {
        log.warn("【STUB WARNING】StubEmbeddingService 已激活！生产环境必须配置 realEmbeddingService！");
    }

    /** BGE-M3 输出维度 */
    private static final int VECTOR_DIM = 1024;

    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        log.warn("【Stub】EmbeddingService 未配置真实实现，使用随机单位向量，共 {} 个 chunk", chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            chunk.setVector(randomUnitVector());
        }
    }

    @Override
    public float[] encode(String text) {
        log.warn("【Stub】EmbeddingService 未配置真实实现，返回随机单位向量，text={}", text);
        return randomUnitVector();
    }

    /**
     * 生成 L2 归一化的随机单位向量。
     * <p>避免全零向量导致 pgvector cosine 距离计算时分母为 0（score=NaN）。
     */
    private static float[] randomUnitVector() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float[] v = new float[VECTOR_DIM];
        double norm = 0.0;
        for (int i = 0; i < VECTOR_DIM; i++) {
            v[i] = (float) (rng.nextGaussian());  // 标准正态分布，均匀分布在超球面
            norm += (double) v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < VECTOR_DIM; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }
}

