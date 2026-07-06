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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j ChatMemoryStore ACL 适配器。
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

    /**
     * 更新消息列表并保留已有消息的 seq。
     *
     * <p>LangChain4j 在每轮对话后会调用此方法回写全量消息。如果直接将所有消息的
     * seq 设为 0，会破坏断线重连增量同步机制（{@code getHistorySince} 依赖 seq > 0）。
     *
     * <p>策略：通过 role + content 匹配已有消息以保留其 seq；新增消息从当前最大 seq+1 开始分配。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        try {
            // 读取已有消息，构建 role|content → seq 的索引
            List<ConversationMessage> existing = historyRepo.findAll(sessionId);
            Map<String, Long> seqIndex = new HashMap<>(existing.size());
            long maxSeq = 0L;
            for (ConversationMessage em : existing) {
                seqIndex.putIfAbsent(em.role() + "|" + em.content(), em.seq());
                if (em.seq() > maxSeq) {
                    maxSeq = em.seq();
                }
            }

            // 转换消息并分配 seq
            List<ConversationMessage> toSave = new ArrayList<>(messages.size());
            long nextSeq = maxSeq;
            for (ChatMessage m : messages) {
                ConversationMessage dm = toDomainMessage(m);
                String key = dm.role() + "|" + dm.content();
                Long existingSeq = seqIndex.get(key);
                long seq = (existingSeq != null && existingSeq > 0) ? existingSeq : ++nextSeq;
                toSave.add(new ConversationMessage(
                        dm.role(), dm.content(), seq, dm.timestamp(),
                        dm.toolRequestId(), dm.toolName()));
            }
            historyRepo.saveAll(sessionId, toSave);
        } catch (Exception e) {
            log.error("[Memory] 持久化聊天记忆失败: sessionId={}", memoryId, e);
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
        if (m instanceof AiMessage ai) {
            return new ConversationMessage("assistant", ai.text(), 0L, null, null, null);
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return new ConversationMessage("tool", tr.text(), 0L, null,
                    tr.id(), tr.toolName());
        }
        if (m instanceof UserMessage um) {
            return new ConversationMessage("user", um.singleText(), 0L, null, null, null);
        }
        return new ConversationMessage("user", m.toString(), 0L, null, null, null);
    }
}
