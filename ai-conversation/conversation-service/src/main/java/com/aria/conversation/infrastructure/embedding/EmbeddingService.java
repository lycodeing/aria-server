package com.aria.conversation.infrastructure.embedding;

/**
 * Embedding 服务接口（infrastructure 层 Port）。
 *
 * <p>职责：将文本编码为固定维度的向量，供 Tier2 原型匹配和 Tier3 动态 RAG 使用。
 * 实现类通过 LangChain4j 对接 OpenAI 兼容端点。
 */
public interface EmbeddingService {

    /**
     * 向量化单个文本。
     *
     * @param text 待向量化文本
     * @return 1024 维 float 向量（BGE-M3 格式）
     */
    float[] encode(String text);
}
