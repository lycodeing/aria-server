package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DIT 工具注册管理接口。
 * GET    /admin/dit/tools            → 列出所有工具
 * POST   /admin/dit/tools            → 注册新工具
 * PUT    /admin/dit/tools/{id}       → 更新工具
 * DELETE /admin/dit/tools/{id}       → 删除工具
 * POST   /admin/dit/tools/{id}/test  → 预览调用工具
 */
@RestController
@RequestMapping("/api/v1/admin/dit/tools")
@RequiredArgsConstructor
public class DitToolController {

    private final DitManageAppService manageService;
    private final HttpToolRunner httpToolRunner;

    @GetMapping
    public R<List<ToolDO>> list() {
        return R.ok(manageService.listTools());
    }

    @PostMapping
    public R<ToolDO> create(@RequestBody @Valid ToolRequest req) {
        return R.ok(manageService.createTool(toToolDO(null, req)));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid ToolRequest req) {
        manageService.updateTool(toToolDO(id, req));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manageService.deleteTool(id);
        return R.ok();
    }

    /**
     * 预览调用工具：使用指定参数实际执行一次 HTTP 工具调用，返回原始响应和 JSONPath 提取结果。
     */
    @PostMapping("/{id}/test")
    public R<TestResult> test(@PathVariable Long id,
                              @RequestBody TestRequest req) {
        ToolDO t = manageService.getToolById(id);
        // 构建一个不带 JSONPath 的配置，拿到原始响应
        ToolConfig rawConfig = new ToolConfig(
                t.getCode(), t.getName(), t.getDescription(),
                t.getToolType(), t.getHttpMethod(), t.getUrlTemplate(),
                t.getHeadersTemplate(), t.getBodyTemplate(), t.getParamSchema(),
                null,  // 不提取，返回完整响应
                t.getAuthType(), t.getAuthConfig(),
                t.getTimeoutMs() != null ? t.getTimeoutMs() : 5000,
                Boolean.TRUE.equals(t.getIsDiscoverTool())
        );
        Map<String, Object> params = req.getParams() != null ? req.getParams() : Collections.emptyMap();
        ToolCallResult result = httpToolRunner.execute(rawConfig, params, Collections.emptyMap());

        // 在原始响应基础上按配置的 JSONPath 提取
        String extractedResult = httpToolRunner.extractByJsonPath(result.getResponse(), t.getResponseJsonpath());

        return R.ok(new TestResult(
                result.getStatus().name(),
                result.getHttpStatus(),
                result.getResponse(),
                extractedResult,
                result.getDurationMs(),
                result.getErrorMsg()
        ));
    }

    private ToolDO toToolDO(Long id, ToolRequest req) {
        ToolDO tool = new ToolDO();
        tool.setId(id);
        tool.setCode(req.getCode());
        tool.setName(req.getName());
        tool.setDescription(req.getDescription());
        tool.setToolType(req.getToolType() != null ? req.getToolType() : "HTTP");
        tool.setHttpMethod(req.getHttpMethod());
        tool.setUrlTemplate(req.getUrlTemplate());
        tool.setHeadersTemplate(req.getHeadersTemplate());
        tool.setBodyTemplate(req.getBodyTemplate());
        tool.setParamSchema(req.getParamSchema() != null ? req.getParamSchema() : "{}");
        tool.setResponseJsonpath(req.getResponseJsonpath());
        tool.setAuthType(req.getAuthType() != null ? req.getAuthType() : "NONE");
        tool.setAuthConfig(req.getAuthConfig());
        tool.setTimeoutMs(req.getTimeoutMs() != null ? req.getTimeoutMs() : 5000);
        tool.setIsDiscoverTool(req.getIsDiscoverTool() != null && req.getIsDiscoverTool());
        tool.setEnabled(true);
        return tool;
    }

    @Data
    public static class ToolRequest {
        @NotBlank private String code;
        @NotBlank private String name;
        @NotBlank private String description;
        private String toolType;
        private String httpMethod;
        private String urlTemplate;
        private String headersTemplate;
        private String bodyTemplate;
        private String paramSchema;
        private String responseJsonpath;
        private String authType;
        private String authConfig;
        private Integer timeoutMs;
        private Boolean isDiscoverTool;
    }

    @Data
    public static class TestRequest {
        private Map<String, Object> params;
    }

    public record TestResult(
            String status,
            Integer httpStatus,
            String rawResponse,
            String extractedResult,
            long durationMs,
            String errorMsg
    ) {}
}
