package com.aria.conversation.application.dto;

/**
 * AI 回复建议应用层 DTO。
 *
 * <p>由 {@link com.aria.conversation.application.service.ReplySuggestionService} 生成并返回，
 * Controller 层负责将其映射为对外的 {@code ReplySuggestionVO}。
 * 此设计保持 application 层对 interfaces 层的单向依赖。
 *
 * @param id         建议唯一标识（UUID）
 * @param content    建议回复文本
 * @param confidence 置信度 [0, 1]
 * @param source     来源标识：{@code "KB"} 或 {@code "CONTEXT"}
 */
public record ReplySuggestionDTO(
        String id,
        String content,
        double confidence,
        String source
) {}
