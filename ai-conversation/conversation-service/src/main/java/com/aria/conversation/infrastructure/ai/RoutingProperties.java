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
