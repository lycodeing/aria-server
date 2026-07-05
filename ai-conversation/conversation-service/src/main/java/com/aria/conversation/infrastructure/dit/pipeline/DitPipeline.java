package com.aria.conversation.infrastructure.dit.pipeline;

import java.util.List;
import java.util.Map;

/**
 * DIT Pipeline 路由结果容器（存根，保留以支持 RouteResultHandler 策略实现）。
 *
 * <p>原 DitPipeline 在 Phase 5C 中被 LangChain4j {@link com.aria.conversation.application.service.DomainAgentService}
 * 替代；本类仅保留 {@link RouteResult} 类型层次，供 RouteResultHandler 实现引用。
 * 后续如需重新启用 DIT pipeline 路径，在此类中补充 Pipeline 执行逻辑即可。
 */
public final class DitPipeline {

    private DitPipeline() { /* 工具类，不允许实例化 */ }

    /**
     * DIT Pipeline 路由结果密封接口。
     * 每种子类型对应一条路由分支，由对应的 {@code RouteResultHandler} 处理。
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
}
