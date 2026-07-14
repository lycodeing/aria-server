package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 意图分类级联协调器（@Primary），实现 IntentService 接口。
 * ChatAppService 零感知，通过 @Primary 自动注入此实现。
 *
 * Tier 1: KeywordRegexIntentMatcher（关键词/正则，< 1ms）
 * Tier 2: LangChain4jIntentService（Few-Shot LLM 兜底，200-800ms）
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridIntentService implements IntentService {

    private final KeywordRegexIntentMatcher ruleMatcher;
    private final LangChain4jIntentService llmClassifier;

    @Override
    public IntentResult classify(String userMessage) {
        try {
            Optional<IntentResult> ruleResult = ruleMatcher.match(userMessage);
            if (ruleResult.isPresent()) {
                log.debug("[HybridIntent] Tier1 规则命中，跳过 LLM. intent={}",
                        ruleResult.get().intent());
                return ruleResult.get();
            }
        } catch (Exception e) {
            log.warn("[HybridIntent] 规则层异常，降级走 LLM. message={}", userMessage, e);
        }
        return llmClassifier.classify(userMessage);
    }
}
