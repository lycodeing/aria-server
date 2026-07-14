package com.aria.conversation.infrastructure.ai.tool;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolSpecBuilder")
class ToolSpecBuilderTest {

    private ToolSpecBuilder builder;

    private static ToolConfig tool(String code, String description, String paramSchema) {
        return new ToolConfig(code, "name", description, "HTTP", "GET",
                "http://x", "{}", null, paramSchema, null, "NONE", "{}", 5000, false);
    }

    @BeforeEach
    void setUp() {
        builder = new ToolSpecBuilder(new ObjectMapper());
    }

    @Test
    @DisplayName("paramSchema 为空时构建无参数 ToolSpecification")
    void build_emptySchema_noParams() {
        ToolConfig tc = tool("query_order", "查询订单", null);
        ToolSpecification spec = builder.build(tc);

        assertThat(spec.name()).isEqualTo("query_order");
        assertThat(spec.description()).isEqualTo("查询订单");
        assertThat(spec.parameters().properties()).isEmpty();
    }

    @Test
    @DisplayName("string 类型参数正确映射")
    void build_stringType_mapsCorrectly() {
        String schema = "{\"properties\":{\"orderId\":{\"type\":\"string\",\"description\":\"订单号\"}},"
                + "\"required\":[\"orderId\"]}";
        ToolSpecification spec = builder.build(tool("t", "desc", schema));

        assertThat(spec.parameters().properties()).containsKey("orderId");
        assertThat(spec.parameters().properties().get("orderId"))
                .isInstanceOf(JsonStringSchema.class);
        assertThat(spec.parameters().required()).contains("orderId");
    }

    @Test
    @DisplayName("integer / number / boolean / array 类型正确映射")
    void build_typeMappings() {
        String schema = "{\"properties\":{"
                + "\"qty\":{\"type\":\"integer\"},"
                + "\"price\":{\"type\":\"number\"},"
                + "\"express\":{\"type\":\"boolean\"},"
                + "\"tags\":{\"type\":\"array\"}"
                + "}}";
        ToolSpecification spec = builder.build(tool("t", "d", schema));

        assertThat(spec.parameters().properties().get("qty")).isInstanceOf(JsonIntegerSchema.class);
        assertThat(spec.parameters().properties().get("price")).isInstanceOf(JsonNumberSchema.class);
        assertThat(spec.parameters().properties().get("express")).isInstanceOf(JsonBooleanSchema.class);
        assertThat(spec.parameters().properties().get("tags")).isInstanceOf(JsonArraySchema.class);
    }

    @Test
    @DisplayName("非法 JSON Schema 降级为无参数，不抛出异常")
    void build_invalidSchema_fallbackToEmpty() {
        ToolSpecification spec = builder.build(tool("t", "d", "{invalid-json}"));
        assertThat(spec.parameters().properties()).isEmpty();
    }
}
