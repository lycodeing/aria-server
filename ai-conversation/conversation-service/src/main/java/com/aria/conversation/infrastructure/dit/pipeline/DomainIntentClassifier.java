package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 领域感知意图分类器。
 *
 * <p>与 {@link com.aria.conversation.infrastructure.ai.IntentClassifier} 的区别：
 * <ul>
 *   <li>携带领域内定义的意图列表（含示例句子）构建分类 Prompt</li>
 *   <li>返回的意图 code 是领域内的业务意图（如 "query_order"），而非通用枚举</li>
 *   <li>兜底使用通用 IntentType.UNKNOWN</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainIntentClassifier {

    private final DynamicAiClient aiClient;
    private final ObjectMapper objectMapper;

    /**
     * 在指定领域意图列表内对用户消息进行意图分类。
     *
     * @param userMessage 用户输入的原始消息
     * @param intents     领域内定义的意图列表
     * @return 分类结果，intentCode 为领域意图 code 或 "UNKNOWN"
     */
    public DomainIntentResult classify(String userMessage, List<IntentConfig> intents) {
        if (intents.isEmpty()) {
            return DomainIntentResult.unknown();
        }
        try {
            String systemPrompt = buildSystemPrompt(intents);
            List<ChatMessage> messages = List.of(new ChatMessage("user", userMessage));
            String response = aiClient.chat(messages, systemPrompt);
            return parseResponse(response, intents);
        } catch (Exception e) {
            log.warn("[DIT] 领域意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return DomainIntentResult.unknown();
        }
    }

    /**
     * 构建领域意图分类 Prompt。
     * 将每个意图的 code、name、description 和示例句子注入 Prompt，
     * 帮助 LLM 准确分类。
     */
    private String buildSystemPrompt(List<IntentConfig> intents) {
        StringBuilder sb = new StringBuilder("""
                你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
                {"intentCode": "<意图code>", "confidence": <0.0到1.0的小数>}
                
                可选意图如下：
                """);

        for (IntentConfig intent : intents) {
            sb.append("- ").append(intent.code())
              .append("：").append(intent.description());
            if (intent.exampleQueries() != null && !intent.exampleQueries().isBlank()
                    && !intent.exampleQueries().equals("[]")) {
                sb.append("（示例：").append(intent.exampleQueries()).append("）");
            }
            sb.append("\n");
        }
        sb.append("- UNKNOWN：以上均不匹配\n\n只输出 JSON，不要解释。");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 为 DomainIntentResult。
     * intentCode 不在意图列表中时降级为 UNKNOWN。
     */
    DomainIntentResult parseResponse(String response, List<IntentConfig> intents) {
        if (response == null || response.isBlank()) {
            return DomainIntentResult.unknown();
        }
        String json = extractJson(response.trim());
        if (!json.startsWith("{")) {
            return DomainIntentResult.unknown();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String intentCode = node.path("intentCode").asText("UNKNOWN");
            double confidence = node.path("confidence").asDouble(1.0);

            // 验证 intentCode 在领域意图列表中
            boolean valid = intents.stream().anyMatch(i -> i.code().equals(intentCode));
            if (!valid && !"UNKNOWN".equals(intentCode)) {
                log.warn("[DIT] 未知领域意图值: {}, 降级为 UNKNOWN", intentCode);
                return DomainIntentResult.unknown();
            }
            return new DomainIntentResult(intentCode, confidence);
        } catch (Exception e) {
            log.warn("[DIT] 领域意图 JSON 解析失败: {}", json, e);
            return DomainIntentResult.unknown();
        }
    }

    private String extractJson(String text) {
        if (text.startsWith("```")) {
            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}');
            if (start >= 0 && end >= start) {
                return text.substring(start, end + 1);
            }
        }
        return text;
    }

    /**
     * 领域意图分类结果。
     *
     * @param intentCode 意图标识（领域内业务意图 code 或 "UNKNOWN"）
     * @param confidence 置信度 0.0~1.0
     */
    public record DomainIntentResult(String intentCode, double confidence) {

        public static DomainIntentResult unknown() {
            return new DomainIntentResult("UNKNOWN", 1.0);
        }

        public boolean isUnknown() {
            return "UNKNOWN".equals(intentCode);
        }
    }
}
