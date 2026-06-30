package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.infrastructure.ai.CtyunAiClient;
import com.aidevplatform.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final String ROLE_USER      = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String FIELD_ROLE     = "role";
    private static final String FIELD_CONTENT  = "content";
    private static final String FIELD_SEQ      = "seq";

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
        // history 含 seq 字段，供前端通过 sinceSeq 增量同步；AI 调用前剥离 seq 转 String map
        List<Map<String, Object>> history = historyRepository.findAll(sessionId);
        // 用户消息暂入内存（seq 由后续 saveAll/append 重新生成）
        history.add(buildUserOrAssistant(ROLE_USER, userMessage, null));

        StringBuilder assistantReply = new StringBuilder();
        List<Map<String, String>> aiPrompt = toAiPrompt(history);

        return aiClient.streamChat(aiPrompt, SYSTEM_PROMPT)
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
                        history.add(buildUserOrAssistant(ROLE_ASSISTANT, assistantReply.toString(), null));
                        List<Map<String, Object>> trimmed = history.size() > MAX_HISTORY
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
        List<Map<String, Object>> history = historyRepository.findAll(sessionId);
        history.add(buildUserOrAssistant(ROLE_USER, userMessage, null));

        String reply = aiClient.chat(toAiPrompt(history), SYSTEM_PROMPT);

        history.add(buildUserOrAssistant(ROLE_ASSISTANT, reply, null));
        List<Map<String, Object>> trimmed = history.size() > MAX_HISTORY
                ? history.subList(history.size() - MAX_HISTORY, history.size())
                : history;
        historyRepository.saveAll(sessionId, trimmed);
        return reply;
    }

    /** 清除会话历史 */
    public void clearHistory(String sessionId) {
        historyRepository.delete(sessionId);
    }

    /**
     * 获取会话历史消息列表（用于前端展示，含 seq 字段）。
     */
    public List<Map<String, Object>> getHistory(String sessionId) {
        return historyRepository.findAll(sessionId);
    }

    /**
     * 增量获取会话历史：返回 seq 严格大于 sinceSeq 的所有消息（按 seq 升序）。
     * 客户端断线重连后调用，避免每次重连全量拉取历史。
     *
     * @param sessionId 会话唯一标识
     * @param sinceSeq  起始 seq（不含），客户端传入 lastSeq
     * @return 增量消息列表，无新消息时返回空列表
     */
    public List<Map<String, Object>> getHistorySince(String sessionId, long sinceSeq) {
        return historyRepository.findSince(sessionId, sinceSeq);
    }

    /**
     * 保存访客消息（WebSocket 接入人工后，HTTP SSE 路径不再走 AI 时调用）。
     */
    public void saveVisitorMessage(String sessionId, String content) {
        historyRepository.append(sessionId, ROLE_USER, content);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /** 构造内存中的历史消息项（seq 为 null 时不写入字段，由 Repository 写入时统一分配） */
    private Map<String, Object> buildUserOrAssistant(String role, String content, Long seq) {
        Map<String, Object> m = new LinkedHashMap<>(3);
        m.put(FIELD_ROLE, role);
        m.put(FIELD_CONTENT, content);
        if (seq != null) {
            m.put(FIELD_SEQ, seq);
        }
        return m;
    }

    /** 将完整历史（含 seq）剥离为 AI 接口所需的 [role, content] String map 列表 */
    private List<Map<String, String>> toAiPrompt(List<Map<String, Object>> history) {
        List<Map<String, String>> prompt = new ArrayList<>(history.size());
        for (Map<String, Object> msg : history) {
            prompt.add(Map.of(
                    FIELD_ROLE,    String.valueOf(msg.get(FIELD_ROLE)),
                    FIELD_CONTENT, String.valueOf(msg.get(FIELD_CONTENT))
            ));
        }
        return prompt;
    }
}
