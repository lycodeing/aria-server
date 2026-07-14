package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DIT 意图/槽位/绑定管理接口。
 * 意图：  GET/POST /admin/dit/intents?domainId=  PUT/DELETE /admin/dit/intents/{id}
 * 槽位：  GET/POST /admin/dit/slots?intentId=    PUT/DELETE /admin/dit/slots/{id}
 * 绑定：  GET/POST /admin/dit/bindings?intentId= DELETE /admin/dit/bindings/{id}
 */
@RestController
@RequestMapping("/api/v1/admin/dit")
@RequiredArgsConstructor
public class DitIntentController {

    private final DitManageAppService manageService;

    // ---- 意图 ----

    @GetMapping("/intents")
    public R<List<IntentDO>> listIntents(@RequestParam Long domainId) {
        return R.ok(manageService.listIntents(domainId));
    }

    @PostMapping("/intents")
    public R<IntentDO> createIntent(@RequestBody @Valid IntentRequest req) {
        IntentDO intent = new IntentDO();
        intent.setDomainId(req.getDomainId());
        intent.setCode(req.getCode());
        intent.setName(req.getName());
        intent.setDescription(req.getDescription());
        intent.setExampleQueries(req.getExampleQueries());
        intent.setAutoTransfer(req.getAutoTransfer() != null && req.getAutoTransfer());
        intent.setSkipRag(req.getSkipRag() != null && req.getSkipRag());
        intent.setFallbackReply(req.getFallbackReply());
        intent.setEnabled(true);
        intent.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        intent.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
        intent.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
        return R.ok(manageService.createIntent(intent));
    }

    @PutMapping("/intents/{id}")
    public R<Void> updateIntent(@PathVariable Long id, @RequestBody @Valid IntentRequest req) {
        IntentDO intent = new IntentDO();
        intent.setId(id);
        intent.setCode(req.getCode());
        intent.setName(req.getName());
        intent.setDescription(req.getDescription());
        intent.setExampleQueries(req.getExampleQueries());
        intent.setAutoTransfer(req.getAutoTransfer() != null && req.getAutoTransfer());
        intent.setSkipRag(req.getSkipRag() != null && req.getSkipRag());
        intent.setFallbackReply(req.getFallbackReply());
        intent.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        intent.setKeywords(req.getKeywords() != null ? req.getKeywords() : "[]");
        intent.setPatterns(req.getPatterns() != null ? req.getPatterns() : "[]");
        manageService.updateIntent(intent);
        return R.ok();
    }

    @DeleteMapping("/intents/{id}")
    public R<Void> deleteIntent(@PathVariable Long id) {
        manageService.deleteIntent(id);
        return R.ok();
    }

    // ---- 槽位 ----

    @GetMapping("/slots")
    public R<List<IntentSlotDO>> listSlots(@RequestParam Long intentId) {
        return R.ok(manageService.listSlots(intentId));
    }

    @PostMapping("/slots")
    public R<IntentSlotDO> createSlot(@RequestBody @Valid SlotRequest req) {
        IntentSlotDO slot = new IntentSlotDO();
        slot.setIntentId(req.getIntentId());
        slot.setSlotName(req.getSlotName());
        slot.setSlotType(req.getSlotType() != null ? req.getSlotType() : "string");
        slot.setDescription(req.getDescription());
        slot.setRequired(req.getRequired() != null && req.getRequired());
        slot.setResolveStrategy(req.getResolveStrategy());
        slot.setSessionKey(req.getSessionKey());
        slot.setDiscoverToolCode(req.getDiscoverToolCode());
        slot.setDiscoverFixedParams(req.getDiscoverFixedParams());
        slot.setAskUserPrompt(req.getAskUserPrompt());
        slot.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok(manageService.createSlot(slot));
    }

    @PutMapping("/slots/{id}")
    public R<Void> updateSlot(@PathVariable Long id, @RequestBody @Valid SlotRequest req) {
        IntentSlotDO slot = new IntentSlotDO();
        slot.setId(id);
        slot.setSlotName(req.getSlotName());
        slot.setSlotType(req.getSlotType() != null ? req.getSlotType() : "string");
        slot.setDescription(req.getDescription());
        slot.setRequired(req.getRequired() != null && req.getRequired());
        slot.setResolveStrategy(req.getResolveStrategy());
        slot.setSessionKey(req.getSessionKey());
        slot.setDiscoverToolCode(req.getDiscoverToolCode());
        slot.setDiscoverFixedParams(req.getDiscoverFixedParams());
        slot.setAskUserPrompt(req.getAskUserPrompt());
        slot.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        manageService.updateSlot(slot);
        return R.ok();
    }

    @DeleteMapping("/slots/{id}")
    public R<Void> deleteSlot(@PathVariable Long id) {
        manageService.deleteSlot(id);
        return R.ok();
    }

    // ---- 意图-工具绑定 ----

    @GetMapping("/bindings")
    public R<List<IntentToolDO>> listBindings(@RequestParam Long intentId) {
        return R.ok(manageService.listBindings(intentId));
    }

    @PostMapping("/bindings")
    public R<IntentToolDO> createBinding(@RequestBody @Valid BindingRequest req) {
        IntentToolDO binding = new IntentToolDO();
        binding.setIntentId(req.getIntentId());
        binding.setToolId(req.getToolId());
        binding.setExecutionMode(req.getExecutionMode() != null ? req.getExecutionMode() : "OPTIONAL");
        binding.setExecutionOrder(req.getExecutionOrder() != null ? req.getExecutionOrder() : 0);
        binding.setParamMappings(req.getParamMappings() != null ? req.getParamMappings() : "{}");
        return R.ok(manageService.createBinding(binding));
    }

    @DeleteMapping("/bindings/{id}")
    public R<Void> deleteBinding(@PathVariable Long id) {
        manageService.deleteBinding(id);
        return R.ok();
    }

    // ---- Request 类 ----

    @Data
    public static class IntentRequest {
        @NotNull private Long domainId;
        @NotBlank private String code;
        @NotBlank private String name;
        @NotBlank private String description;
        private String exampleQueries;
        private Boolean autoTransfer;
        private Boolean skipRag;
        private String fallbackReply;
        private Integer sortOrder;

        /** 关键词列表，JSON 字符串，如 ["转人工","找真人"]，大小写不敏感全文包含匹配 */
        private String keywords;

        /** 正则表达式列表，JSON 字符串，如 ["^我要.*转.*人工"]，Java Pattern 语法 */
        private String patterns;
    }

    @Data
    public static class SlotRequest {
        @NotNull private Long intentId;
        @NotBlank private String slotName;
        private String slotType;
        @NotBlank private String description;
        private Boolean required;
        private String resolveStrategy;
        private String sessionKey;
        private String discoverToolCode;
        private String discoverFixedParams;
        private String askUserPrompt;
        private Integer sortOrder;
    }

    @Data
    public static class BindingRequest {
        @NotNull private Long intentId;
        @NotNull private Long toolId;
        private String executionMode;
        private Integer executionOrder;
        private String paramMappings;
    }
}
