package com.aria.knowledge.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.knowledge.application.service.KnowledgeSearchAppService;
import com.aria.knowledge.domain.model.ChunkHit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内部知识库检索接口（供 conversation-service 通过 knowledge-sdk 调用）。
 * 路径前缀 /internal/**，不在 Swagger 文档中暴露（application.yml 已配置 paths-to-exclude）。
 *
 * <p>响应格式统一使用 {@link R} 包装，与平台其他接口保持一致，
 * knowledge-client SDK 统一解析 R.data 字段。
 */
@RestController
@RequestMapping("/internal/knowledge")
@RequiredArgsConstructor
public class InternalKnowledgeController {

    private final KnowledgeSearchAppService searchAppService;

    /**
     * 混合检索入口（BM25 + 向量双路召回 + RRF 融合）。
     * conversation-service 通过 KnowledgeClient.search() 调用此接口。
     */
    @PostMapping("/search")
    public R<SearchResponse> search(@RequestBody @Valid SearchRequest request) {
        List<ChunkHit> hits = searchAppService.hybridSearch(
            request.getQuery(), request.getKbId(), request.getTopK());
        List<Map<String, Object>> dtos = hits.stream().map(this::toDto).toList();
        return R.ok(new SearchResponse(dtos, hits.size()));
    }

    /**
     * BGE-Reranker 重排序接口（conversation-service 可选调用）。
     * Reranker 服务已配置时走真实精排；未配置时原样返回候选列表。
     */
    @PostMapping("/rerank")
    public R<RerankResponse> rerank(@RequestBody @Valid RerankRequest request) {
        List<ChunkHit> candidates = request.getChunks().stream()
            .map(dto -> ChunkHit.builder()
                .chunkId(dto.getChunkId())
                .content(dto.getContent())
                .score(dto.getScore())
                .build())
            .toList();
        List<ChunkHit> reranked = searchAppService.rerank(request.getQuery(), candidates);
        return R.ok(new RerankResponse(reranked.stream().map(this::toDto).toList()));
    }

    private Map<String, Object> toDto(ChunkHit hit) {
        return Map.of(
            "chunkId",       hit.getChunkId(),
            "docId",         hit.getDocId()         != null ? hit.getDocId()                : "",
            "content",       hit.getContent()        != null ? hit.getContent()              : "",
            "breadcrumb",    hit.getBreadcrumb()     != null ? hit.getBreadcrumb()           : "",
            "parentChunkId", hit.getParentChunkId()  != null ? hit.getParentChunkId()        : "",
            "score",         hit.getScore(),
            "source",        hit.getSource()         != null ? hit.getSource().name()        : "VECTOR"
        );
    }

    // ===== 响应 DTO =====

    public record SearchResponse(List<Map<String, Object>> hits, int totalFound) {}
    public record RerankResponse(List<Map<String, Object>> hits) {}

    // ===== 请求 DTO =====

    @Data
    public static class SearchRequest {
        @NotBlank private String query;
        @NotBlank private String kbId;
        @Min(1) @Max(50) private int topK = 5;
    }

    @Data
    public static class RerankRequest {
        @NotBlank private String query;
        private List<ChunkDto> chunks;
    }

    @Data
    public static class ChunkDto {
        private String chunkId;
        private String content;
        private double score;
    }
}
