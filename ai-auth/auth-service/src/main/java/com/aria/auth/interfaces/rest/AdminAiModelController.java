package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.application.service.AiModelConfigService;
import com.aria.auth.interfaces.dto.AiModelRequest;
import com.aria.auth.interfaces.rest.vo.AiModelVO;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.response.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
 *
 * <p>所有接口使用 VO/DTO，不直接暴露 DO，防止调用方通过 JSON 修改内部字段
 * （如 createdAt、isDefault、deletedAt 等）。
 */
@RestController
@RequestMapping("/api/v1/admin/ai-models")
@SaCheckLogin
@RequiredArgsConstructor
public class AdminAiModelController {

    private final AiModelConfigService service;

    @GetMapping
    public R<Page<AiModelVO>> list(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AiModelConfigDO> doPage = service.page(pageNum, pageSize);
        // 转换为 VO：api_key 脱敏，不返回 deletedAt/createdBy 等内部字段
        List<AiModelVO> vos = doPage.getRecords().stream()
                .map(this::toVO)
                .toList();
        Page<AiModelVO> voPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        voPage.setRecords(vos);
        return R.ok(voPage);
    }

    @PostMapping
    public R<AiModelVO> create(@RequestBody @Valid AiModelRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        AiModelConfigDO created = service.create(req, userId);
        return R.ok(toVO(created));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid AiModelRequest req) {
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

    // ---- 内部转换 ----

    private AiModelVO toVO(AiModelConfigDO do_) {
        return AiModelVO.builder()
                .id(do_.getId())
                .name(do_.getName())
                .provider(do_.getProvider())
                .apiProtocol(do_.getApiProtocol())
                .baseUrl(do_.getBaseUrl())
                .maskedApiKey(service.maskApiKey(do_.getApiKeyEnc()))
                .modelName(do_.getModelName())
                .temperature(do_.getTemperature() != null ? do_.getTemperature().doubleValue() : null)
                .maxTokens(do_.getMaxTokens())
                .timeoutSec(do_.getTimeoutSec())
                .isDefault(do_.getIsDefault())
                .isEnabled(do_.getIsEnabled())
                .build();
    }
}
