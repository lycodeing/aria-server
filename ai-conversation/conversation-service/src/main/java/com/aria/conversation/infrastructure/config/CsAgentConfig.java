package com.aria.conversation.infrastructure.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 客服接待配置（对应 system_config.config_key = 'cs.agent.config'）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CsAgentConfig {

    /** 每个客服最大同时接待会话数，默认 5 */
    private int maxSessionsPerAgent = 5;

    /** 降级默认值，auth-service 不可用时使用 */
    public static CsAgentConfig defaults() {
        return new CsAgentConfig(5);
    }
}
