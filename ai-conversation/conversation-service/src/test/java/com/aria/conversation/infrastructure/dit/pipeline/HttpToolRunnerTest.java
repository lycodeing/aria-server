package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HttpToolRunner 工具执行器")
class HttpToolRunnerTest {

    private HttpToolRunner runner;

    @BeforeEach
    void setUp() {
        runner = new HttpToolRunner(new ObjectMapper(),
                org.springframework.web.reactive.function.client.WebClient.builder());
    }

    // ---- replacePlaceholders ----

    @Test
    @DisplayName("replacePlaceholders: 替换单个占位符")
    void replacePlaceholders_single() {
        String result = runner.replacePlaceholders(
                "https://api.shop.com/orders/{order_id}",
                Map.of("order_id", "ORD001"));
        assertEquals("https://api.shop.com/orders/ORD001", result);
    }

    @Test
    @DisplayName("replacePlaceholders: 替换多个占位符")
    void replacePlaceholders_multiple() {
        String result = runner.replacePlaceholders(
                "https://api.shop.com/{domain}/orders/{order_id}",
                Map.of("domain", "ecommerce", "order_id", "ORD001"));
        assertEquals("https://api.shop.com/ecommerce/orders/ORD001", result);
    }

    @Test
    @DisplayName("replacePlaceholders: 参数不存在时替换为空串")
    void replacePlaceholders_missingParam() {
        String result = runner.replacePlaceholders(
                "https://api.shop.com/orders/{order_id}",
                Map.of());
        assertEquals("https://api.shop.com/orders/", result);
    }

    @Test
    @DisplayName("replacePlaceholders: null template 返回空串")
    void replacePlaceholders_nullTemplate() {
        assertEquals("", runner.replacePlaceholders(null, Map.of()));
    }

    // ---- extractByJsonPath ----

    @Test
    @DisplayName("extractByJsonPath: 简单字段提取")
    void extractByJsonPath_simpleField() {
        String json = "{\"data\":{\"orderId\":\"ORD001\",\"amount\":99.0}}";
        String result = runner.extractByJsonPath(json, "$.data");
        assertTrue(result.contains("ORD001"));
    }

    @Test
    @DisplayName("extractByJsonPath: 嵌套字段提取")
    void extractByJsonPath_nestedField() {
        String json = "{\"data\":{\"orderId\":\"ORD001\"}}";
        String result = runner.extractByJsonPath(json, "$.data.orderId");
        assertEquals("ORD001", result);
    }

    @Test
    @DisplayName("extractByJsonPath: jsonPath 为 null 返回原始响应")
    void extractByJsonPath_nullPath() {
        String json = "{\"code\":200}";
        assertEquals(json, runner.extractByJsonPath(json, null));
    }

    @Test
    @DisplayName("extractByJsonPath: jsonPath 为 $ 返回原始响应")
    void extractByJsonPath_rootPath() {
        String json = "{\"code\":200}";
        assertEquals(json, runner.extractByJsonPath(json, "$"));
    }

    @Test
    @DisplayName("extractByJsonPath: 路径不存在返回原始响应")
    void extractByJsonPath_missingPath() {
        String json = "{\"code\":200}";
        assertEquals(json, runner.extractByJsonPath(json, "$.data.nonExistent"));
    }

    @Test
    @DisplayName("extractByJsonPath: null 响应返回空串")
    void extractByJsonPath_nullResponse() {
        assertEquals("", runner.extractByJsonPath(null, "$.data"));
    }
}
