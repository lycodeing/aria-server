package com.aria.conversation.interfaces.rest.vo;

/**
 * AI 回复建议 VO。
 *
 * <p>来源分两类：
 * <ul>
 *   <li>{@code KB} — 来自知识库向量检索，内容直接取 chunk 正文，置信度来自检索分数</li>
 *   <li>{@code CONTEXT} — 基于当前对话上下文由 LLM 推理生成，置信度固定 0.7</li>
 * </ul>
 * KB 结果在合并列表中优先排在前面。
 *
 * @param id         建议唯一标识（UUID）
 * @param content    建议回复文本
 * @param confidence 置信度 [0, 1]，KB 来源归一化检索分数，CONTEXT 来源固定 0.7
 * @param source     来源标识：{@code "KB"} 或 {@code "CONTEXT"}
 */
public record ReplySuggestionVO(
        String id,
        String content,
        double confidence,
        String source
) {}
