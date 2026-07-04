package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.conversation.infrastructure.persistence.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "cs_conversation.cs_tool", autoResultMap = true)
public class ToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String description;

    /** HTTP=通用 HTTP 调用，BUILTIN=Java 内置实现 */
    private String toolType;

    /** GET / POST / PUT / DELETE */
    private String httpMethod;

    /** URL 模板，支持 {slot_name} 占位符 */
    private String urlTemplate;

    /** 请求头模板，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String headersTemplate;

    /** 请求体模板（POST），JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String bodyTemplate;

    /** 参数 JSON Schema，供 LLM Function Calling 使用 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String paramSchema;

    /** 从响应中提取结果的 JSONPath，如 "$.data" */
    private String responseJsonpath;

    /** NONE / API_KEY / BEARER / BASIC */
    private String authType;

    /** 认证配置，AES 加密存储，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String authConfig;

    private Integer timeoutMs;

    /** true=可作为槽位 DISCOVER 级发现工具 */
    private Boolean isDiscoverTool;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
