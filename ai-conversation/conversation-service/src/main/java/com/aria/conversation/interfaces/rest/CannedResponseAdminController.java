// interfaces/rest/CannedResponseAdminController.java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupDO;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 快捷回复管理端接口（管理员）。
 * 分组 CRUD + 公共快捷回复 CRUD。
 * 权限校验依赖 Sa-Token 拦截器，controller 层不重复鉴权。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class CannedResponseAdminController {

    private final CannedResponseAppService service;

    // ── 分组接口 ──────────────────────────────────────────

    @GetMapping("/canned-response-groups")
    public R<List<CannedResponseGroupDO>> listGroups() {
        return R.ok(service.listGroups());
    }

    @PostMapping("/canned-response-groups")
    public R<CannedResponseGroupDO> createGroup(@RequestBody @Valid GroupRequest req) {
        Long createdBy = StpUtil.getLoginIdAsLong();
        return R.ok(service.createGroup(req.getName(), req.getParentId(),
                req.getSortOrder() != null ? req.getSortOrder() : 0, createdBy));
    }

    @PutMapping("/canned-response-groups/{id}")
    public R<Void> updateGroup(@PathVariable Long id,
                               @RequestBody @Valid GroupRequest req) {
        service.updateGroup(id, req.getName(), req.getParentId(),
                req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok();
    }

    @DeleteMapping("/canned-response-groups/{id}")
    public R<Void> deleteGroup(@PathVariable Long id) {
        service.deleteGroup(id);
        return R.ok();
    }

    // ── 公共快捷回复接口 ──────────────────────────────────

    @GetMapping("/canned-responses")
    public R<List<CannedResponseDO>> listPublic(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.listPublic(groupId, page, Math.min(size, 100)));
    }

    @PostMapping("/canned-responses")
    public R<CannedResponseDO> createPublic(@RequestBody @Valid CannedRequest req) {
        Long createdBy = StpUtil.getLoginIdAsLong();
        return R.ok(service.createPublic(req.getTitle(), req.getContent(),
                req.getGroupId(), req.getSortOrder() != null ? req.getSortOrder() : 0,
                createdBy));
    }

    @PutMapping("/canned-responses/{id}")
    public R<Void> updatePublic(@PathVariable Long id,
                                @RequestBody @Valid CannedRequest req) {
        service.updatePublic(id, req.getTitle(), req.getContent(),
                req.getGroupId(), req.getSortOrder() != null ? req.getSortOrder() : 0);
        return R.ok();
    }

    @DeleteMapping("/canned-responses/{id}")
    public R<Void> deletePublic(@PathVariable Long id) {
        service.deletePublic(id);
        return R.ok();
    }

    // ── 请求体 DTO ────────────────────────────────────────

    @Data
    public static class GroupRequest {
        @NotBlank @Size(max = 64) private String name;
        private Long parentId;
        private Integer sortOrder;
    }

    @Data
    public static class CannedRequest {
        @NotBlank @Size(max = 128) private String title;
        @NotBlank private String content;
        private Long groupId;
        private Integer sortOrder;
    }
}
