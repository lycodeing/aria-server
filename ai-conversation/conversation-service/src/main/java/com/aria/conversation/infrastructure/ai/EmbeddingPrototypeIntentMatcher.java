package com.aria.conversation.infrastructure.ai;

import com.aria.common.core.util.VectorMathUtils;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于 Embedding 原型的多意图匹配器（Tier 2）。
 *
 * <p><b>算法：</b>
 * <ol>
 *   <li>将用户消息编码为 embedding 向量并 L2 归一化</li>
 *   <li>与 Redis 中所有意图原型向量逐一计算余弦相似度</li>
 *   <li>相似度超过各意图独立阈值（或全局阈值）的，全部加入结果列表</li>
 * </ol>
 *
 * <p><b>长尾意图为何有效：</b>原型向量只需 1 个 exampleQuery，余弦相似度不依赖样本数量，
 * 而是依赖 embedding 模型的语义表征能力。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingPrototypeIntentMatcher {

    private final EmbeddingService embeddingService;
    private final IntentPrototypeStore prototypeStore;
    private final RoutingConfigProvider routingConfigProvider;

    /**
     * 返回所有相似度超过阈值的意图，按相似度降序排列。
     *
     * @param userMessage 用户消息
     * @return 命中意图列表，可能为空列表
     */
    public List<IntentResult> match(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return List.of();
        }
        Map<String, float[]> prototypes = prototypeStore.getAllPrototypes();
        if (prototypes.isEmpty()) {
            log.debug("[EmbeddingMatcher] 原型库为空，跳过 Tier2");
            return List.of();
        }

        float[] queryNorm = VectorMathUtils.normalize(embeddingService.encode(userMessage));

        RoutingConfig.Intent intentConfig = routingConfigProvider.getConfig().getIntent();
        double globalThreshold = intentConfig.getEmbeddingGlobalThreshold();
        Map<String, Double> intentThresholds = intentConfig.getEmbeddingThresholds();

        List<IntentResult> results = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : prototypes.entrySet()) {
            String intentCode = entry.getKey();
            float[] protoNorm = entry.getValue();
            double similarity = VectorMathUtils.cosineSimilarity(queryNorm, protoNorm);
            double threshold = intentThresholds.getOrDefault(intentCode, globalThreshold);

            if (similarity >= threshold) {
                // N2 修复：业务路由语境使用 fromBusinessCode()，自定义意图 code 映射为 FAQ_QUERY
                IntentType type = IntentType.fromBusinessCode(intentCode);
                results.add(new IntentResult(type, intentCode, similarity));
                log.debug("[EmbeddingMatcher] 命中 intent={} sim={} threshold={}",
                        intentCode, String.format("%.4f", similarity), threshold);
            }
        }
        results.sort(Comparator.comparingDouble(IntentResult::confidence).reversed());
        return results;
    }
}
