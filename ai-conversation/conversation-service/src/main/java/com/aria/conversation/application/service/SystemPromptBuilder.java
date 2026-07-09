package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;

import java.util.List;

/**
 * 系统提示词（System Prompt）构造工具类。
 *
 * <p>封装 RAG 参考资料拼接逻辑，供 {@code DomainAgentService} 和
 * {@code ChatAppService} 共享使用，避免重复代码。
 */
public final class SystemPromptBuilder {

    /** 默认基础 system prompt */
    public static final String DEFAULT_BASE_PROMPT =
            """
            你是一名专业的智能客服助手。请用简洁、友好的语言回答用户问题。回答要简明扼要，避免冗长说明。

            【域切换规则】
            - 当需要切换服务域时，调用 switch_domain 工具一次即可，切换后立即结束本轮所有工具调用。
            - switch_domain 返回包含 [DOMAIN_SWITCHED] 的信号时，表示切换已成功完成，禁止重复调用。
            - 切换成功后只需简短告知用户「已为您切换到XX服务，请重新描述您的问题」，不要再调用其他业务工具。
            - 同一轮对话中，域切换与业务查询（天气、订单等）互斥，不能同时进行。""";

    private SystemPromptBuilder() { /* 工具类，不允许实例化 */ }

    /**
     * 基于 RAG 检索结果和可选附加指令构造完整的 system prompt。
     *
     * @param hits     RAG 检索命中结果（可为 null 或空）
     * @param addon    附加指令（可为 null）
     * @param basePrompt 基础 system prompt（传 null 时使用 {@link #DEFAULT_BASE_PROMPT}）
     * @return 拼接后的完整 system prompt
     */
    public static String build(List<KnowledgeSearchResult.Hit> hits, String addon, String basePrompt) {
        StringBuilder sb = new StringBuilder();
        if (hits != null && !hits.isEmpty()) {
            sb.append("【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeSearchResult.Hit h = hits.get(i);
                String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                        ? h.getBreadcrumb() : "文档片段";
                sb.append("[").append(i + 1).append("] ").append(label).append("\n")
                  .append(h.getContent() != null ? h.getContent() : "").append("\n\n");
            }
            sb.append("---\n");
        }
        if (addon != null && !addon.isBlank()) {
            sb.append(addon).append("\n");
        }
        sb.append(basePrompt != null ? basePrompt : DEFAULT_BASE_PROMPT);
        return sb.toString();
    }

    /**
     * 基于 RAG 检索结果构造 system prompt（无附加指令，使用默认 base prompt）。
     *
     * @param hits RAG 检索命中结果（可为 null 或空）
     * @return 拼接后的完整 system prompt
     */
    public static String build(List<KnowledgeSearchResult.Hit> hits) {
        return build(hits, null, DEFAULT_BASE_PROMPT);
    }
}
