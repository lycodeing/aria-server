package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户意图分类器。
 *
 * <p>调用 {@link DynamicAiClient#chat} 发起一次非流式 LLM 请求，
 * 要求 LLM 以 JSON 格式返回意图分类结果。
 *
 * <p>Prompt 设计参考 AWS Bedrock 实践：用轻量模型做分类，
 * 只定义业务需要的有限意图类别，不依赖外部 NLU 服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    /**
     * 意图分类系统 Prompt。
     * 明确告诉 LLM 只输出 JSON，不要任何多余解释。
     */
    private static final String CLASSIFY_SYSTEM_PROMPT = """
            你是一个用户意图分类器。分析用户的输入，返回以下 JSON 格式，不要输出任何其他内容：
            {"intent": "<意图>", "confidence": <0.0到1.0的小数>}

            意图取值说明：
            - FAQ_QUERY：用户在咨询产品、服务、政策等业务相关问题
            - TRANSFER_REQUEST：用户明确或隐含地要求转人工客服（如"我要真人"、"转客服"、"人工"）
            - COMPLAINT：用户在投诉、表达强烈不满（如"投诉"、"要求赔偿"、"太差了"）
            - CHITCHAT：闲聊、问候、与业务无关的日常对话（如"你好"、"今天天气"）
            - OUT_OF_SCOPE：询问与本业务完全无关的话题（如问数学题、写代码）
            - UNKNOWN：无法判断

            只输出 JSON，不要解释。
            """;

    private final DynamicAiClient aiClient;
    private final ObjectMapper objectMapper;

    /**
     * 对用户消息进行意图分类。
     *
     * <p>分类失败（LLM 返回格式错误、网络异常等）时返回 {@link IntentResult#UNKNOWN}，
     * 不抛出异常，保证主流程不因分类失败中断。
     *
     * @param userMessage 用户输入的原始消息
     * @return 意图分类结果
     */
    public IntentResult classify(String userMessage) {
        try {
            List<ChatMessage> messages = List.of(new ChatMessage("user", userMessage));
            String response = aiClient.chat(messages, CLASSIFY_SYSTEM_PROMPT);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("[Intent] 意图分类失败，降级为 UNKNOWN. message={}", userMessage, e);
            return IntentResult.UNKNOWN;
        }
    }

    /**
     * 解析 LLM 返回的 JSON 字符串为 IntentResult。
     *
     * <p>容错设计：
     * <ul>
     *   <li>LLM 可能在 JSON 外包裹 markdown 代码块（```json ... ```），需要提取</li>
     *   <li>intent 字段值不在枚举中时，降级为 UNKNOWN</li>
     *   <li>confidence 字段缺失时，默认 1.0</li>
     * </ul>
     */
    IntentResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return IntentResult.UNKNOWN;
        }
        // 提取 JSON：LLM 有时会输出 ```json\n{...}\n```
        String json = extractJson(response.trim());
        // 快速检查：不以 { 开头说明不是有效 JSON 对象，提前返回 UNKNOWN
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
                log.warn("[Intent] 未知意图值: {}, 降级为 UNKNOWN", intentStr);
                intent = IntentType.UNKNOWN;
            }
            return new IntentResult(intent, confidence);
        } catch (Exception e) {
            log.warn("[Intent] JSON 解析失败: {}", json, e);
            return IntentResult.UNKNOWN;
        }
    }

    /** 从可能包含 markdown 代码块的字符串中提取 JSON 部分 */
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
}
