package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * LLM 模型构建策略接口。
 *
 * <p>每种 AI 协议（OpenAI、Anthropic 等）提供一个实现，
 * 由 {@link DynamicModelFactory} 根据运行时配置动态选择。
 * 新增协议只需添加 {@code @Component} 实现类，无需修改工厂代码（开闭原则）。
 *
 * <p>实现类通过 {@link org.springframework.core.annotation.Order @Order} 注解控制优先级：
 * <ul>
 *   <li>{@code @Order(1)} — 精确匹配协议（如 Anthropic）</li>
 *   <li>{@code @Order(2)} — 兜底实现（如 OpenAI 兼容协议）</li>
 * </ul>
 */
public interface LlmModelBuilder {

    /**
     * 判断是否支持指定的 API 协议（大小写不敏感）。
     *
     * @param apiProtocol 协议标识，如 "anthropic"、"openai"、"deepseek"
     * @return true 表示本构建器支持该协议
     */
    boolean supports(String apiProtocol);

    /**
     * 构建同步对话模型。
     *
     * @param cfg AI 模型配置（baseUrl、apiKey、modelName 等）
     * @return 同步 ChatModel 实例
     */
    ChatModel buildChatModel(AiModelConfig cfg);

    /**
     * 构建流式对话模型。
     *
     * @param cfg AI 模型配置（baseUrl、apiKey、modelName 等）
     * @return 流式 StreamingChatModel 实例
     */
    StreamingChatModel buildStreamingModel(AiModelConfig cfg);
}
