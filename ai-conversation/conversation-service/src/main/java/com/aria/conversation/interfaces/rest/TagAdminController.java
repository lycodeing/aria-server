package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.TagAppService;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 标签字典管理 Controller（管理端）。
 *
 * <p>所有操作委托给 {@link TagAppService}，不直接访问 Mapper，
 * 符合 interfaces → application → infrastructure 的 DDD 分层规范。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
public class TagAdminController {

    private final TagAppService tagAppService;

    /** 标签字典列表，可按 source（PRESET/CUSTOM）过滤，按 name ASC 排序。 */
    @GetMapping
    @SaCheckPermission("system:tag:manage")
    public R<List<TagEntity>> list(
            @RequestParam(required = false) String source) {
        return R.ok(tagAppService.listTags(source));
    }

    /** 新建预定义标签；标签名重复返回 409。 */
    @PostMapping
    @SaCheckPermission("system:tag:manage")
    public R<TagEntity> create(@RequestBody @Validated CreateTagReq req) {
        return R.ok(tagAppService.createPresetTag(req.getName(), req.getColor()));
    }

    /** 修改标签名称、颜色或来源；标签不存在返回 404。 */
    @PutMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> update(@PathVariable Long id,
                           @RequestBody @Validated UpdateTagReq req) {
        tagAppService.updateTag(id, req.getName(), req.getColor(), req.getSource());
        return R.ok();
    }

    /**
     * 删除标签。
     * usage_count > 0 时前端已做二次确认，后端直接执行删除。
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> delete(@PathVariable Long id) {
        tagAppService.deleteTag(id);
        return R.ok();
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────────

    @Data
    public static class CreateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
    }

    @Data
    public static class UpdateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
        @NotBlank private String source;
    }
}
