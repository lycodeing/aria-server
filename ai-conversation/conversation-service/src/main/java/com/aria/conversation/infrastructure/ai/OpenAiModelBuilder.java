package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI 兼容协议的 LLM 模型构建器（兜底实现）。
 *
 * <p>支持 OpenAI 及所有兼容 OpenAI 协议的第三方服务
 * （DeepSeek、Moonshot、通义千问等）。当其他 Builder 均不匹配时，
 * 由本构建器兜底处理。
 */
@Slf4j
@Order(2)
@Component
public class OpenAiModelBuilder implements LlmModelBuilder {

    @Override
    public boolean supports(String apiProtocol) {
        if (apiProtocol == null) {
            log.warn("[ModelBuilder] apiProtocol 为 null，默认使用 OpenAI 兼容协议");
        }
        // 大小写不敏感比较——DB 可能存储 "ANTHROPIC"（大写）或 "anthropic"（小写）
        return apiProtocol == null || !AiProtocol.ANTHROPIC.equalsIgnoreCase(apiProtocol);
    }

    @Override
    public ChatModel buildChatModel(AiModelConfig cfg) {
        return OpenAiChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }

    @Override
    public StreamingChatModel buildStreamingModel(AiModelConfig cfg) {
        // P0-D 已知限制：OpenAI 兼容端点默认不在流式响应里返回 token usage，需请求体带
        // stream_options.include_usage=true 才会回传。当前 LangChain4j 版本的
        // OpenAiStreamingChatModelBuilder 未暴露该参数入口，故流式 CHAT 的 token 成本可能漏采
        // （LlmCostLogger 对 null usage 静默跳过，不影响主链路）。非流式 INTENT_CLASSIFY 的
        // 成本统计不受影响，仍是成本报表的可靠信号。待 LangChain4j 升级后补齐。
        return OpenAiStreamingChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }
}
