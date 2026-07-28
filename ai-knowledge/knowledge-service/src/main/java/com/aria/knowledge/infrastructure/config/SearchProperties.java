package com.aria.knowledge.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库检索参数配置。
 *
 * <p>将"召回阶段数量（recallK）"与"最终返回数量（topK）"解耦，允许独立调优：
 * <ul>
 *   <li>{@link #recallKVector} — 向量 ANN 召回数量，独立于 topK</li>
 *   <li>{@link #recallKText}   — BM25 全文召回数量，独立于 topK</li>
 *   <li>{@link #rerankerCandidateLimit} — RRF 融合后送 Reranker 的候选上限</li>
 *   <li>{@link #rrfK}          — RRF 平滑系数，小值让头部排名更集中</li>
 * </ul>
 *
 * <p>参数选型依据：
 * <ul>
 *   <li>BAAI/FlagEmbedding 官方建议送入 BGE-Reranker-v2-M3 的候选数为 100~200 条</li>
 *   <li>RRF K=40 在 topK=5~20 的小 topK 场景比默认 K=60 头部区分度更好</li>
 *   <li>BM25 召回略多于向量（6:4），反映中文客服场景的字面匹配需求</li>
 * </ul>
 *
 * @author lycodeing
 * @since 2026-07
 */
@Validated
@ConfigurationProperties(prefix = "knowledge.search")
public record SearchProperties(

        /**
         * PostgreSQL 全文检索分词配置：{@code simple}（内置）或 {@code jieba}（pg_jieba，中文优化）。
         * 生产环境通过 application-prod.yml 覆盖为 {@code jieba}。
         */
        @NotBlank
        @DefaultValue("simple")
        String ftsConfig,

        /**
         * 向量 ANN 检索召回数量，独立于最终 topK。
         * 默认 80，知识库大、多样性查询时可调大。
         */
        @Min(10)
        @Max(500)
        @DefaultValue("80")
        int recallKVector,

        /**
         * BM25 全文检索召回数量，独立于最终 topK。
         * 默认 100，精确匹配需求多（产品编号、错误代码等）时可调大。
         */
        @Min(10)
        @Max(500)
        @DefaultValue("100")
        int recallKText,

        /**
         * RRF 融合后送入 Reranker 的候选上限。
         * 默认 200，受 Reranker GPU 算力约束；延迟敏感时可调低至 100。
         */
        @Min(50)
        @Max(500)
        @DefaultValue("200")
        int rerankerCandidateLimit,

        /**
         * RRF 平滑系数 K。
         * 默认 40；topK 大时调大（平滑），topK 小时调小（头部更集中）。
         */
        @Min(1)
        @Max(100)
        @DefaultValue("40")
        int rrfK

) {
}
