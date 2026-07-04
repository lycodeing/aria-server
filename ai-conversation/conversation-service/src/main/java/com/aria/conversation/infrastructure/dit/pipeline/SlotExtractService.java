package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 槽位提取服务（EXTRACT 级）。
 *
 * <p>调用 LLM 从用户消息和对话历史中批量提取意图所需的槽位值，
 * 返回 Map&lt;slotName, value&gt;，未能提取的槽位不出现在 Map 中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotExtractService {

    private final DynamicAiClient aiClient;
    private final ObjectMapper objectMapper;

    /**
     * 从用户消息中批量提取槽位值。
     *
     * @param userMessage    当前用户消息
     * @param recentHistory  最近几轮对话（供 LLM 结合上下文提取）
     * @param slots          需要提取的槽位定义列表
     * @return 成功提取的槽位 Map，key=slotName，value=提取值；未提取到的槽位不出现
     */
    public Map<String, Object> extract(String userMessage,
                                       List<ChatMessage> recentHistory,
                                       List<SlotConfig> slots) {
        if (slots.isEmpty()) return Map.of();
        try {
            String systemPrompt = buildExtractPrompt(slots);
            // 拼接最近 3 轮历史 + 当前消息，让 LLM 有更多上下文
            List<ChatMessage> messages = buildMessages(userMessage, recentHistory);
            String response = aiClient.chat(messages, systemPrompt);
            return parseExtracted(response);
        } catch (Exception e) {
            log.warn("[DIT] 槽位提取失败 slots={}", slots.stream()
                    .map(SlotConfig::slotName).toList(), e);
            return Collections.emptyMap();
        }
    }

    private String buildExtractPrompt(List<SlotConfig> slots) {
        StringBuilder sb = new StringBuilder("""
                你是一个信息提取器。从用户的消息中提取以下参数，以 JSON 格式返回，
                无法提取的参数不要包含在 JSON 中，不要输出任何其他内容。
                
                需要提取的参数：
                """);
        for (SlotConfig slot : slots) {
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

    private List<ChatMessage> buildMessages(String userMessage, List<ChatMessage> history) {
        // 取最近 3 轮历史
        int start = Math.max(0, history.size() - 6);
        List<ChatMessage> recent = history.subList(start, history.size());
        List<ChatMessage> all = new java.util.ArrayList<>(recent);
        all.add(new ChatMessage("user", userMessage));
        return all;
    }

    private Map<String, Object> parseExtracted(String response) {
        if (response == null || response.isBlank()) return Map.of();
        String json = response.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('{'), e = json.lastIndexOf('}');
            if (s >= 0 && e >= s) json = json.substring(s, e + 1);
        }
        if (!json.startsWith("{")) return Map.of();
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            // 过滤掉空值
            result.entrySet().removeIf(entry ->
                    entry.getValue() == null
                    || entry.getValue().toString().isBlank()
                    || "null".equalsIgnoreCase(entry.getValue().toString()));
            return result;
        } catch (Exception e) {
            log.warn("[DIT] 槽位提取 JSON 解析失败: {}", json, e);
            return Map.of();
        }
    }
}
