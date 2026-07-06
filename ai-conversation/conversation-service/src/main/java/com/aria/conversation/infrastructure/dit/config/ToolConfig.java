package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;

/**
 * 工具配置（只读，从 cs_tool 映射，存入 Redis 缓存）。
 *
 * @param code             工具唯一标识
 * @param name             工具名称
 * @param description      给 LLM 的工具说明（Function Calling description）
 * @param toolType         HTTP / BUILTIN
 * @param httpMethod       GET / POST / PUT / DELETE
 * @param urlTemplate      URL 模板，支持 {slot_name} 占位符
 * @param headersTemplate  请求头模板（JSON 字符串）
 * @param bodyTemplate     请求体模板（JSON 字符串，POST 使用）
 * @param paramSchema      参数 JSON Schema（JSON 字符串）
 * @param responseJsonpath 从响应提取结果的 JSONPath，如 "$.data"
 * @param authType         NONE / API_KEY / BEARER / BASIC
 * @param authConfig       认证配置（JSON 字符串，已加密）
 * @param timeoutMs        超时毫秒
 * @param isDiscoverTool   是否可作为 DISCOVER 级发现工具
 */
public record ToolConfig(
        String code,
        String name,
        String description,
        String toolType,
        String httpMethod,
        String urlTemplate,
        String headersTemplate,
        String bodyTemplate,
        String paramSchema,
        String responseJsonpath,
        String authType,
        String authConfig,
        int timeoutMs,
        boolean isDiscoverTool
) implements Serializable {}
