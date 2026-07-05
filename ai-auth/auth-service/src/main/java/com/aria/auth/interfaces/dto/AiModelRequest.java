package com.aria.auth.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 模型配置创建/更新请求 DTO。
 * 明确限定调用方可提交的字段，防止通过 DO 直接设置 createdAt/deletedAt/isDefault 等内部字段。
 */
@Data
public class AiModelRequest {
    @NotBlank(message = "配置名称不能为空")
    private String name;

    @NotBlank(message = "供应商不能为空")
    private String provider;

    @NotBlank(message = "API 协议不能为空")
    private String apiProtocol;

    /**
     * 模型类型：CHAT=对话大模型，EMBEDDING=向量模型
     */
    @NotBlank(message = "模型类型不能为空")
    private String modelType;

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /**
     * api_key 加密串，更新时为空则保留原值
     */
    private String apiKeyEnc;

    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    private Double temperature;
    private Integer maxTokens;
    private Integer timeoutSec;

    @NotNull(message = "是否启用不能为空")
    private Boolean isEnabled;
}
