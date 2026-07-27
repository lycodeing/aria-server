package com.aria.conversation.infrastructure.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图路由级联配置，绑定前缀 aria.routing。
 * 作为 YAML 默认值兜底，运行时值由 RoutingConfigProvider 从 system_config 表覆盖。
 */
@ConfigurationProperties(prefix = "aria.routing")
@Component
@Getter
@Setter
public class RoutingProperties {

    private IntentProperties intent = new IntentProperties();
    private DomainProperties domain = new DomainProperties();

    @Getter
    @Setter
    public static class IntentProperties {
        /** 是否启用向量相似度层（第二阶段，默认关闭） */
        private boolean embeddingEnabled = false;
        /** 向量相似度命中阈值，低于此值继续走 LLM */
        private double embeddingThreshold = 0.75;
        /** LLM 分类置信度最低值，低于此值降级为 UNKNOWN；0.0=关闭 */
        private double minLlmConfidence = 0.0;
        /** few-shot prompt 中每个意图最多注入的示例条数 */
        private int maxExamplesToInject = 5;

        // C3 修复：与 RoutingConfig.Intent 保持同步，确保降级到 YAML 时多意图配置不丢失
        /** 多意图总开关，false 时退化为单意图（可无重启回滚） */
        private boolean multiIntentEnabled = true;
        /** Tier2 全局默认相似度阈值（替代 embeddingThreshold）*/
        private double embeddingGlobalThreshold = 0.75;
        /** 超过此置信度则跳过 Tier3 LLM */
        private double embeddingHighConfidence = 0.85;
        /** 意图级独立阈值覆盖（key=intentCode, value=阈值），可通过 YAML 配置 */
        private java.util.Map<String, Double> embeddingThresholds = new java.util.HashMap<>();
        /** 是否开启 Tier3 动态 RAG 注入 */
        private boolean llmRagEnabled = true;
        /** Tier3 动态 RAG 每意图注入历史案例数 */
        private int llmRagTopK = 2;
        /** 是否开启高置信度结果自动积累 */
        private boolean autoAccumulateEnabled = true;
        /** 自动积累的最低置信度门槛 */
        private double autoAccumulateMinConfidence = 0.95;
    }

    @Getter
    @Setter
    public static class DomainProperties {
        /** 是否启用域路由规则层 */
        private boolean ruleEnabled = true;
        /**
         * 域路由 LLM 置信度阈值（预留）。
         * 当前 LangChain4jDomainRoutingService 返回裸 domain code，不含 confidence，
         * 此配置暂不生效。
         */
        private double minLlmConfidence = 0.0;
    }
}
