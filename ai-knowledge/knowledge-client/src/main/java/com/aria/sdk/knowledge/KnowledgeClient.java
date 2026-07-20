package com.aria.sdk.knowledge;

import com.aria.common.sdk.BaseClient;
import com.aria.common.sdk.ClientConfig;
import com.aria.common.sdk.TypeRef;
import com.aria.common.sdk.exception.SdkException;
import com.aria.sdk.knowledge.dto.SearchRequest;
import com.aria.sdk.knowledge.dto.SearchResponse;
import com.aria.sdk.knowledge.exception.KnowledgeClientException;
import com.aria.sdk.knowledge.internal.ApiResponse;

import java.time.Duration;

/**
 * knowledge-service 内网接口 SDK 门面。
 *
 * <p>封装 {@code /internal/knowledge/search} 与 {@code /internal/knowledge/rerank}
 * 的 HTTP 协议细节，包括 AK/SK 签名或 X-Internal-Secret 鉴权、URL 拼接、
 * {@code R<T>} 响应包装解析等，让上层只面向业务方法编程。
 *
 * <p>使用示例：
 * <pre>
 * // 手动构建（AK/SK 模式）
 * KnowledgeClient client = KnowledgeClient.builder()
 *     .baseUrl("http://knowledge-service:8081")
 *     .accessKey(ak, sk)
 *     .build();
 *
 * // 手动构建（SharedSecret 模式）
 * KnowledgeClient client = KnowledgeClient.builder()
 *     .baseUrl("http://knowledge-service:8081")
 *     .sharedSecret(System.getenv("ARIA_INTERNAL_SECRET"))
 *     .build();
 *
 * // Spring Boot 自动装配：application.yml 中配置 knowledge.client.* 即可
 * {@literal @}Autowired KnowledgeClient knowledgeClient;
 *
 * SearchResponse result = client.search(SearchRequest.of("如何退款", "kb_001", 5));
 * </pre>
 *
 * @author lycodeing
 * @since 2026-07
 */
public class KnowledgeClient extends BaseClient {

    private KnowledgeClient(ClientConfig config) {
        super(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- 知识库检索 ----

    /**
     * 混合检索（BM25 + 向量双路召回 + RRF 融合）。
     * 对应内部接口：POST /internal/knowledge/search
     *
     * @param request 检索请求（含查询文本、知识库 ID、topK），不能为 null
     * @return 按相关性降序排列的 chunk 命中列表
     * @throws KnowledgeClientException 当 HTTP 非 2xx 或服务端返回业务错误码时
     */
    public SearchResponse search(SearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SearchRequest 不能为空");
        }
        ApiResponse<SearchResponse> resp = doPost(
                "/internal/knowledge/search",
                request,
                new TypeRef<ApiResponse<SearchResponse>>() {},
                "知识库检索失败");
        return unwrap(resp, "知识库检索失败");
    }

    /**
     * BGE-Reranker 重排序（可选，对 search 结果精排）。
     * 对应内部接口：POST /internal/knowledge/rerank
     *
     * @param request 重排请求（含查询文本和待排序 chunk 列表），不能为 null
     * @return 精排后的 chunk 列表
     * @throws KnowledgeClientException 当 HTTP 非 2xx 或服务端返回业务错误码时
     */
    public SearchResponse rerank(SearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SearchRequest 不能为空");
        }
        ApiResponse<SearchResponse> resp = doPost(
                "/internal/knowledge/rerank",
                request,
                new TypeRef<ApiResponse<SearchResponse>>() {},
                "知识库重排序失败");
        return unwrap(resp, "知识库重排序失败");
    }

    // ---- 内部工具 ----

    private <T> ApiResponse<T> doPost(String path, Object body,
                                      TypeRef<ApiResponse<T>> ref, String errPrefix) {
        try {
            return post(path, body, ref);
        } catch (SdkException e) {
            throw wrapHttpFailure(e, errPrefix);
        }
    }

    /**
     * 将底层 {@link SdkException} 包装为 {@link KnowledgeClientException} 并透传 HTTP 状态码，
     * 避免上层为了拿状态码去遍历异常链。
     */
    private KnowledgeClientException wrapHttpFailure(SdkException e, String errPrefix) {
        int httpStatus = e.getStatusCode() > 0 ? e.getStatusCode() : KnowledgeClientException.UNKNOWN_CODE;
        return new KnowledgeClientException(
                errPrefix + ": " + e.getMessage(),
                httpStatus,
                KnowledgeClientException.UNKNOWN_CODE,
                e);
    }

    private <T> T unwrap(ApiResponse<T> resp, String errPrefix) {
        if (resp == null) {
            throw new KnowledgeClientException(errPrefix + ": 服务端返回空响应体");
        }
        if (!resp.isSuccess()) {
            int bizCode = resp.code() != null ? resp.code() : KnowledgeClientException.UNKNOWN_CODE;
            throw new KnowledgeClientException(
                    errPrefix + ": code=" + resp.code() + " msg=" + resp.msg(),
                    KnowledgeClientException.UNKNOWN_CODE,
                    bizCode,
                    null);
        }
        if (resp.data() == null) {
            throw new KnowledgeClientException(errPrefix + ": 服务端返回 data 为空");
        }
        return resp.data();
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

        public Builder sharedSecret(String secret) {
            configBuilder.sharedSecret(secret);
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

        public Builder callTimeout(Duration d) {
            configBuilder.callTimeout(d);
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