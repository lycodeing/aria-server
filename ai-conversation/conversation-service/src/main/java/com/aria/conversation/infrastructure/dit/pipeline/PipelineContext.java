package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * DIT Pipeline 执行上下文，贯穿 Steps 1-5 传递数据。
 */
@Data
@Builder
public class PipelineContext {

    /** 会话 ID */
    private final String sessionId;

    /** 用户消息 */
    private final String userMessage;

    /** 领域标识（前端传入） */
    private final String domainCode;

    /** Step 1 加载结果：领域配置 */
    private DomainConfig domainConfig;

    /** Step 2 识别结果：领域意图 code */
    private String intentCode;

    /** Step 2 识别结果：命中的意图配置（null 表示 UNKNOWN） */
    private IntentConfig intentConfig;

    /** Step 3 解析结果：全部已解析槽位 */
    private Map<String, Object> resolvedSlots;

    /** 会话上下文（登录用户信息等，由外部注入） */
    @Builder.Default
    private Map<String, Object> sessionCtx = Map.of();
}
