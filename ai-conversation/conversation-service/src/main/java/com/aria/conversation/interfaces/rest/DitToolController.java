package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 工具注册管理接口。
 * GET    /admin/dit/tools        → 列出所有工具
 * POST   /admin/dit/tools        → 注册新工具
 * PUT    /admin/dit/tools/{id}   → 更新工具
 * DELETE /admin/dit/tools/{id}   → 删除工具
 */
@RestController
@RequestMapping("/api/v1/admin/dit/tools")
@RequiredArgsConstructor
public class DitToolController {

    private final DitManageAppService manageService;

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
}
