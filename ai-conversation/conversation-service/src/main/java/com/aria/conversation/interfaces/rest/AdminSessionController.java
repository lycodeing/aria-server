package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DitManageAppService;
import com.aria.conversation.application.service.payload.SessionDomainSwitchVO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口（管理员）。
 * GET /api/v1/admin/sessions/{sessionId}/domain-history → 查询会话域切换历史
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final DitManageAppService manageService;

    /**
     * 查询指定会话的域切换历史（按时间升序）。
     * 会话历史含用户消息原文，仅限有查询权限的管理员访问。
     *
     * @param sessionId 会话 ID
     * @return 域切换历史列表
     */
    @GetMapping("/{sessionId}/domain-history")
    @SaCheckPermission("system:session:query")
    public R<List<SessionDomainSwitchVO>> getDomainHistory(
            @PathVariable @NotBlank(message = "sessionId 不能为空") String sessionId) {
        return R.ok(manageService.getSessionDomainHistory(sessionId));
    }
}
