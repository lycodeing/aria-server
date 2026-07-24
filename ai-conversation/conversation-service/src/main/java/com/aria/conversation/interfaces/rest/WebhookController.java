package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.WebhookAppService;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SLA Webhook 通知配置 Controller。
 * 管理飞书/钉钉/企微/自定义 Webhook 配置，供 SLA 策略绑定后推送告警。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/sla/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookAppService webhookAppService;

    @GetMapping
    @SaCheckPermission("system:sla:manage")
    public R<List<WebhookConfigEntity>> list() {
        return R.ok(webhookAppService.listWebhooks());
    }

    @PostMapping
    @SaCheckPermission("system:sla:manage")
    public R<WebhookConfigEntity> create(@RequestBody @Valid WebhookReq req) {
        WebhookConfigEntity entity = buildEntity(null, req);
        return R.ok(webhookAppService.createWebhook(entity));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> update(@PathVariable Long id,
                           @RequestBody @Valid WebhookReq req) {
        webhookAppService.updateWebhook(id, buildEntity(id, req));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> delete(@PathVariable Long id) {
        webhookAppService.deleteWebhook(id);
        return R.ok();
    }

    /**
     * 发送测试消息，验证 Webhook 配置可用。
     * 发送一条模拟告警，前端展示成功/失败反馈。
     */
    @PostMapping("/{id}/test")
    @SaCheckPermission("system:sla:manage")
    public R<Void> test(@PathVariable Long id) {
        webhookAppService.testWebhook(id);
        return R.ok();
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class WebhookReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String type;           // FEISHU | DINGTALK | WECOM | CUSTOM
        // TODO: 生产环境建议增加 ConstraintValidator 过滤 RFC-1918 私有地址段（10.x/172.16.x/192.168.x）
        @NotBlank
        @Pattern(regexp = "^https://.*", message = "Webhook URL 必须使用 HTTPS 协议")
        @Size(max = 500)
        private String url;
        private String secret;                   // 飞书/钉钉签名密钥，可空
        private Map<String, String> customHeaders; // CUSTOM 类型专用
        private String messageTemplate;          // 自定义模板，空则用默认
        private Integer isEnabled = 1;
    }

    private WebhookConfigEntity buildEntity(Long id, WebhookReq req) {
        return WebhookConfigEntity.builder()
                .id(id)
                .name(req.getName())
                .type(req.getType())
                .url(req.getUrl())
                .secret(req.getSecret())
                .customHeaders(req.getCustomHeaders())
                .messageTemplate(req.getMessageTemplate())
                .isEnabled(req.getIsEnabled())
                .build();
    }
}
