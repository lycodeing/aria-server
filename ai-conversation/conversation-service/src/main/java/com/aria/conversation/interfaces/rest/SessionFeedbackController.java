package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.SessionFeedbackAppService;
import com.aria.conversation.interfaces.dto.SessionFeedbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 坐席反馈 Controller。
 *
 * <p>坐席对 AI 回答提交纠错/点赞反馈。需登录态（Sa-Token），坐席 ID 从登录态取得，
 * 不信任前端传入。所有操作委托 {@link SessionFeedbackAppService}，符合 DDD 分层。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sessions/feedback")
@RequiredArgsConstructor
@Tag(name = "坐席反馈")
public class SessionFeedbackController {

    private final SessionFeedbackAppService feedbackAppService;

    @Operation(summary = "提交 AI 回答反馈（坐席纠错 / 点赞）")
    @PostMapping
    @SaCheckLogin
    public R<Void> submitFeedback(@RequestBody @Validated SessionFeedbackRequest request) {
        Long agentId = StpUtil.getLoginIdAsLong();
        feedbackAppService.submitFeedback(request, agentId);
        return R.ok();
    }
}
