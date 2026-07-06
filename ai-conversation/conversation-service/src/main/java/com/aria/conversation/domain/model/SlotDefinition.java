package com.aria.conversation.domain.model;

import java.util.List;

/**
 * 槽位定义值对象（不可变）。
 *
 * <p>从基础设施层 {@code SlotConfig} 映射而来，仅保留 LLM 提取所需的最少字段，
 * 避免 domain 层依赖 infrastructure 类型。
 *
 * @param slotName    参数名，如 "order_id"
 * @param slotType    类型标识：string / number / date / enum
 * @param description 给 LLM 的提取说明
 * @param enumValues  enum 类型可选值列表（非 enum 类型时为 null 或空）
 */
public record SlotDefinition(
        String slotName,
        String slotType,
        String description,
        List<String> enumValues
) {
}
