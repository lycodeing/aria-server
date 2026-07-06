package com.aria.conversation.application.service.route;

import java.util.List;
import java.util.Map;

/**
 * DIT Pipeline 路由结果密封接口。
 *
 * <p>每种子类型对应一条路由分支，由对应的 {@link RouteResultHandler} 处理。
 * 新增 RouteResult 子类型只需新增 sealed permits + RouteResultHandler 实现（开闭原则）。
 */
public sealed interface RouteResult
        permits RouteResult.TransferResult,
                RouteResult.PendingResult,
                RouteResult.FallbackResult {

    /**
     * 转人工路由结果：系统判定应将会话移交人工座席。
     *
     * @param reason       触发转人工的意图/原因标识
     * @param replyMessage 展示给用户的转接提示语
     */
    record TransferResult(String reason, String replyMessage) implements RouteResult {}

    /**
     * 槽位挂起路由结果：系统需要用户补充信息才能继续执行。
     *
     * @param promptMessage 询问用户的提示语
     * @param candidates    可选候选项列表（格式：[{"id":"...", "label":"..."}]），无候选时为 null 或空
     */
    record PendingResult(String promptMessage,
                         List<Map<String, String>> candidates) implements RouteResult {}

    /**
     * 降级路由结果：走 RAG + LLM 生成回复。
     *
     * @param systemPromptAddon 附加到 base system prompt 的额外指令（可为 null）
     */
    record FallbackResult(String systemPromptAddon) implements RouteResult {}
}
