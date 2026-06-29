package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.infrastructure.ai.CtyunAiClient;
import com.aidevplatform.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 对话应用服务。
 * 管理多轮对话历史（委托给 ConversationHistoryRepository），调用 AI 生成回复。
 */
@Service
public class ChatAppService {

    private static final int MAX_HISTORY = 20;
    private static final String SYSTEM_PROMPT = """
            你是一名专业的智能客服助手。
            请用简洁、友好的语言回答用户问题。
            如果涉及订单、退款等敏感操作，引导用户验证身份。
            回答要简明扼要，避免冗长说明。
            """;
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final CtyunAiClient aiClient;
    private final ConversationHistoryRepository historyRepository;

    public ChatAppService(CtyunAiClient aiClient, ConversationHistoryRepository historyRepository) {
        this.aiClient = aiClient;
        this.historyRepository = historyRepository;
    }

    /**
     * 流式对话：返回 SSE 文本 chunk Flux。
     * 自动维护 Redis 中的多轮历史（通过 ConversationHistoryRepository）。
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        List<Map<String, String>> history = historyRepository.findAll(sessionId);
        history.add(Map.of("role", ROLE_USER, "content", userMessage));

        StringBuilder assistantReply = new StringBuilder();

        return aiClient.streamChat(history, SYSTEM_PROMPT)
                .map(chunk -> {
                    String content = aiClient.extractDeltaContent(chunk);
                    if (!content.isEmpty()) {
                        assistantReply.append(content);
                    }
                    return content;
                })
                .filter(content -> !content.isEmpty())
                .doOnComplete(() -> {
                    if (!assistantReply.isEmpty()) {
                        history.add(Map.of("role", ROLE_ASSISTANT, "content", assistantReply.toString()));
                        // 截断后全量保存（含 AI 回复）
                        List<Map<String, String>> trimmed = history.size() > MAX_HISTORY
                                ? history.subList(history.size() - MAX_HISTORY, history.size())
                                : history;
                        historyRepository.saveAll(sessionId, trimmed);
                    }
                })
                .onErrorResume(e -> Flux.just("抱歉，AI 服务暂时不可用，请稍后重试。"));
    }

    /**
     * 非流式对话（用于单次问答场景）。
     */
    public String chat(String sessionId, String userMessage) {
        List<Map<String, String>> history = historyRepository.findAll(sessionId);
        history.add(Map.of("role", ROLE_USER, "content", userMessage));

        String reply = aiClient.chat(history, SYSTEM_PROMPT);

        history.add(Map.of("role", ROLE_ASSISTANT, "content", reply));
        List<Map<String, String>> trimmed = history.size() > MAX_HISTORY
                ? history.subList(history.size() - MAX_HISTORY, history.size())
                : history;
        historyRepository.saveAll(sessionId, trimmed);
        return reply;
    }

    /**
     * 清除会话历史。
     */
    public void clearHistory(String sessionId) {
        historyRepository.delete(sessionId);
    }

    /**
     * 获取会话历史消息列表（用于前端展示）。
     */
    public List<Map<String, String>> getHistory(String sessionId) {
        return historyRepository.findAll(sessionId);
    }

    /**
     * 保存访客消息（WebSocket 接入人工后，HTTP SSE 路径不再走 AI 时调用）。
     */
    public void saveVisitorMessage(String sessionId, String content) {
        historyRepository.append(sessionId, ROLE_USER, content);
    }
}
