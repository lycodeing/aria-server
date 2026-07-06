package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 通用 HTTP 工具执行器。
 *
 * <p>根据 {@link ToolConfig} 中的 url_template、http_method、headers_template、
 * body_template 动态构造 HTTP 请求，执行并返回 {@link ToolCallResult}。
 *
 * <p>支持 {slot_name} 占位符替换：在 URL 路径、请求头、请求体中的 {xxx} 
 * 均会被 resolvedSlots 中对应的值替换。
 *
 * <p>按 baseUrl 缓存 WebClient（Caffeine，最多 128 个，10 分钟未访问自动驱逐），
 * 避免重复创建连接池。
 */
@Slf4j
@Component
public class HttpToolRunner {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
    /** 匹配数组索引片段，如 current_condition[0] 或 [0] */
    private static final Pattern ARRAY_INDEX = Pattern.compile("^([^\\[]*)?\\[(\\d+)]$");

    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    /** baseUrl → WebClient 缓存，最多缓存 128 个 baseUrl，10 分钟未访问自动驱逐 */
    private final Cache<String, WebClient> clientCache = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();
    private final Map<String, HttpAuthStrategy> authStrategyMap;

    public HttpToolRunner(ObjectMapper objectMapper,
                          WebClient.Builder webClientBuilder,
                          List<HttpAuthStrategy> authStrategies) {
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.authStrategyMap = authStrategies.stream()
                .collect(Collectors.toMap(HttpAuthStrategy::authType, s -> s));
    }

    /**
     * 执行 HTTP 工具调用。
     *
     * @param tool          工具配置
     * @param resolvedSlots 已解析的槽位值（用于占位符替换）
     * @param sessionCtx    会话上下文（补充参数来源）
     * @return 工具调用结果
     */
    public ToolCallResult execute(ToolConfig tool,
                                  Map<String, Object> resolvedSlots,
                                  Map<String, Object> sessionCtx) {
        long start = System.currentTimeMillis();
        try {
            // 合并参数来源：槽位 + session 上下文
            Map<String, Object> params = new HashMap<>(sessionCtx);
            params.putAll(resolvedSlots);

            // 替换 URL 占位符
            String url = replacePlaceholders(tool.urlTemplate(), params);

            // 构建 WebClient
            String baseUrl = extractBaseUrl(url);
            WebClient client = clientCache.get(baseUrl, u ->
                    webClientBuilder.clone().baseUrl(u)
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                            .build());

            // 构建请求头
            HttpHeaders headers = buildHeaders(tool, params);

            // 发起请求
            String method = tool.httpMethod() != null ? tool.httpMethod().toUpperCase() : "GET";
            String responseBody = switch (method) {
                case "POST", "PUT", "PATCH" -> {
                    String body = tool.bodyTemplate() != null
                            ? replacePlaceholders(tool.bodyTemplate(), params)
                            : "{}";
                    yield client.method(org.springframework.http.HttpMethod.valueOf(method))
                            .uri(url.substring(baseUrl.length()))
                            .headers(h -> h.addAll(headers))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofMillis(tool.timeoutMs()))
                            .block();
                }
                default -> client.get()
                        .uri(url.substring(baseUrl.length()))
                        .headers(h -> h.addAll(headers))
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(tool.timeoutMs()))
                        .block();
            };

            // 按 JSONPath 提取结果
            String extracted = extractByJsonPath(responseBody, tool.responseJsonpath());
            long duration = System.currentTimeMillis() - start;
            log.debug("[DIT] 工具调用成功 tool={} duration={}ms", tool.code(), duration);
            return ToolCallResult.success(tool.code(), extracted, 200, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            // reactor timeout 包装成 ReactiveException，检查 cause
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.TimeoutException) {
                log.warn("[DIT] 工具调用超时 tool={} timeout={}ms", tool.code(), tool.timeoutMs());
                return ToolCallResult.timeout(tool.code(), duration);
            }
            log.warn("[DIT] 工具调用失败 tool={}", tool.code(), e);
            return ToolCallResult.error(tool.code(), e.getMessage(), duration);
        }
    }

    /**
     * 替换字符串中的 {slot_name} 占位符。
     */
    String replacePlaceholders(String template, Map<String, Object> params) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object val = params.get(key);
            m.appendReplacement(sb, val != null ? Matcher.quoteReplacement(val.toString()) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从响应体中按 JSONPath 提取值。
     * 支持格式：
     *   $.field
     *   $.nested.field
     *   $.array[0]
     *   $.array[0].field
     *   $.field[0].nested[1].value
     * 不支持通配符（*）和过滤表达式（?()）。
     * 为 null 或格式不支持时返回原始响应。
     */
    public String extractByJsonPath(String responseBody, String jsonPath) {
        if (responseBody == null) return "";
        if (jsonPath == null || jsonPath.isBlank() || "$".equals(jsonPath)) return responseBody;
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            // 去掉 "$." 或 "$" 前缀
            String path = jsonPath.startsWith("$.") ? jsonPath.substring(2)
                        : jsonPath.startsWith("$") ? jsonPath.substring(1)
                        : jsonPath;
            if (path.isBlank()) return responseBody;

            // 按 "." 分割路径段，每段再检查数组下标
            for (String segment : path.split("\\.")) {
                Matcher m = ARRAY_INDEX.matcher(segment);
                if (m.matches()) {
                    // 如 "current_condition[0]" 或 "[0]"
                    String fieldName = m.group(1);
                    int idx = Integer.parseInt(m.group(2));
                    if (fieldName != null && !fieldName.isEmpty()) {
                        node = node.path(fieldName);
                        if (node.isMissingNode()) return responseBody;
                    }
                    if (!node.isArray() || idx >= node.size()) return responseBody;
                    node = node.get(idx);
                } else {
                    node = node.path(segment);
                }
                if (node == null || node.isMissingNode()) return responseBody;
            }
            return node.isTextual() ? node.asText() : node.toString();
        } catch (Exception e) {
            return responseBody;
        }
    }

    private HttpHeaders buildHeaders(ToolConfig tool, Map<String, Object> params) {
        HttpHeaders headers = new HttpHeaders();
        // 认证头（策略模式，新增认证方式只需添加 HttpAuthStrategy 实现）
        // 注意：authConfig 中的 token / api_key_value 字段当前以明文存储。
        // 生产部署前需接入加密存储（如 KMS/AES），并在此处调用解密服务后再使用。
        authStrategyMap.getOrDefault(
                tool.authType() != null ? tool.authType() : "NONE",
                authStrategyMap.getOrDefault("NONE", new NoAuthStrategy()))
            .apply(headers, tool.authConfig(), objectMapper);
        // 自定义请求头
        if (tool.headersTemplate() != null && !tool.headersTemplate().isBlank()) {
            try {
                JsonNode hNode = objectMapper.readTree(
                        replacePlaceholders(tool.headersTemplate(), params));
                hNode.fields().forEachRemaining(e ->
                        headers.set(e.getKey(), e.getValue().asText()));
            } catch (Exception e) {
                log.warn("[DIT] 自定义请求头模板解析失败 tool={}", tool.code(), e);
            }
        }
        return headers;
    }

    /**
     * 提取 URL 的 baseUrl（协议 + 主机 + 端口）。
     * 例如：https://api.shop.com/v1/weather → https://api.shop.com
     */
    private String extractBaseUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            log.warn("[DIT] extractBaseUrl 解析失败，回退使用原始 URL: {}", url, e);
            return url;
        }
    }
}
