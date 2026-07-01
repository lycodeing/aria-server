package com.aria.sdk.knowledge;

import com.aria.common.sdk.BaseClient;
import com.aria.common.sdk.ClientConfig;
import com.aria.sdk.knowledge.dto.SearchRequest;
import com.aria.sdk.knowledge.dto.SearchResponse;

import java.time.Duration;

/**
 * knowledge-service SDK 客户端。
 * 供 conversation-service 调用内部检索接口，复用 common-sdk 的 AK/SK 签名和重试机制。
 * 与平台其他 SDK（CodeClient、PipelineClient）风格完全一致。
 *
 * <p>使用示例：
 * <pre>
 * // 手动构建
 * KnowledgeClient client = KnowledgeClient.builder()
 *     .baseUrl("http://knowledge-service:8081")
 *     .accessKey(ak, sk)
 *     .build();
 *
 * // Spring Boot 自动装配（引入 knowledge-sdk 依赖 + 配置 application.yml 即可）
 * {@literal @}Autowired KnowledgeClient knowledgeClient;
 *
 * // 调用混合检索
 * SearchResponse result = client.search(SearchRequest.of("如何退款", "kb_001", 5));
 * </pre>
 */
public class KnowledgeClient extends BaseClient {

    private KnowledgeClient(ClientConfig config) {
        super(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 混合检索（BM25 + 向量双路召回 + RRF 融合）。
     * 对应内部接口：POST /internal/knowledge/search
     *
     * @param request 检索请求（含查询文本、知识库 ID、topK）
     * @return 按相关性降序排列的 chunk 命中列表
     */
    public SearchResponse search(SearchRequest request) {
        return post("/internal/knowledge/search", request, SearchResponse.class);
    }

    /**
     * BGE-Reranker 重排序（可选，对 search 结果精排）。
     * 对应内部接口：POST /internal/knowledge/rerank
     *
     * @param request 重排请求（含查询文本和待排序 chunk 列表）
     * @return 精排后的 chunk 列表
     */
    public SearchResponse rerank(SearchRequest request) {
        return post("/internal/knowledge/rerank", request, SearchResponse.class);
    }

    // ===== Builder =====

    public static class Builder {
        private final ClientConfig.Builder configBuilder = ClientConfig.builder();

        public Builder baseUrl(String url) {
            configBuilder.baseUrl(url);
            return this;
        }

        public Builder accessKey(String ak, String sk) {
            configBuilder.accessKey(ak, sk);
            return this;
        }

        public Builder connectTimeout(Duration d) {
            configBuilder.connectTimeout(d);
            return this;
        }

        public Builder readTimeout(Duration d) {
            configBuilder.readTimeout(d);
            return this;
        }

        public Builder maxRetries(int n) {
            configBuilder.maxRetries(n);
            return this;
        }

        public KnowledgeClient build() {
            return new KnowledgeClient(configBuilder.build());
        }
    }
}
