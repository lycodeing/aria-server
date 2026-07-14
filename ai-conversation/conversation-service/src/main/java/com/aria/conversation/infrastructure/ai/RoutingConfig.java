package com.aria.conversation.infrastructure.ai;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private Domain domain  = new Domain();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Intent {
        /** 是否启用向量相似度匹配层（Tier 2），默认关闭；第二阶段开启 */
        private boolean embeddingEnabled    = false;
        /** 向量相似度命中阈值，低于此值继续走 LLM，范围 0.0~1.0，推荐 0.75 */
        private double  embeddingThreshold  = 0.75;
        /** LLM 意图分类置信度下限，低于此值降级为 UNKNOWN；0.0 表示关闭阈值检查 */
        private double  minLlmConfidence    = 0.0;
        /** few-shot prompt 中每个意图最多注入的示例句子条数，过多会增加 token 消耗 */
        private int     maxExamplesToInject = 5;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Domain {
        /** 是否启用域路由关键词/正则规则层（Tier 1），false=跳过规则直接走 LLM 小模型 */
        private boolean ruleEnabled = true;
    }

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
}
