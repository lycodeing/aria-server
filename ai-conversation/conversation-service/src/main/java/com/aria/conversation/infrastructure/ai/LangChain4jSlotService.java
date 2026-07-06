package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.model.SlotDefinition;
import com.aria.conversation.domain.service.SlotService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 LangChain4j 的槽位提取服务。
 *
 * <p>构建提取 Prompt，调用 LLM 批量提取意图所需槽位值，
 * 返回 Map&lt;slotName, value&gt;；解析失败返回空 Map，不抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jSlotService implements SlotService {

    private final DynamicModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> extract(String userMessage,
                                       List<ConversationMessage> recentHistory,
                                       List<SlotDefinition> slots) {
        if (slots == null || slots.isEmpty()) {
            return Map.of();
        }
        try {
            String systemPrompt = buildExtractPrompt(slots);
            List<dev.langchain4j.data.message.ChatMessage> messages =
                    buildMessages(userMessage, recentHistory, systemPrompt);
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
            return parseExtracted(response);
        } catch (Exception e) {
            log.warn("[Slot] 槽位提取失败 slots={}", slots.stream()
                    .map(SlotDefinition::slotName).toList(), e);
            return Collections.emptyMap();
        }
    }

    private String buildExtractPrompt(List<SlotDefinition> slots) {
        StringBuilder sb = new StringBuilder("""
                你是一个信息提取器。从用户的消息中提取以下参数，以 JSON 格式返回，
                无法提取的参数不要包含在 JSON 中，不要输出任何其他内容。
                
                需要提取的参数：
                """);
        for (SlotDefinition slot : slots) {
            sb.append("- ").append(slot.slotName())
              .append("（").append(slot.slotType()).append("）")
              .append("：").append(slot.description());
            if (slot.enumValues() != null && !slot.enumValues().isEmpty()) {
                sb.append("，可选值：").append(slot.enumValues());
            }
            sb.append("\n");
        }
        sb.append("\n只输出 JSON，如：{\"order_id\": \"ORD001\"} 或 {}（无法提取时）");
        return sb.toString();
    }

    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            String userMessage,
            List<ConversationMessage> history,
            String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from(systemPrompt));
        if (history != null && !history.isEmpty()) {
            // 截取最近 6 条消息（3 轮对话）作为上下文
            int start = Math.max(0, history.size() - 6);
            for (ConversationMessage m : history.subList(start, history.size())) {
                result.add("assistant".equals(m.role())
                        ? AiMessage.from(m.content())
                        : UserMessage.from(m.content()));
            }
        }
        result.add(UserMessage.from(userMessage));
        return result;
    }

    private Map<String, Object> parseExtracted(String response) {
        if (response == null || response.isBlank()) return Map.of();
        String json = response.trim();
        if (json.startsWith("```")) {
            int startIdx = json.indexOf('{'), endIdx = json.lastIndexOf('}');
            if (startIdx >= 0 && endIdx >= startIdx) json = json.substring(startIdx, endIdx + 1);
        }
        if (!json.startsWith("{")) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            // 防御性拷贝：避免 objectMapper 返回不可变 Map 导致 removeIf 抛 UnsupportedOperationException
            Map<String, Object> result = new LinkedHashMap<>(raw);
            result.entrySet().removeIf(entry ->
                    entry.getValue() == null
                    || entry.getValue().toString().isBlank()
                    || "null".equalsIgnoreCase(entry.getValue().toString()));
            return result;
        } catch (Exception e) {
            log.warn("[Slot] 槽位提取 JSON 解析失败: {}", json, e);
            return Map.of();
        }
    }
}
