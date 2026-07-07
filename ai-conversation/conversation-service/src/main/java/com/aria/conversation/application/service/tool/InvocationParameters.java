package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import reactor.core.publisher.Sinks;

import java.util.List;

/**
 * 单次 streamChat 请求的不可变上下文，封装内置工具执行所需的全部 per-request 状态。
 *
 * <p>通过构造器注入 {@link BuiltinTools}，不作为 {@code @Tool} 方法参数，
 * 避免 LangChain4j 将其纳入 LLM JSON Schema。
 *
 * <p>持有 {@link DomainSummary} 而非 {@code DomainDO}，隔离持久化实体在应用层的流转。
 *
 * @param sessionId          当前会话 ID
 * @param currentDomainCode  当前活跃域 code
 * @param userMessage        触发本次对话的用户消息原文
 * @param allDomains         所有可切换的启用域摘要（预查询，避免工具执行时重复查库）
 * @param eventSink          SSE 事件通道，用于向前端推送工具生命周期事件
 */
public record InvocationParameters(
        String sessionId,
        String currentDomainCode,
        String userMessage,
        List<DomainSummary> allDomains,
        Sinks.Many<ChatEvent> eventSink
) {}
