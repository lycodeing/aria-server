package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;

/**
 * 意图-工具绑定配置（只读，从 cs_intent_tool 映射）。
 *
 * @param tool           工具配置
 * @param executionMode  REQUIRED / OPTIONAL
 * @param executionOrder REQUIRED 工具的串行顺序
 * @param paramMappings  参数来源映射（JSON 字符串）
 */
public record IntentToolBinding(
        ToolConfig tool,
        String executionMode,
        int executionOrder,
        String paramMappings
) implements Serializable {

    public boolean isRequired() {
        return "REQUIRED".equals(executionMode);
    }

    public boolean isOptional() {
        return "OPTIONAL".equals(executionMode);
    }
}
