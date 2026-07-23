package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.NoteAppService;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话备注操作。
 *
 * <pre>
 * GET    /api/v1/sessions/{sessionId}/notes           → 查询备注列表
 * POST   /api/v1/sessions/{sessionId}/notes           → 新增备注
 * PUT    /api/v1/sessions/{sessionId}/notes/{noteId}  → 修改备注（仅作者可改）
 * DELETE /api/v1/sessions/{sessionId}/notes/{noteId}  → 删除备注（作者或管理员可删）
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/notes")
@RequiredArgsConstructor
public class SessionNoteController {

    private final NoteAppService noteAppService;

    @GetMapping
    @SaCheckPermission("session:note:write")
    public R<List<NoteVO>> listNotes(@PathVariable String sessionId) {
        return R.ok(noteAppService.listNotes(sessionId));
    }

    @PostMapping
    @SaCheckPermission("session:note:write")
    public R<NoteVO> addNote(@PathVariable String sessionId,
                              @RequestBody @Validated NoteReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(noteAppService.addNote(sessionId, operatorId, req.getContent()));
    }

    @PutMapping("/{noteId}")
    @SaCheckPermission("session:note:write")
    public R<NoteVO> updateNote(@PathVariable String sessionId,
                                 @PathVariable Long noteId,
                                 @RequestBody @Validated NoteReq req) {
        String operatorId = StpUtil.getLoginIdAsString();
        return R.ok(noteAppService.updateNote(noteId, operatorId, req.getContent()));
    }

    @DeleteMapping("/{noteId}")
    @SaCheckPermission("session:note:write")
    public R<Void> deleteNote(@PathVariable String sessionId,
                               @PathVariable Long noteId) {
        String operatorId = StpUtil.getLoginIdAsString();
        boolean isAdmin = StpUtil.hasRole("super_admin") || StpUtil.hasRole("kf_manager");
        noteAppService.deleteNote(noteId, operatorId, isAdmin);
        return R.ok();
    }

    @Data
    public static class NoteReq {
        @NotBlank(message = "备注内容不能为空")
        @Size(max = 2000, message = "备注内容不能超过 2000 字符")
        private String content;
    }
}
