package com.aria.conversation.application.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SSE JSON 信封序列化工具。
 *
 * <p>统一处理 payload → JSON 字符串的序列化，消除散落在各服务里的
 * {@code try { objectMapper.writeValueAsString(...) } catch (JsonProcessingException) { ... }}
 * 样板代码。所有 payload 都是内部 record，字段可控，序列化失败被视为不可恢复的编程错误，
 * 直接包装为 {@link IllegalStateException} 抛出，交给上层 {@code onErrorResume} 处理。
 *
 * <p>与直接使用 {@link ObjectMapper} 相比，此 helper 的优势：
 * <ul>
 *   <li>去除每个调用点的 checked exception 样板</li>
 *   <li>调用点表达意图更清晰：{@code SseJson.encode(mapper, payload)}</li>
 * </ul>
 */
public final class SseJson {

    private SseJson() { /* 工具类不允许实例化 */ }

    /**
     * 将 payload 序列化为 JSON 字符串。
     *
     * @param mapper  Spring 容器管理的 Jackson ObjectMapper
     * @param payload SSE payload（通常是 record）
     * @return 紧凑 JSON 字符串
     * @throws IllegalStateException 序列化失败（payload 结构非法，视为编程错误）
     */
    public static String encode(ObjectMapper mapper, Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE payload 序列化失败: " + payload.getClass().getName(), e);
        }
    }
}
