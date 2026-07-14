package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 LangChain4j 的意图识别服务。
 *
 * <p>从 {@code __system__} 域加载意图定义，构建分类 Prompt，
 * 调用 LLM 返回 JSON，解析为 {@link IntentResult}。
 * 任何失败均降级返回 {@link IntentResult#UNKNOWN}，不抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jIntentService implements IntentService {

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private final RoutingProperties routingProperties;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            DomainConfig domain = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)
                    .orElse(null);
            if (domain == null || domain.intents().isEmpty()) {
                log.warn("[Intent] __system__ 域不存在或意图列表为空，降级为 UNKNOWN");
                return IntentResult.UNKNOWN;
            }
            String systemPrompt = buildPrompt(domain.intents());
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userMessage)
            );
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }

    private String buildPrompt(List<IntentConfig> intents) {
        StringBuilder sb = new StringBuilder("""
                你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
                {"intent": "<意图>", "confidence": <0.0到1.0的小数>}

                意图取值说明：
                """);
        int maxExamples = routingProperties.getIntent().getMaxExamplesToInject();
        for (IntentConfig intent : intents) {
            sb.append("- ").append(intent.code());
            if (intent.description() != null && !intent.description().isBlank()) {
                sb.append("：").append(intent.description());
            }
            // 注入 exampleQueries 作为 few-shot 示例（已是 List<String>，无需解析）
            List<String> examples = intent.exampleQueries();
            if (examples != null && !examples.isEmpty()) {
                List<String> sample = examples.size() > maxExamples
                        ? examples.subList(0, maxExamples) : examples;
                sb.append("（示例：").append(String.join("、", sample)).append("）");
            }
            sb.append("\n");
        }
        sb.append("- UNKNOWN：无法判断\n\n只输出 JSON，不要解释。");
        return sb.toString();
    }

    IntentResult parseResponse(String response) {
        if (response == null || response.isBlank()) return IntentResult.UNKNOWN;
        String json = extractJson(response.trim());
        if (!json.startsWith("{")) {
            log.warn("[Intent] 响应不是有效 JSON 对象: {}", json);
            return IntentResult.UNKNOWN;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String intentStr = node.path("intent").asText("UNKNOWN").toUpperCase();
            double confidence = node.path("confidence").asDouble(1.0);

            IntentType intent;
            try {
                intent = IntentType.valueOf(intentStr);
            } catch (IllegalArgumentException ex) {
                // 自定义业务 code 不在枚举内，按 FAQ_QUERY 分叉
                log.warn("[Intent] 未知意图值: {}, 映射为 FAQ_QUERY", intentStr);
                intent = IntentType.FAQ_QUERY;
            }

            // 低置信度降级（minLlmConfidence=0.0 时关闭此检查）
            double minConfidence = routingProperties.getIntent().getMinLlmConfidence();
            if (minConfidence > 0.0 && confidence < minConfidence) {
                log.debug("[Intent] LLM 置信度 {} < 阈值 {}，降级为 UNKNOWN", confidence, minConfidence);
                return IntentResult.UNKNOWN;
            }

            return new IntentResult(intent, intentStr.toLowerCase(), confidence);
        } catch (Exception e) {
            log.warn("[Intent] JSON 解析失败: {}", json, e);
            return IntentResult.UNKNOWN;
        }
    }

    private String extractJson(String text) {
        if (text.startsWith("```")) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end >= start) {
                return text.substring(start, end + 1);
            }
        }
        return text;
    }
}
