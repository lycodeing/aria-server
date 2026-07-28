package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 시스템 프롬프트(System Prompt) 구조 빌더.
 *
 * <p>RAG 참고자료, 추가 지령(addon), 기본 프롬프트 세 섹션을 조합한다.
 * 각 섹션은 독립적으로 null/빈값 가능 — 없는 섹션은 조용히 제외된다.
 *
 * <pre>
 * 조합 구조:
 *   [참고자료 섹션]  (RAG hits 있을 때만)
 *   [addon 섹션]    (addon 있을 때만)
 *   [basePrompt]   (항상 포함, null이면 DEFAULT_BASE_PROMPT)
 * </pre>
 */
@Slf4j
public final class SystemPromptBuilder {

    /** 기본 System Prompt */
    public static final String DEFAULT_BASE_PROMPT =
            """
            你是一名专业的智能客服助手。请用简洁、友好的语言回答用户问题。回答要简明扼要，避免冗长说明。

            【域切换规则】
            - 当需要切换服务域时，调用 switch_domain 工具一次即可，切换后立即结束本轮所有工具调用。
            - switch_domain 返回包含 [DOMAIN_SWITCHED] 的信号时，表示切换已成功完成，禁止重复调用。
            - 切换成功后只需简短告知用户「已为您切换到XX服务，请重新描述您的问题」，不要再调用其他业务工具。
            - 同一轮对话中，域切换与业务查询（天气、订单等）互斥，不能同时进行。""";

    private SystemPromptBuilder() {}

    /**
     * 基于 RAG 检索结果和可选附加指令构造完整的 system prompt。
     *
     * @param hits       RAG 检索命中结果（可为 null 或空）
     * @param addon      附加指令块（可为 null）
     * @param basePrompt 基础 system prompt（传 null 时使用 {@link #DEFAULT_BASE_PROMPT}）
     * @return 拼接后的完整 system prompt
     */
    public static String build(List<KnowledgeSearchResult.Hit> hits, String addon, String basePrompt) {
        List<String> sections = new ArrayList<>();

        // 1. RAG参考资料部分（如有）
        String ragSection = buildRagSection(hits);
        if (ragSection != null) sections.add(ragSection);

        // 2. 附加指令（如有）
        if (addon != null && !addon.isBlank()) sections.add(addon);

        // 3. 默认提示（始终包含）
        sections.add(basePrompt != null ? basePrompt : DEFAULT_BASE_PROMPT);

        String prompt = String.join("\n", sections);

        log.debug("[SystemPrompt] built: length={} hasRag={} hasAddon={}",
                prompt.length(), ragSection != null, addon != null && !addon.isBlank());
        return prompt;
    }

    /**
     * 간단 오버로드：addon 없이 RAG hits만 사용 (기본 base prompt 적용).
     */
    public static String build(List<KnowledgeSearchResult.Hit> hits) {
        return build(hits, null, DEFAULT_BASE_PROMPT);
    }

    /**
     * RAG 참고자료 섹션 构建。
     *
     * @return 참고자료 텍스트（hits가 없으면 null → 호출자가 섹션 제외）
     */
    private static String buildRagSection(List<KnowledgeSearchResult.Hit> hits) {
        if (hits == null || hits.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(
                "【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append(formatHit(i + 1, hits.get(i)));
        }
        sb.append("---");
        return sb.toString();
    }

    /**
     * 단일 RAG hit을 번호 포함 텍스트 블록으로 포맷.
     * breadcrumb이 없으면 "文档片段" 레이블 사용.
     */
    private static String formatHit(int index, KnowledgeSearchResult.Hit hit) {
        String label = (hit.getBreadcrumb() != null && !hit.getBreadcrumb().isBlank())
                ? hit.getBreadcrumb() : "文档片段";
        String content = hit.getContent() != null ? hit.getContent() : "";
        return "[" + index + "] " + label + "\n" + content + "\n\n";
    }
}
