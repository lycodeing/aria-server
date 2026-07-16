package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CsatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 访客侧 CSAT 接口（不需要坐席鉴权，仅需访客 Token）。
 *
 * <p>POST /api/v1/chat/csat/{csatId}/rate  提交评分（1–5星 + 可选文字）
 * <p>POST /api/v1/chat/csat/{csatId}/skip  跳过评价
 */
@RestController
@RequestMapping("/api/v1/chat/csat")
@RequiredArgsConstructor
public class CsatController {

    private final CsatService csatService;

    @PostMapping("/{csatId}/rate")
    public R<Void> rate(@PathVariable Long csatId,
                        @RequestBody @Valid RateRequest req) {
        csatService.rate(csatId, req.getScore(), req.getComment());
        return R.ok();
    }

    @PostMapping("/{csatId}/skip")
    public R<Void> skip(@PathVariable Long csatId) {
        csatService.skip(csatId);
        return R.ok();
    }

    @Data
    public static class RateRequest {
        @NotNull @Min(1) @Max(5)
        private Short score;
        private String comment;
    }
}
