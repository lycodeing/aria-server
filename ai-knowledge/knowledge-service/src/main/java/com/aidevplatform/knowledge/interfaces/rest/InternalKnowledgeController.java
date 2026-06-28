package com.aidevplatform.knowledge.interfaces.rest;

import com.aidevplatform.knowledge.application.service.KnowledgeSearchAppService;
import com.aidevplatform.knowledge.domain.model.ChunkHit;
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
 * 通过 AK/SK HMAC 签名鉴权（同 code-service 内部接口规范）。
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
    public Map<String, Object> search(@RequestBody @Valid SearchRequest request) {
        List<ChunkHit> hits = searchAppService.hybridSearch(
            request.getQuery(), request.getKbId(), request.getTopK());
        return Map.of(
            "code", 0,
            "data", Map.of(
                "hits", hits.stream().map(this::toDto).toList(),
                "totalFound", hits.size()
            )
        );
    }

    /**
     * BGE-Reranker 重排序接口（conversation-service 可选调用）。
     */
    @PostMapping("/rerank")
    public Map<String, Object> rerank(@RequestBody @Valid RerankRequest request) {
        // 当前直接返回，实现阶段接入 BGE-Reranker
        List<ChunkHit> candidates = request.getChunks().stream()
            .map(dto -> ChunkHit.builder()
                .chunkId(dto.getChunkId())
                .content(dto.getContent())
                .score(dto.getScore())
                .build())
            .toList();
        List<ChunkHit> reranked = searchAppService.rerank(request.getQuery(), candidates);
        return Map.of("code", 0, "data", Map.of("hits", reranked.stream().map(this::toDto).toList()));
    }

    private Map<String, Object> toDto(ChunkHit hit) {
        return Map.of(
            "chunkId",       hit.getChunkId(),
            "docId",         hit.getDocId() != null ? hit.getDocId() : "",
            "content",       hit.getContent(),
            "breadcrumb",    hit.getBreadcrumb() != null ? hit.getBreadcrumb() : "",
            "parentChunkId", hit.getParentChunkId() != null ? hit.getParentChunkId() : "",
            "score",         hit.getScore(),
            "source",        hit.getSource() != null ? hit.getSource().name() : "VECTOR"
        );
    }

    // ===== 内部 DTO =====

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
