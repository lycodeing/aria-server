package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;

/**
 * 意图配置（只读，从 cs_intent 映射，含关联的槽位和工具绑定）。
 *
 * @param code           意图标识，如 "query_order"
 * @param name           意图名称，如 "查询订单"
 * @param description    给 LLM 的意图说明
 * @param exampleQueries 少样本示例（由 Repository 解析为 List<String>）
 * @param autoTransfer   是否自动转人工
 * @param skipRag        是否跳过 RAG
 * @param fallbackReply  工具失败兜底回复
 * @param slots          槽位列表（按 sort_order 排序）
 * @param toolBindings   工具绑定列表（按 execution_order 排序）
 * @param keywords       关键词列表，大小写不敏感包含匹配
 * @param patterns       正则表达式列表，Java Pattern 语法
 * @param sortOrder      意图排序权重
 */
public record IntentConfig(
        String code,
        String name,
        String description,
        List<String> exampleQueries,
        boolean autoTransfer,
        boolean skipRag,
        String fallbackReply,
        List<SlotConfig> slots,
        List<IntentToolBinding> toolBindings,
        List<String> keywords,
        List<String> patterns,
        int sortOrder
) implements Serializable {

    /** 获取所有 REQUIRED 工具绑定，按 executionOrder 升序排列 */
    public List<IntentToolBinding> requiredTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isRequired)
                .sorted(Comparator.comparingInt(IntentToolBinding::executionOrder))
                .toList();
    }

    /** 获取所有 OPTIONAL 工具绑定 */
    public List<IntentToolBinding> optionalTools() {
        return toolBindings.stream()
                .filter(IntentToolBinding::isOptional)
                .toList();
    }
}
