package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.TagAppService;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话级标签操作。
 *
 * <pre>
 * GET    /api/v1/sessions/{sessionId}/tags          → 查询会话标签列表
 * POST   /api/v1/sessions/{sessionId}/tags          → 为会话添加标签（tagId / tagName 二选一）
 * DELETE /api/v1/sessions/{sessionId}/tags/{tagId}  → 移除会话标签
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/tags")
@RequiredArgsConstructor
public class SessionTagController {

    private final TagAppService tagAppService;

    @GetMapping
    @SaCheckPermission("session:tag:write")
    public R<List<TagVO>> listSessionTags(@PathVariable String sessionId) {
        return R.ok(tagAppService.listSessionTags(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:tag:write")
    public R<TagVO> addSessionTag(@PathVariable String sessionId,
                                   @RequestBody @Validated VisitorTagController.TagReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(tagAppService.addSessionTag(sessionId, operatorId, req.getTagId(), req.getTagName()));
    }

    @DeleteMapping("/{tagId}")
    @SaCheckPermission("session:tag:write")
    public R<Void> removeSessionTag(@PathVariable String sessionId,
                                     @PathVariable Long tagId) {
        String operatorId = StpUtil.getLoginIdAsString();
        tagAppService.removeSessionTag(sessionId, operatorId, tagId);
        return R.ok();
    }
}
