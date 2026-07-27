package com.aria.conversation.infrastructure.ai;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 路由阈值配置值对象。
 *
 * <p>对应 system_config 中 config_key = 'routing.config' 的 JSON 结构，
 * Jackson 反序列化后直接使用，字段缺失时保持默认值，无需 JsonNode 路径导航。
 *
 * <pre>{@code
 * {
 *   "intent": { "embeddingEnabled": false, "embeddingThreshold": 0.75,
 *               "minLlmConfidence": 0.0, "maxExamplesToInject": 5 },
 *   "domain":  { "ruleEnabled": true }
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class RoutingConfig {

    private Intent intent = new Intent();
    private Domain domain = new Domain();

    /**
     * 从 {@link RoutingProperties} YAML 默认值构造，auth-service 不可用时降级使用。
     *
     * @param p YAML 绑定的默认配置
     * @return 等价的 RoutingConfig 实例
     */
    public static RoutingConfig fromProperties(RoutingProperties p) {
        RoutingConfig c = new RoutingConfig();
        c.getIntent().setEmbeddingEnabled(p.getIntent().isEmbeddingEnabled());
        c.getIntent().setEmbeddingThreshold(p.getIntent().getEmbeddingThreshold());
        c.getIntent().setMinLlmConfidence(p.getIntent().getMinLlmConfidence());
        c.getIntent().setMaxExamplesToInject(p.getIntent().getMaxExamplesToInject());
        c.getDomain().setRuleEnabled(p.getDomain().isRuleEnabled());
        return c;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Intent {
        /**
         * 是否启用向量相似度匹配层（Tier 2），默认关闭；第二阶段开启
         */
        private boolean embeddingEnabled = false;
        /**
         * 向量相似度命中阈值（已废弃），低于此值继续走 LLM。
         * 请改用 {@link #embeddingGlobalThreshold}，此字段保留仅为向后兼容。
         *
         * @deprecated 使用 embeddingGlobalThreshold 替代
         */
        @Deprecated
        private double embeddingThreshold = IntentClassificationConstants.DEFAULT_EMBEDDING_THRESHOLD;
        /**
         * LLM 意图分类置信度下限，低于此值降级为 UNKNOWN；0.0 表示关闭阈值检查
         */
        private double minLlmConfidence = 0.0;
        /**
         * few-shot prompt 中每个意图最多注入的示例句子条数，过多会增加 token 消耗
         */
        private int maxExamplesToInject = IntentClassificationConstants.DEFAULT_MAX_EXAMPLES_TO_INJECT;

        // ── 多意图新增字段（向后兼容：有默认值，旧 JSON 缺失时使用默认）────────

        /**
         * 多意图总开关：false 时退化为单意图模式，可无重启回滚。
         */
        private boolean multiIntentEnabled = true;
        /**
         * Tier2 全局默认相似度阈值，替代已废弃的 embeddingThreshold。
         */
        private double embeddingGlobalThreshold = IntentClassificationConstants.DEFAULT_EMBEDDING_THRESHOLD;
        /**
         * 超过此置信度则跳过 Tier3 LLM 调用（节省延迟）。
         */
        private double embeddingHighConfidence = IntentClassificationConstants.DEFAULT_HIGH_CONFIDENCE;
        /**
         * 意图级独立阈值（key=intentCode, value=阈值），覆盖全局阈值。
         */
        private Map<String, Double> embeddingThresholds = new HashMap<>();
        /**
         * 是否开启 Tier3 动态 RAG 注入（历史案例 Few-Shot 增强）。
         */
        private boolean llmRagEnabled = true;
        /**
         * Tier3 动态 RAG 每意图注入历史案例数。
         */
        private int llmRagTopK = IntentClassificationConstants.DEFAULT_LLM_RAG_TOP_K;
        /**
         * 是否开启高置信度结果自动积累到历史案例库。
         */
        private boolean autoAccumulateEnabled = true;
        /**
         * 自动积累的最低置信度门槛。
         */
        private double autoAccumulateMinConfidence = IntentClassificationConstants.DEFAULT_AUTO_ACCUMULATE_MIN_CONF;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Domain {
        /**
         * 是否启用域路由关键词/正则规则层（Tier 1），false=跳过规则直接走 LLM 小模型
         */
        private boolean ruleEnabled = true;
    }
}

