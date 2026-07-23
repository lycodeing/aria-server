package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook 通知配置实体（对应 cs_conversation.cs_webhook_config 表）。
 *
 * <p>{@code type} 枚举值：FEISHU | DINGTALK | WECOM | CUSTOM
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_webhook_config", autoResultMap = true)
public class WebhookConfigEntity {

    /** 主键（BIGSERIAL 自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称，全局唯一 */
    private String name;

    /** Webhook 类型：FEISHU | DINGTALK | WECOM | CUSTOM */
    private String type;

    /** Webhook 请求地址 */
    private String url;

    /** 签名密钥（飞书/钉钉需要），明文存储 */
    private String secret;

    /** CUSTOM 类型的自定义请求头，key=header名，value=header值 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> customHeaders;

    /** 自定义消息模板，支持 ${变量}，空则用平台默认模板 */
    private String messageTemplate;

    /** 是否启用：1=启用，0=禁用 */
    private Integer isEnabled;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
