package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.domain.ToolCallLogDO;
import com.aria.conversation.infrastructure.dit.mapper.ToolCallLogMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行编排器。
 *
 * <p>职责：
 * <ol>
 *   <li>串行执行所有 REQUIRED 工具，前一个结果可作为后一个的参数来源</li>
 *   <li>将 OPTIONAL 工具定义格式化为 Function Calling 所需的 JSON Schema</li>
 * </ol>
 *
 * <p>OPTIONAL 工具的实际调用在 P3 LLM 流式输出中检测 tool_call 事件后执行。
 * 本期只支持单轮工具调用（一次 LLM 响应最多触发一个工具），防止无限调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final HttpToolRunner httpToolRunner;
    private final ObjectMapper objectMapper;
    private final ToolCallLogMapper logMapper;

    /**
     * 串行执行意图绑定的所有 REQUIRED 工具。
     *
     * @param intentConfig  意图配置（含工具绑定列表）
     * @param resolvedSlots 已解析的槽位值
     * @param sessionCtx    会话上下文
     * @return 按执行顺序排列的结果列表
     */
    public List<ToolCallResult> executeRequired(IntentConfig intentConfig,
                                                Map<String, Object> resolvedSlots,
                                                Map<String, Object> sessionCtx) {
        List<IntentToolBinding> required = intentConfig.requiredTools();
        List<ToolCallResult> results = new ArrayList<>(required.size());

        Map<String, Object> accumulatedParams = new HashMap<>(sessionCtx);
        accumulatedParams.putAll(resolvedSlots);

        String sessionId = (String) sessionCtx.getOrDefault("sessionId", "");

        for (IntentToolBinding binding : required) {
            ToolConfig tool = binding.tool();
            if (!tool.toolType().equals("HTTP") && !tool.toolType().equals("BUILTIN")) {
                results.add(ToolCallResult.skipped(tool.code()));
                continue;
            }
            Map<String, Object> toolParams = resolveParamMappings(
                    binding.paramMappings(), accumulatedParams);

            ToolCallResult result = httpToolRunner.execute(tool, toolParams, sessionCtx);
            results.add(result);

            // 异步写入日志（失败不影响主流程）
            writeLog(sessionId, intentConfig.code(), tool.code(), toolParams, result);

            log.debug("[DIT] REQUIRED 工具 {} 执行结果 status={}", tool.code(), result.getStatus());

            if (result.isSuccess() && result.getResponse() != null) {
                accumulatedParams.put("_tool_" + tool.code(), result.getResponse());
            }
        }
        return results;
    }

    /**
     * 将 OPTIONAL 工具定义转换为 Function Calling JSON Schema 列表。
     *
     * <p>格式兼容 OpenAI function calling：
     * <pre>
     * [{"name":"tool_code","description":"...","parameters":{"type":"object","properties":{...}}}]
     * </pre>
     *
     * @param intentConfig 意图配置
     * @return Function Calling 工具定义列表（JSON 字符串列表）
     */
    public List<Map<String, Object>> buildFunctionDefinitions(IntentConfig intentConfig) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (IntentToolBinding binding : intentConfig.optionalTools()) {
            ToolConfig tool = binding.tool();
            Map<String, Object> def = new HashMap<>();
            def.put("name", tool.code());
            def.put("description", tool.description());
            // paramSchema 已是 JSON Schema 字符串，直接解析为 Map
            try {
                Map<String, Object> schema = tool.paramSchema() != null && !tool.paramSchema().isBlank()
                        ? objectMapper.readValue(tool.paramSchema(), new TypeReference<>() {})
                        : Map.of();
                def.put("parameters", Map.of(
                        "type", "object",
                        "properties", schema
                ));
            } catch (Exception e) {
                def.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }
            defs.add(def);
        }
        return defs;
    }

    /** 异步写入工具调用日志（失败不影响主流程）。 */
    private void writeLog(String sessionId, String intentCode, String toolCode,
                          Map<String, Object> params, ToolCallResult result) {
        try {
            ToolCallLogDO log_ = new ToolCallLogDO();
            log_.setSessionId(sessionId);
            log_.setIntentCode(intentCode);
            log_.setToolCode(toolCode);
            // 脱敏：去掉 token、password 等敏感字段
            String paramsJson = objectMapper.writeValueAsString(sanitize(params));
            log_.setParams(paramsJson);
            // 响应摘要：截断至 2000 字符
            String resp = result.getResponse();
            log_.setResponse(resp != null && resp.length() > 2000 ? resp.substring(0, 2000) : resp);
            log_.setStatus(result.getStatus());
            log_.setHttpStatus(result.getHttpStatus());
            log_.setDurationMs((int) result.getDurationMs());
            log_.setErrorMsg(result.getErrorMsg());
            log_.setCreatedAt(LocalDateTime.now());
            logMapper.insert(log_);
        } catch (Exception e) {
            log.warn("[DIT] 工具调用日志写入失败 tool={}", toolCode, e);
        }
    }

    /** 脱敏：移除 params 中可能含有的 token/password/secret 字段。 */
    private Map<String, Object> sanitize(Map<String, Object> params) {
        Map<String, Object> clean = new HashMap<>(params);
        clean.entrySet().removeIf(entry -> {
            String k = entry.getKey().toLowerCase();
            return k.contains("token") || k.contains("password")
                    || k.contains("secret") || k.contains("key");
        });
        return clean;
    }

    /**
     * 根据参数映射配置解析实际参数值。
     *
     * <p>paramMappings JSON 格式：
     * <pre>
     * {"order_id": {"source": "slot", "key": "order_id"},
     *  "token":    {"source": "session", "key": "api_token"},
     *  "status":   {"source": "literal", "value": "unpaid"}}
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveParamMappings(String paramMappingsJson,
                                                      Map<String, Object> accumulated) {
        if (paramMappingsJson == null || paramMappingsJson.isBlank()
                || "{}".equals(paramMappingsJson.trim())) {
            return new HashMap<>(accumulated);
        }
        try {
            Map<String, Object> mappings = objectMapper.readValue(
                    paramMappingsJson, new TypeReference<>() {});
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : mappings.entrySet()) {
                String paramName = entry.getKey();
                if (!(entry.getValue() instanceof Map<?,?> mapping)) continue;
                String source = (String) ((Map<String, Object>) mapping).get("source");
                String key    = (String) ((Map<String, Object>) mapping).get("key");
                Object value  = ((Map<String, Object>) mapping).get("value");
                Object resolved = switch (source != null ? source : "slot") {
                    case "slot", "session" -> accumulated.get(key);
                    case "literal"         -> value;
                    default                -> accumulated.get(key);
                };
                if (resolved != null) result.put(paramName, resolved);
            }
            return result;
        } catch (Exception e) {
            log.warn("[DIT] 参数映射解析失败: {}", paramMappingsJson, e);
            return new HashMap<>(accumulated);
        }
    }
}
