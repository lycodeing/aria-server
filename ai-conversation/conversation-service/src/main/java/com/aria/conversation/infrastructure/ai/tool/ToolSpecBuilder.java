package com.aria.conversation.infrastructure.ai.tool;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具规格构造器。
 *
 * <p>将 {@link ToolConfig#paramSchema()} 定义的 JSON Schema 字符串解析为
 * LangChain4j {@link ToolSpecification}，支持的类型映射：
 * <ul>
 *   <li>string / object / 未知 → {@link JsonStringSchema}（降级）</li>
 *   <li>integer → {@link JsonIntegerSchema}</li>
 *   <li>number  → {@link JsonNumberSchema}</li>
 *   <li>boolean → {@link JsonBooleanSchema}</li>
 *   <li>array   → {@link JsonArraySchema}（items 默认 string）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSpecBuilder {

    /** JSON 反序列化工具，用于解析 paramSchema JSON Schema 字符串 */
    private final ObjectMapper objectMapper;

    /**
     * 将工具配置转换为 LangChain4j 工具规格。
     *
     * <p>paramSchema 为空或 JSON 解析失败时，参数 Schema 为空（无参数工具），
     * 不抛出异常，降级为可用状态，确保工具列表构建不被单个异常中断。
     *
     * @param tc 工具配置，含 code（唯一标识）、description（LLM 使用说明）、paramSchema（参数定义）
     * @return LangChain4j {@link ToolSpecification}，供 AiServices ToolProvider 注册使用
     */
    public ToolSpecification build(ToolConfig tc) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        if (StringUtils.isNotBlank(tc.paramSchema())) {
            try {
                Map<String, Object> schema = objectMapper.readValue(
                        tc.paramSchema(), new TypeReference<>() {});
                parseProperties(schema, schemaBuilder);
                parseRequired(schema, schemaBuilder);
            } catch (Exception e) {
                log.error("[ToolSpecBuilder] paramSchema 解析失败 tool={}", tc.code(), e);
            }
        }
        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(schemaBuilder.build())
                .build();
    }

    /**
     * 解析 JSON Schema 的 "properties" 节点，将每个属性按类型映射为对应的 JsonSchemaElement。
     */
    private void parseProperties(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        props.forEach((name, def) -> {
            if (!(def instanceof Map<?, ?> propDef)) {
                builder.addProperty(name, JsonStringSchema.builder().build());
                return;
            }
            String desc = safeString(propDef.get("description"));
            builder.addProperty(name, mapType(safeString(propDef.get("type")), desc));
        });
    }

    /**
     * 解析 JSON Schema 的 "required" 数组并设置到 builder。
     */
    private void parseRequired(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }
    }

    /**
     * 将 JSON Schema type 字符串映射为对应的 LangChain4j JsonSchemaElement。
     */
    private JsonSchemaElement mapType(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array"   -> JsonArraySchema.builder().description(description)
                    .items(JsonStringSchema.builder().build()).build();
            default        -> JsonStringSchema.builder().description(description).build();
        };
    }

    /** 安全地将 Object 转换为 String，null 或非 String 类型返回空串，避免 NPE。 */
    private String safeString(Object value) {
        return value instanceof String s ? s : "";
    }
}
