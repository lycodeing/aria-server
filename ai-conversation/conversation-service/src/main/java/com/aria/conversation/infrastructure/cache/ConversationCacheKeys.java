package com.aria.conversation.infrastructure.cache;

/**
 * 会话模块 Redis 缓存 Key 常量。
 *
 * <p>集中定义所有 Redis Key 前缀，避免多个 Service 中重复硬编码导致修改遗漏。
 *
 * @author lycodeing
 * @since 2026-07
 */
public final class ConversationCacheKeys {

    private ConversationCacheKeys() {}

    /** AI 会话摘要缓存前缀，格式：{@code ai_summary:{sessionId}} */
    public static final String AI_SUMMARY_PREFIX = "ai_summary:";

    /** AI 回复建议缓存前缀，格式：{@code reply_suggestions:{sessionId}} */
    public static final String REPLY_SUGGESTIONS_PREFIX = "reply_suggestions:";
}
