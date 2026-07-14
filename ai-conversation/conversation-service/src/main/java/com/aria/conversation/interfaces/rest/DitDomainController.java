package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.interfaces.rest.validation.ValidRegexPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 领域配置管理接口（需登录，仅管理员）。
 * GET    /admin/dit/domains          → 列出所有领域
 * POST   /admin/dit/domains          → 新建领域
 * PUT    /admin/dit/domains/{id}     → 更新领域
 * DELETE /admin/dit/domains/{id}     → 删除领域
 */
@RestController
@RequestMapping("/api/v1/admin/dit/domains")
@RequiredArgsConstructor
public class DitDomainController {

    private final DitManageAppService manageService;

    @GetMapping
    public R<List<DomainDO>> list() {
        return R.ok(manageService.listDomains());
    }

    @PostMapping
    public R<DomainDO> create(@RequestBody @Valid DomainRequest req) {
        DomainDO domain = new DomainDO();
        domain.setCode(req.getCode());
        domain.setName(req.getName());
        domain.setDescription(req.getDescription());
        domain.setSystemPromptAddon(req.getSystemPromptAddon());
        domain.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        domain.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
        domain.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
        return R.ok(manageService.createDomain(domain));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid DomainRequest req) {
        DomainDO domain = new DomainDO();
        domain.setId(id);
        domain.setCode(req.getCode());
        domain.setName(req.getName());
        domain.setDescription(req.getDescription());
        domain.setSystemPromptAddon(req.getSystemPromptAddon());
        domain.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        domain.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
        domain.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
        manageService.updateDomain(domain);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manageService.deleteDomain(id);
        return R.ok();
    }

    @Data
    public static class DomainRequest {
        @NotBlank(message = "code 不能为空")
        @Size(max = 64)
        private String code;

        @NotBlank(message = "name 不能为空")
        @Size(max = 128)
        private String name;

        private String description;
        private String systemPromptAddon;
        private Boolean enabled;

        /** 域路由关键词列表，命中则直接路由到该域，跳过 LLM */
        private String keywords;

        /** 域路由正则列表，命中则直接路由到该域，跳过 LLM */
        @ValidRegexPatterns
        private String patterns;
    }
}
