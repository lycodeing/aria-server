package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.domain.ConversationMessage;
import com.aidevplatform.conversation.infrastructure.ai.ChatMessage;
import com.aidevplatform.conversation.infrastructure.ai.CtyunAiClient;
import com.aidevplatform.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aidevplatform.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aidevplatform.conversation.infrastructure.repository.ConversationHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话应用服务。
 * 编排多轮对话历史维护（委托给 ConversationHistoryRepository）、
 * 知识库检索（RAG）和 AI 回复生成。
 */
@Slf4j
@Service
public class ChatAppService {

    private static final String ROLE_USER      = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final String BASE_SYSTEM_PROMPT = """
            你是一名专业的智能客服助手。
            请用简洁、友好的语言回答用户问题。
            如果涉及订单、退款等敏感操作，引导用户验证身份。
            回答要简明扼要，避免冗长说明。
            """;

    private final CtyunAiClient aiClient;
    private final ConversationHistoryRepository historyRepository;
    private final KnowledgeClient knowledgeClient;

    public ChatAppService(CtyunAiClient aiClient,
                          ConversationHistoryRepository historyRepository,
                          KnowledgeClient knowledgeClient) {
        this.aiClient          = aiClient;
        this.historyRepository = historyRepository;
        this.knowledgeClient   = knowledgeClient;
    }

    // -------------------------------------------------------
    // 对话主流程
    // -------------------------------------------------------

    /**
     * 检索与用户问题相关的知识块（RAG 第一步）。
     * 运行于 Spring MVC 阻塞线程，直接调用，不使用响应式包装。
     */
    public List<KnowledgeSearchResult.Hit> searchHits(String userMessage) {
        return knowledgeClient.search(userMessage);
    }

    /**
     * 流式对话（带预检索 hits），供 Controller 提前检索后调用，避免重复检索。
     *
     * <p>历史维护策略：
     * <ol>
     *   <li>立即持久化 USER 消息（生成 seq + 发 MQ + 写 Redis List）</li>
     *   <li>读取完整历史作为 AI prompt</li>
     *   <li>流完成后持久化 ASSISTANT 回复</li>
     * </ol>
     *
     * <p>⚠️ 此方法在 Spring MVC 阻塞线程上下文调用，Flux 内部不调用 block()。
     */
    public Flux<String> streamChat(String sessionId, String userMessage,
                                   List<KnowledgeSearchResult.Hit> hits) {
        historyRepository.append(sessionId, ROLE_USER, userMessage);

        String systemPrompt = buildSystemPrompt(hits);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder assistantReply = new StringBuilder();

        return aiClient.streamChat(aiPrompt, systemPrompt)
                .map(chunk -> {
                    String content = aiClient.extractDeltaContent(chunk);
                    if (!content.isEmpty()) assistantReply.append(content);
                    return content;
                })
                .filter(content -> !content.isEmpty())
                .doOnComplete(() -> {
                    if (!assistantReply.isEmpty()) {
                        historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                    }
                })
                .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just("抱歉，AI 服务暂时不可用，请稍后重试。"));
    }

    /**
     * 流式对话（自动检索），内部调用 {@link #searchHits} 后委托带 hits 的重载。
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        return streamChat(sessionId, userMessage, searchHits(userMessage));
    }

    /**
     * 非流式对话，返回完整回复文本。
     */
    public String chat(String sessionId, String userMessage) {
        historyRepository.append(sessionId, ROLE_USER, userMessage);
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = buildSystemPrompt(hits);
        String reply = aiClient.chat(toAiPrompt(historyRepository.findAll(sessionId)), systemPrompt);
        historyRepository.append(sessionId, ROLE_ASSISTANT, reply);
        return reply;
    }

    // -------------------------------------------------------
    // 历史查询
    // -------------------------------------------------------

    /** 清除会话历史。 */
    public void clearHistory(String sessionId) {
        historyRepository.delete(sessionId);
    }

    /** 获取全量历史消息（最近 20 轮），用于前端加载上下文。 */
    public List<ConversationMessage> getHistory(String sessionId) {
        return historyRepository.findAll(sessionId);
    }

    /**
     * 增量获取历史消息（seq > sinceSeq），用于断线重连后补齐空窗。
     *
     * @param sinceSeq 客户端已知的最后一条消息 seq
     */
    public List<ConversationMessage> getHistorySince(String sessionId, long sinceSeq) {
        return historyRepository.findSince(sessionId, sinceSeq);
    }

    /** 保存访客消息（转人工后，HTTP SSE 路径不走 AI 时调用）。 */
    public void saveVisitorMessage(String sessionId, String content) {
        historyRepository.append(sessionId, ROLE_USER, content);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 将历史消息转换为 AI 接口所需的 {@link ChatMessage} 列表，剥离 seq 和 timestamp 字段。
     */
    private List<ChatMessage> toAiPrompt(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }

    /**
     * 构建含知识上下文的 system prompt。
     * 有命中结果时在基础 prompt 前注入【参考资料】块；无命中时返回基础 prompt。
     */
    private String buildSystemPrompt(List<KnowledgeSearchResult.Hit> hits) {
        if (hits.isEmpty()) {
            return BASE_SYSTEM_PROMPT;
        }
        StringBuilder ref = new StringBuilder("【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchResult.Hit hit = hits.get(i);
            String label = (hit.getBreadcrumb() != null && !hit.getBreadcrumb().isBlank())
                    ? hit.getBreadcrumb() : "文档片段";
            String content = hit.getContent() != null ? hit.getContent() : "";
            ref.append("[").append(i + 1).append("] ").append(label).append("\n")
               .append(content).append("\n\n");
        }
        return ref.append("---\n").append(BASE_SYSTEM_PROMPT).toString();
    }
}
