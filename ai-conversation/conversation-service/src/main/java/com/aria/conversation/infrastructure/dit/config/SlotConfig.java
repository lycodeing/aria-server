package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.List;

/**
 * 槽位配置（只读，从 cs_intent_slot 映射，存入 Redis 缓存）。
 *
 * @param slotName            参数名，如 "order_id"
 * @param slotType            string / number / date / enum
 * @param description         给 LLM 的提取说明
 * @param required            是否必填
 * @param resolveStrategy     解析策略顺序，如 ["EXTRACT","SESSION","DISCOVER","ASK_USER"]
 * @param sessionKey          SESSION 级：会话上下文 key
 * @param discoverToolCode    DISCOVER 级：发现工具 code
 * @param discoverFixedParams DISCOVER 工具的额外固定参数（JSON 字符串）
 * @param askUserPrompt       ASK_USER 级：询问用户的话术
 * @param enumValues          enum 类型可选值列表
 */
public record SlotConfig(
        String slotName,
        String slotType,
        String description,
        boolean required,
        List<String> resolveStrategy,
        String sessionKey,
        String discoverToolCode,
        String discoverFixedParams,
        String askUserPrompt,
        List<String> enumValues
) implements Serializable {}
