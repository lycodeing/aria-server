package com.aidevplatform.sdk.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

/**
 * 知识库检索请求 DTO。
 */
@Data
@Builder
public class SearchRequest {

    /** 检索查询文本（已经过改写） */
    @NotBlank
    private String query;

    /** 目标知识库 ID */
    @NotBlank
    private String kbId;

    /** 返回 top-K 条结果，范围 1~50 */
    @Min(1) @Max(50)
    private int topK;

    public static SearchRequest of(String query, String kbId, int topK) {
        return SearchRequest.builder()
            .query(query)
            .kbId(kbId)
            .topK(topK)
            .build();
    }
}
