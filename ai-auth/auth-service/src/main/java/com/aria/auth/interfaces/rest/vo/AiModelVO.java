package com.aria.auth.interfaces.rest.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AI 模型配置响应 VO（接口层专用，api_key 已脱敏）。
 * 不直接暴露 DO 字段，保护内部实现细节，防止调用方通过 JSON 修改不该修改的字段。
 */
@Data
@Builder
public class AiModelVO {
    private Long id;
    private String name;
    private String provider;
    private String apiProtocol;
    private String baseUrl;
    /**
     * 模型类型：CHAT=对话大模型，EMBEDDING=向量模型
     */
    private String modelType;
    /**
     * 脱敏后的 API Key（格式：前4位****后4位）
     */
    private String maskedApiKey;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private Integer timeoutSec;
    private Boolean isDefault;
    private Boolean isEnabled;
}
