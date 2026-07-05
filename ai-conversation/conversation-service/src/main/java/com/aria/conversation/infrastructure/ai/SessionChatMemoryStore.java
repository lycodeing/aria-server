package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j ChatMemoryStore ACL adapter.
 *
 * <p>将会话历史 Redis 存储（{@link ConversationHistoryRepository}）适配为
 * LangChain4j {@link ChatMemoryStore} 接口。LangChain4j 类型仅在本类内部使用，
 * 不泄漏到 domain / application 层，保持 ACL 边界清晰。
 *
 * <p>角色映射：
 * <ul>
 *   <li>user      ↔ {@link UserMessage}</li>
 *   <li>assistant ↔ {@link AiMessage}</li>
 *   <li>tool      ↔ {@link ToolExecutionResultMessage}，id/toolName 通过扩展字段传递</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionChatMemoryStore implements ChatMemoryStore {

    private final ConversationHistoryRepository historyRepo;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return historyRepo.findAll(memoryId.toString()).stream()
                .map(this::toLangChain4jMessage)
                .toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            historyRepo.saveAll(memoryId.toString(), messages.stream()
                    .map(this::toDomainMessage).toList());
        } catch (Exception e) {
            log.error("[Memory] Failed to persist chat memory: sessionId={}", memoryId, e);
            throw e;
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        historyRepo.delete(memoryId.toString());
    }

    // -------------------------------------------------------
    // 内部转换
    // -------------------------------------------------------

    private ChatMessage toLangChain4jMessage(ConversationMessage m) {
        return switch (m.role()) {
            case "assistant" -> AiMessage.from(m.content());
            case "tool"      -> ToolExecutionResultMessage.from(
                    m.toolRequestId() != null ? m.toolRequestId() : "",
                    m.toolName()      != null ? m.toolName()      : "",
                    m.content());
            default          -> UserMessage.from(m.content());
        };
    }

    private ConversationMessage toDomainMessage(ChatMessage m) {
        if (m instanceof AiMessage ai)
            return new ConversationMessage("assistant", ai.text(), 0L, null, null, null);
        if (m instanceof ToolExecutionResultMessage tr)
            // LangChain4j 1.1.0: id(), toolName(), text()
            return new ConversationMessage("tool", tr.text(), 0L, null,
                    tr.id(), tr.toolName());
        if (m instanceof UserMessage um)
            return new ConversationMessage("user", um.singleText(), 0L, null, null, null);
        return new ConversationMessage("user", m.toString(), 0L, null, null, null);
    }
}
