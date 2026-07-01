package com.aria.customerservice.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.customerservice.auth.application.service.AiModelConfigService;
import com.aria.customerservice.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.response.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模型配置管理接口（需登录）。
 *
 * <pre>
 * GET    /api/v1/admin/ai-models           分页列表（api_key 脱敏展示）
 * POST   /api/v1/admin/ai-models           新建配置
 * PUT    /api/v1/admin/ai-models/{id}      更新配置（不传 api_key 则不更新）
 * PATCH  /api/v1/admin/ai-models/{id}/default  设为默认（原默认自动取消）
 * DELETE /api/v1/admin/ai-models/{id}     软删除（默认配置不允许删除）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/ai-models")
@SaCheckLogin
@RequiredArgsConstructor
public class AdminAiModelController {

    private final AiModelConfigService service;

    @GetMapping
    public R<Page<AiModelConfigDO>> list(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AiModelConfigDO> page = service.page(pageNum, pageSize);
        // 返回前对 api_key_enc 脱敏，防止明文泄露
        page.getRecords().forEach(r -> r.setApiKeyEnc(service.maskApiKey(r.getApiKeyEnc())));
        return R.ok(page);
    }

    @PostMapping
    public R<AiModelConfigDO> create(@RequestBody AiModelConfigDO req) {
        Long userId = StpUtil.getLoginIdAsLong();
        AiModelConfigDO created = service.create(req, userId);
        // 返回体也脱敏
        created.setApiKeyEnc(service.maskApiKey(created.getApiKeyEnc()));
        return R.ok(created);
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AiModelConfigDO req) {
        service.update(id, req);
        return R.ok();
    }

    @PatchMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        service.setDefault(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
