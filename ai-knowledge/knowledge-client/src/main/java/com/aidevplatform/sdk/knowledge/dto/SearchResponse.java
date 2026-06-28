package com.aidevplatform.sdk.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 知识库检索响应 DTO。
 */
@Data
@Builder
public class SearchResponse {
    private List<ChunkHitDTO> hits;
    private int               totalFound;
    private long              retrievalMs;
}
