package com.aidevplatform.conversation.infrastructure.ai;

/**
 * AI 接口消息值对象（不可变）。
 *
 * <p>对应 OpenAI / 天翼云 chat/completions 接口中 messages 数组的单条元素：
 * <pre>{"role": "user", "content": "..."}</pre>
 *
 * @param role    消息角色（system / user / assistant）
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
