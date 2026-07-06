package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
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
 * GET    /api/v1/admin/ai-models?modelType=CHAT|EMBEDDING  分页列表（前端 TAB 切换时传 modelType）
 * POST   /api/v1/admin/ai-models                           新建配置
 * PUT    /api/v1/admin/ai-models/{id}                      更新配置（不传 api_key 则不更新）
 * PATCH  /api/v1/admin/ai-models/{id}/default              设为默认（按 model_type 范围独立生效）
 * DELETE /api/v1/admin/ai-models/{id}                      软删除（默认配置不允许删除）
 * </pre>
 *
 * <p>model_type 区分 CHAT（对话大模型）和 EMBEDDING（向量模型），
 * 前端同一页面使用 TAB 标签页切换，后端通过 modelType 查询参数过滤。
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

    /**
     * 分页列表。
     * modelType 可选，为空时返回全部类型；前端 TAB 切换时传 "CHAT" 或 "EMBEDDING"。
     */
    @GetMapping
    public R<Page<AiModelVO>> list(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false)    String modelType) {
        Page<AiModelConfigDO> doPage = service.page(pageNum, pageSize, modelType);
        List<AiModelVO> vos = doPage.getRecords().stream()
                .map(this::toVO)
                .toList();
        Page<AiModelVO> voPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        voPage.setRecords(vos);
        return R.ok(voPage);
    }

    @PostMapping
    @SaCheckPermission("system:ai-model:create")
    public R<AiModelVO> create(@RequestBody @Valid AiModelRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        AiModelConfigDO created = service.create(req, userId);
        return R.ok(toVO(created));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:ai-model:update")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid AiModelRequest req) {
        service.update(id, req);
        return R.ok();
    }

    /**
     * 设为默认：按 model_type 范围独立生效，CHAT / EMBEDDING 互不影响。
     */
    @PutMapping("/{id}/default")
    @SaCheckPermission("system:ai-model:set-default")
    public R<Void> setDefault(@PathVariable Long id) {
        service.setDefault(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:ai-model:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    /**
     * 测试模型连通性。
     * CHAT 模型：发送极简非流式请求，验证 API Key + 地址有效性。
     * EMBEDDING 模型：发送一条测试文本，验证向量服务可访问。
     *
     * @return { success, latencyMs, message }
     */
    @PostMapping("/{id}/test")
    public R<java.util.Map<String, Object>> testConnection(@PathVariable Long id) {
        return R.ok(service.testConnection(id));
    }

    // ---- 内部转换 ----

    private AiModelVO toVO(AiModelConfigDO do_) {
        return AiModelVO.builder()
                .id(do_.getId())
                .name(do_.getName())
                .provider(do_.getProvider())
                .apiProtocol(do_.getApiProtocol())
                .modelType(do_.getModelType())
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
