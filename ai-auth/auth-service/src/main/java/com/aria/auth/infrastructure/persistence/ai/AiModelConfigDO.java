package com.aria.auth.infrastructure.persistence.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型配置持久化对象。
 * api_key_enc 存储格式：PLAINTEXT:{key}（开发）或 AES:{base64}（生产）。
 */
@Getter
@Setter
@TableName("cs_auth.ai_model_config")
public class AiModelConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String provider;
    private String apiProtocol;
    private String remark;
    private String baseUrl;
    /**
     * 加密存储的 API Key，格式：PLAINTEXT:{raw} 或 AES:{base64}
     */
    private String apiKeyEnc;
    private String modelName;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer timeoutSec;
    /**
     * 模型类型：CHAT=对话大模型，EMBEDDING=向量模型
     */
    private String modelType;

    @com.baomidou.mybatisplus.annotation.TableField("is_default")
    private Boolean isDefault;
    @com.baomidou.mybatisplus.annotation.TableField("is_enabled")
    private Boolean isEnabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
