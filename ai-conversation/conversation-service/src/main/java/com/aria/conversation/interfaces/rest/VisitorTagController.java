package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.TagAppService;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 访客持久标签操作（通过 sessionId 上下文鉴权）。
 *
 * <pre>
 * GET    /api/v1/sessions/{sessionId}/visitor/tags        → 查询访客标签列表
 * POST   /api/v1/sessions/{sessionId}/visitor/tags        → 为访客添加标签（tagId / tagName 二选一）
 * DELETE /api/v1/sessions/{sessionId}/visitor/tags/{tagId} → 移除访客标签
 * </pre>
 *
 * 路径设计：保留 /sessions/{sessionId}/visitor/tags 而非 /visitors/{visitorId}/tags，
 * 原因：坐席操作入口是会话上下文，便于直接鉴权（只有当前会话坐席可操作）。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/visitor/tags")
@RequiredArgsConstructor
public class VisitorTagController {

    private final TagAppService tagAppService;

    @GetMapping
    @SaCheckPermission("session:tag:write")
    public R<List<TagVO>> listVisitorTags(@PathVariable String sessionId) {
        return R.ok(tagAppService.listVisitorTags(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:tag:write")
    public R<TagVO> addVisitorTag(@PathVariable String sessionId,
                                   @RequestBody @Validated TagReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(tagAppService.addVisitorTag(sessionId, operatorId, req.getTagId(), req.getTagName()));
    }

    @DeleteMapping("/{tagId}")
    @SaCheckPermission("session:tag:write")
    public R<Void> removeVisitorTag(@PathVariable String sessionId,
                                     @PathVariable Long tagId) {
        String operatorId = StpUtil.getLoginIdAsString();
        tagAppService.removeVisitorTag(sessionId, operatorId, tagId);
        return R.ok();
    }

    @Data
    public static class TagReq {
        /** 已有标签 ID，与 tagName 二选一 */
        private Long   tagId;
        /** 新建标签名，与 tagId 二选一 */
        private String tagName;
    }
}
