package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LangChain4j 的多意图分类服务（Tier 3 LLM 兜底）。
 *
 * <p>实现 {@link MultiIntentClassifier}，从 {@code __system__} 域加载意图定义，
 * 构建多意图分类 Prompt，调用 LLM 返回 JSON 数组，解析为 {@link IntentResult} 列表。
 * 任何失败均降级返回 {@code [IntentResult.UNKNOWN]}，不抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jIntentService implements MultiIntentClassifier {

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private final RoutingConfigProvider routingConfigProvider;

    @Override
    public List<IntentResult> classifyMulti(String userMessage) {
        try {
            DomainConfig domain = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
            if (domain == null || domain.intents().isEmpty()) {
                log.warn("[Intent] __system__ 域不存在或意图列表为空");
                return List.of(IntentResult.UNKNOWN);
            }
            return classifyMulti(userMessage, domain.intents());
        } catch (Exception e) {
            log.warn("[Intent] 多意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return List.of(IntentResult.UNKNOWN);
        }
    }

    @Override
    public List<IntentResult> classifyMulti(String userMessage, List<IntentConfig> intents) {
        try {
            if (intents == null || intents.isEmpty()) {
                return List.of(IntentResult.UNKNOWN);
            }
            String systemPrompt = buildMultiPrompt(intents);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userMessage)
            );
            String response = modelFactory.getChatModel().chat(messages).aiMessage().text();
            return parseMultiResponse(response);
        } catch (Exception e) {
            log.warn("[Intent] 多意图分类失败（域感知），降级为 UNKNOWN. message={}", userMessage, e);
            return List.of(IntentResult.UNKNOWN);
        }
    }

    /**
     * 构建多意图分类 Prompt（返回 intents 数组格式）。
     */
    String buildMultiPrompt(List<IntentConfig> intents) {
        StringBuilder sb = new StringBuilder("""
                你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
                {"intents": [{"intent": "<意图>", "confidence": <0.0到1.0的小数>}, ...]}
                注意：
                1. 如果消息只有一个意图，intents 数组只有一个元素
                2. 如果消息包含多个不同意图，按置信度从高到低列出所有意图
                3. 置信度总和不必为 1（各意图独立评分）
                4. 最多返回 3 个意图，置信度低于 0.5 的不要返回
                
                意图取值说明：
                """);
        int maxExamples = routingConfigProvider.getConfig().getIntent().getMaxExamplesToInject();
        for (IntentConfig intent : intents) {
            sb.append("- ").append(intent.code());
            if (intent.description() != null && !intent.description().isBlank()) {
                sb.append("：").append(intent.description());
            }
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

    /**
     * 解析多意图 JSON 响应。
     * 兼容旧格式单意图 {@code {"intent":...}} 作为兜底。
     */
    List<IntentResult> parseMultiResponse(String response) {
        if (response == null || response.isBlank()) return List.of(IntentResult.UNKNOWN);
        String json = extractJson(response.trim());
        if (!json.startsWith("{")) return List.of(IntentResult.UNKNOWN);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode intentsNode = root.path("intents");
            double minConf = routingConfigProvider.getConfig().getIntent().getMinLlmConfidence();

            if (intentsNode.isMissingNode() || !intentsNode.isArray()) {
                return List.of(parseSingleFallback(root, minConf));
            }
            List<IntentResult> results = new ArrayList<>();
            for (JsonNode node : intentsNode) {
                String intentStr = node.path("intent").asText("UNKNOWN").toUpperCase();
                double confidence = node.path("confidence").asDouble(0.0);
                if (minConf > 0.0 && confidence < minConf) continue;
                IntentType type = IntentType.fromCode(intentStr);
                results.add(new IntentResult(type, intentStr.toLowerCase(), confidence));
            }
            return results.isEmpty() ? List.of(IntentResult.UNKNOWN) : results;
        } catch (Exception e) {
            log.warn("[Intent] 多意图 JSON 解析失败: {}", json, e);
            return List.of(IntentResult.UNKNOWN);
        }
    }

    private IntentResult parseSingleFallback(JsonNode root, double minConf) {
        String intentStr = root.path("intent").asText("UNKNOWN").toUpperCase();
        double confidence = root.path("confidence").asDouble(0.0);
        if (minConf > 0.0 && confidence < minConf) return IntentResult.UNKNOWN;
        IntentType type = IntentType.fromCode(intentStr);
        return new IntentResult(type, intentStr.toLowerCase(), confidence);
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
