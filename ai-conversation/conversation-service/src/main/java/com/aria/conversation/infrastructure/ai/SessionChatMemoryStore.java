package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>assistant ↔ {@link AiMessage}（含 tool_calls 请求，通过 {@link ConversationMessage#toolCalls()} 承载）</li>
 *   <li>tool      ↔ {@link ToolExecutionResultMessage}，id/toolName 通过 {@code toolRequestId}/{@code toolName} 传递</li>
 * </ul>
 *
 * <p>关键：assistant 消息的 tool_calls 必须与后续 tool 结果消息的 id 严格对齐，
 * 否则 LangChain4j 复读历史时会因 tool_call ↔ tool_result 不配对而抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionChatMemoryStore implements ChatMemoryStore {

    private final ConversationHistoryRepository historyRepo;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = historyRepo.findAll(memoryId.toString()).stream()
                .map(this::toLangChain4jMessage)
                .collect(Collectors.toCollection(ArrayList::new));
        return sanitizeDanglingToolCalls(memoryId.toString(), messages);
    }

    /**
     * 从消息列表末尾截断"悬空 tool call"——即 assistant 发出了 tool_calls 请求，
     * 但对应的 {@link ToolExecutionResultMessage} 因异常中断而缺失的情况。
     *
     * <p>这类残缺历史在下一次请求时会被重新加载，LangChain4j 会尝试重新执行工具，
     * 但此时 executor 查找表（per-request 构建）中并没有该工具，导致 NPE。
     *
     * <p>修复策略：从末尾向前扫描，找到最后一条带 tool_calls 的 {@link AiMessage}，
     * 若其后续消息中没有完整覆盖所有 tool call id 的 {@link ToolExecutionResultMessage}，
     * 则截掉从该 assistant 消息开始到末尾的所有消息。
     */
    private List<ChatMessage> sanitizeDanglingToolCalls(String sessionId, List<ChatMessage> messages) {
        // 从后向前找最后一条带 tool_calls 的 AiMessage
        int lastToolCallIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                lastToolCallIdx = i;
                break;
            }
        }
        if (lastToolCallIdx == -1) {
            return messages; // 没有 tool call，直接返回
        }

        AiMessage aiMsg = (AiMessage) messages.get(lastToolCallIdx);
        Set<String> expectedIds = aiMsg.toolExecutionRequests().stream()
                .map(ToolExecutionRequest::id)
                .collect(Collectors.toCollection(HashSet::new));

        // 收集该 assistant 消息之后所有的 tool result id
        Set<String> resultIds = new HashSet<>();
        for (int i = lastToolCallIdx + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage tr) {
                resultIds.add(tr.id());
            }
        }

        if (resultIds.containsAll(expectedIds)) {
            return messages; // tool call 已完整配对
        }

        // 截掉悬空的 assistant tool_call 及其后续消息
        log.warn("[Memory] 检测到悬空 tool_call，截断历史 sessionId={} idx={} toolIds={}",
                sessionId, lastToolCallIdx, expectedIds);
        return new ArrayList<>(messages.subList(0, lastToolCallIdx));
    }

    /**
     * 更新消息列表并保留已有消息的 seq。
     *
     * <p>LangChain4j 在每轮对话后会调用此方法回写全量消息。如果直接将所有消息的
     * seq 设为 0，会破坏断线重连增量同步机制（{@code getHistorySince} 依赖 seq > 0）。
     *
     * <p>策略：优先按 {@code toolRequestId}（tool 消息）或 tool_calls 内容匹配已有消息以保留其 seq；
     * 兜底按 role + content Deque 匹配，避免重复内容碰撞导致 seq 复用；
     * 新增消息从当前最大 seq+1 开始分配。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        try {
            List<ConversationMessage> existing = historyRepo.findAll(sessionId);
            // 使用 Deque 而非单值 Map，确保同 role+content 的多条消息按顺序各自获得独立 seq
            Map<String, Deque<Long>> seqIndex = new LinkedHashMap<>(existing.size() * 2);
            long maxSeq = 0L;
            for (ConversationMessage em : existing) {
                seqIndex.computeIfAbsent(matchKey(em), k -> new ArrayDeque<>()).offer(em.seq());
                if (em.seq() > maxSeq) {
                    maxSeq = em.seq();
                }
            }

            List<ConversationMessage> toSave = new ArrayList<>(messages.size());
            long nextSeq = maxSeq;
            for (ChatMessage m : messages) {
                ConversationMessage dm = toDomainMessage(m);
                Deque<Long> seqQueue = seqIndex.get(matchKey(dm));
                Long existingSeq = (seqQueue != null) ? seqQueue.poll() : null;
                long seq = (existingSeq != null && existingSeq > 0) ? existingSeq : ++nextSeq;
                toSave.add(new ConversationMessage(
                        dm.role(), dm.content(), seq, dm.timestamp(),
                        dm.toolRequestId(), dm.toolName(), dm.toolCalls()));
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

    /**
     * 构造匹配 key，用于 seq 保留匹配：
     * <ul>
     *   <li>tool 结果消息：优先按 toolRequestId 匹配（全局唯一）</li>
     *   <li>assistant 带 tool_calls：按 role + tool_calls ids 联合匹配（content 可能为 null）</li>
     *   <li>其他：按 role + content 匹配</li>
     * </ul>
     */
    private String matchKey(ConversationMessage m) {
        if ("tool".equals(m.role()) && m.toolRequestId() != null) {
            return "tool|id=" + m.toolRequestId();
        }
        if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            StringBuilder sb = new StringBuilder("assistant|calls=");
            for (ConversationMessage.ToolCall tc : m.toolCalls()) {
                sb.append(tc.id()).append(',');
            }
            return sb.toString();
        }
        return m.role() + "|" + m.content();
    }

    private ChatMessage toLangChain4jMessage(ConversationMessage m) {
        if (m.role() == null) {
            return UserMessage.from(m.content() != null ? m.content() : "");
        }
        return switch (m.role()) {
            case "assistant" -> toAiMessage(m);
            case "tool"      -> ToolExecutionResultMessage.from(
                    m.toolRequestId() != null ? m.toolRequestId() : "",
                    m.toolName()      != null ? m.toolName()      : "",
                    m.content() != null ? m.content() : "");
            default          -> UserMessage.from(m.content() != null ? m.content() : "");
        };
    }

    /**
     * 从领域消息反向重建 {@link AiMessage}。
     * <ul>
     *   <li>无 tool_calls：走 {@code AiMessage.from(text)}；文本为 null 时兜底空串，避免 LangChain4j 内部 NPE</li>
     *   <li>有 tool_calls：{@code AiMessage.builder().text(...).toolExecutionRequests(...)}，
     *       保留 tool_call id/name/arguments，让后续 tool 结果消息能正确配对</li>
     * </ul>
     */
    private AiMessage toAiMessage(ConversationMessage m) {
        List<ConversationMessage.ToolCall> calls = m.toolCalls();
        if (calls == null || calls.isEmpty()) {
            return AiMessage.from(m.content() != null ? m.content() : "");
        }
        List<ToolExecutionRequest> requests = new ArrayList<>(calls.size());
        for (ConversationMessage.ToolCall tc : calls) {
            requests.add(ToolExecutionRequest.builder()
                    .id(tc.id() != null ? tc.id() : "")
                    .name(tc.name() != null ? tc.name() : "")
                    .arguments(tc.arguments() != null ? tc.arguments() : "")
                    .build());
        }
        AiMessage.Builder builder = AiMessage.builder().toolExecutionRequests(requests);
        if (m.content() != null && !m.content().isEmpty()) {
            builder.text(m.content());
        }
        return builder.build();
    }

    private ConversationMessage toDomainMessage(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            List<ConversationMessage.ToolCall> toolCalls = null;
            if (ai.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
                toolCalls = new ArrayList<>(requests.size());
                for (ToolExecutionRequest r : requests) {
                    toolCalls.add(new ConversationMessage.ToolCall(r.id(), r.name(), r.arguments()));
                }
            }
            // ai.text() 在纯 tool_call 消息里可能为 null，向下透传
            return new ConversationMessage("assistant", ai.text(), 0L, null, null, null, toolCalls);
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return new ConversationMessage("tool", tr.text(), 0L, null,
                    tr.id(), tr.toolName(), null);
        }
        if (m instanceof UserMessage um) {
            return new ConversationMessage("user", um.singleText(), 0L, null, null, null, null);
        }
        return new ConversationMessage("user", m.toString(), 0L, null, null, null, null);
    }
}
