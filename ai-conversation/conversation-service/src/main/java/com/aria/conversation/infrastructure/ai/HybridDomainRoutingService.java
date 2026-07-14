package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 域路由级联协调器（@Primary），实现 DomainRoutingService。
 *
 * <p>路由策略：
 * <ul>
 *   <li>Tier 1: {@link KeywordRegexDomainMatcher}（关键词/正则，本地内存，无网络开销）</li>
 *   <li>Tier 2: {@link LangChain4jDomainRoutingService}（LLM 兜底，仅在 Tier 1 未命中时调用）</li>
 * </ul>
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridDomainRoutingService implements DomainRoutingService {

    private final KeywordRegexDomainMatcher ruleMatcher;
    private final LangChain4jDomainRoutingService llmRouter;
    private final RoutingConfigProvider routingConfigProvider;

    @Override
    public RouteResult route(String userMessage, String currentDomain,
                             List<ConversationMessage> recentHistory) {
        if (routingConfigProvider.isDomainRuleEnabled()) {
            Optional<String> matched = ruleMatcher.matchDomain(userMessage);
            if (matched.isPresent()) {
                String target = matched.get();
                boolean shouldSwitch = !target.equalsIgnoreCase(currentDomain);
                log.debug("[HybridDomain] Tier1 规则命中，跳过 LLM. domain={} shouldSwitch={}",
                        target, shouldSwitch);
                return new RouteResult(target, shouldSwitch);
            }
        }
        log.debug("[HybridDomain] Tier1 未命中，降级到 LLM 路由. currentDomain={}", currentDomain);
        return llmRouter.route(userMessage, currentDomain, recentHistory);
    }
}
