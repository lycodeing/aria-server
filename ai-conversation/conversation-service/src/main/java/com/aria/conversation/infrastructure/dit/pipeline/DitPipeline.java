package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.dit.repository.PendingSlotRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DIT Pipeline 主编排器（Steps 1-3）。
 *
 * <p>负责：
 * <ol>
 *   <li>加载领域配置（Redis 缓存 + DB 兜底）</li>
 *   <li>领域感知意图识别</li>
 *   <li>槽位解析（四级策略）</li>
 * </ol>
 *
 * <p>Steps 4-5（工具执行 + LLM 回答）在 P3 实现，当前返回的 RouteResult
 * 供 {@link com.aria.conversation.application.service.ChatAppService} 做路由决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DitPipeline {

    private final DomainRepository domainRepository;
    private final DomainIntentClassifier intentClassifier;
    private final SlotResolver slotResolver;
    private final PendingSlotRepository pendingSlotRepo;

    /**
     * 执行 Steps 1-3，返回路由决策结果。
     *
     * @param sessionId     会话 ID
     * @param userMessage   用户消息
     * @param domainCode    领域标识
     * @param recentHistory 最近对话历史（供槽位 EXTRACT 级使用）
     * @param sessionCtx    会话上下文（登录用户信息等）
     * @return 路由决策结果
     */
    public RouteResult route(String sessionId,
                             String userMessage,
                             String domainCode,
                             List<ChatMessage> recentHistory,
                             Map<String, Object> sessionCtx) {

        // Step 1: 加载领域配置
        Optional<DomainConfig> domainOpt = domainRepository.findByCode(domainCode);
        if (domainOpt.isEmpty()) {
            log.warn("[DIT] 领域配置不存在，降级走通用流程 domainCode={}", domainCode);
            return RouteResult.fallback("领域配置不存在，降级走通用流程");
        }
        DomainConfig domain = domainOpt.get();

        // Step 2: 领域感知意图识别
        DomainIntentClassifier.DomainIntentResult intentResult =
                intentClassifier.classify(userMessage, domain.intents());
        log.debug("[DIT] 意图识别 sessionId={} intentCode={} confidence={}",
                sessionId, intentResult.intentCode(), intentResult.confidence());

        // UNKNOWN → 降级走通用 FAQ 流程（带领域 system prompt）
        if (intentResult.isUnknown()) {
            return RouteResult.faqFallback(domain.systemPromptAddon());
        }

        IntentConfig intentConfig = domain.findIntent(intentResult.intentCode())
                .orElse(null);
        if (intentConfig == null) {
            return RouteResult.faqFallback(domain.systemPromptAddon());
        }

        // 自动转人工
        if (intentConfig.autoTransfer()) {
            log.info("[DIT] 意图需要转人工 sessionId={} intent={}", sessionId, intentConfig.code());
            return RouteResult.transfer(
                    intentConfig.code().contains("complaint")
                    ? "非常抱歉给您带来了不好的体验，已为您转接人工客服，请稍候。"
                    : "好的，已为您转接人工客服，请稍候。",
                    intentConfig.code()
            );
        }

        // Step 3: 槽位解析
        SlotResolveResult slotResult = slotResolver.resolve(
                sessionId, userMessage, recentHistory, intentConfig, sessionCtx);

        if (slotResult.isGiveUp()) {
            return RouteResult.transfer(slotResult.promptMessage(), "slot_give_up");
        }

        if (slotResult.isPending()) {
            return RouteResult.pending(
                    slotResult.promptMessage(),
                    slotResult.status() == SlotResolveResult.Status.DISCOVERED
                            ? slotResult.candidates() : null
            );
        }

        // 槽位全部解析完成，返回执行配置
        return RouteResult.execute(intentConfig, slotResult.resolvedSlots(),
                domain.systemPromptAddon(), domain.knowledgeBaseId());
    }

    // ---- 路由决策结果 ----

    /**
     * Pipeline Steps 1-3 的路由决策结果。
     */
    public sealed interface RouteResult
            permits RouteResult.FallbackResult, RouteResult.TransferResult,
                    RouteResult.PendingResult, RouteResult.ExecuteResult {

        /** 降级走通用 FAQ 流程（无领域/意图匹配） */
        record FallbackResult(String systemPromptAddon) implements RouteResult {}

        /** 自动转人工（auto_transfer 或槽位解析超限） */
        record TransferResult(String replyMessage, String reason) implements RouteResult {}

        /** 槽位解析挂起，等待用户输入（MISSING 或 DISCOVERED） */
        record PendingResult(String promptMessage,
                             List<Map<String, String>> candidates) implements RouteResult {}

        /** 槽位解析完成，可执行工具调用 + LLM 回答 */
        record ExecuteResult(IntentConfig intentConfig,
                             Map<String, Object> resolvedSlots,
                             String systemPromptAddon,
                             Long knowledgeBaseId) implements RouteResult {}

        static RouteResult fallback(String reason) {
            return new FallbackResult(null);
        }

        static RouteResult faqFallback(String systemPromptAddon) {
            return new FallbackResult(systemPromptAddon);
        }

        static RouteResult transfer(String replyMessage, String reason) {
            return new TransferResult(replyMessage, reason);
        }

        static RouteResult pending(String promptMessage, List<Map<String, String>> candidates) {
            return new PendingResult(promptMessage, candidates);
        }

        static RouteResult execute(IntentConfig intentConfig, Map<String, Object> resolvedSlots,
                                   String systemPromptAddon, Long knowledgeBaseId) {
            return new ExecuteResult(intentConfig, resolvedSlots, systemPromptAddon, knowledgeBaseId);
        }
    }
}
