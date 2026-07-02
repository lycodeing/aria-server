package com.aria.conversation.infrastructure.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * knowledge-service /internal/knowledge/search 接口响应 DTO。
 *
 * <p>响应格式对齐 {@code R<T>}（code=200 表示成功），
 * 与 InternalKnowledgeController 改用 R<T> 包装后保持一致。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeSearchResult {

    /** 响应状态码，200 表示成功（对齐 R.ok() 的 SUCCESS_CODE） */
    private int code;
    private String msg;
    private SearchData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchData {
        private List<Hit> hits = Collections.emptyList();
        private int totalFound;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hit {
        private String chunkId;
        private String docId;
        /** chunk 正文内容 */
        private String content;
        /** 面包屑路径（来源文档章节标题），用于前端溯源展示 */
        private String breadcrumb;
        /** 相关性分数，值越高表示与查询越相关 */
        private double score;
        /** 来源标识，如 VECTOR / TEXT */
        private String source;
    }

    /** 安全获取 hits，接口异常时返回空列表 */
    public List<Hit> hits() {
        if (data == null || data.getHits() == null) return Collections.emptyList();
        return Collections.unmodifiableList(data.getHits());
    }
}
