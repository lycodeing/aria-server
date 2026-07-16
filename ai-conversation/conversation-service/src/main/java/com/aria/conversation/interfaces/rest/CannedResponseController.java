// interfaces/rest/CannedResponseController.java
package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 快捷回复坐席端接口。
 * 搜索（/ 触发）、私人模板管理、使用次数上报。
 */
@RestController
@RequestMapping("/api/v1/canned-responses")
@RequiredArgsConstructor
public class CannedResponseController {

    private final CannedResponseAppService service;

    /**
     * 搜索快捷回复。
     * q 为空时按 use_count 倒序返回热门结果；非空时走全文检索。
     * 同时返回当前坐席的 PRIVATE 模板（通过 agentId 过滤）。
     */
    @GetMapping("/search")
    public R<List<SearchVO>> search(
            @RequestParam(required = false) String q,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(defaultValue = "10") int limit) {
        Long agentId = StpUtil.getLoginIdAsLong();
        List<CannedResponseDO> results = service.search(q, agentId, groupId,
                Math.min(limit, 30));
        List<SearchVO> vos = results.stream().map(SearchVO::from).toList();
        return R.ok(vos);
    }

    // ── 私人快捷回复 ──────────────────────────────────────

    @GetMapping("/mine")
    public R<List<CannedResponseDO>> listMine() {
        Long agentId = StpUtil.getLoginIdAsLong();
        return R.ok(service.listPrivate(agentId));
    }

    @PostMapping("/mine")
    public R<CannedResponseDO> createMine(@RequestBody @Valid MineRequest req) {
        Long agentId = StpUtil.getLoginIdAsLong();
        return R.ok(service.createPrivate(req.getTitle(), req.getContent(),
                req.getGroupId(), agentId));
    }

    @PutMapping("/mine/{id}")
    public R<Void> updateMine(@PathVariable Long id,
                               @RequestBody @Valid MineRequest req) {
        Long agentId = StpUtil.getLoginIdAsLong();
        service.updatePrivate(id, req.getTitle(), req.getContent(), agentId);
        return R.ok();
    }

    @DeleteMapping("/mine/{id}")
    public R<Void> deleteMine(@PathVariable Long id) {
        Long agentId = StpUtil.getLoginIdAsLong();
        service.deletePrivate(id, agentId);
        return R.ok();
    }

    /**
     * 坐席使用快捷回复时上报，use_count +1（异步，不影响插入速度）。
     */
    @PostMapping("/{id}/use")
    public R<Void> recordUse(@PathVariable Long id) {
        service.recordUse(id);
        return R.ok();
    }

    // ── VO & 请求 DTO ─────────────────────────────────────

    /** 搜索结果 VO，屏蔽无关字段，附加 groupName 供前端显示 */
    public record SearchVO(Long id, String title, String content,
                           String scope, Integer useCount) {
        static SearchVO from(CannedResponseDO cr) {
            return new SearchVO(cr.getId(), cr.getTitle(), cr.getContent(),
                    cr.getScope(), cr.getUseCount());
        }
    }

    @Data
    public static class MineRequest {
        @NotBlank @Size(max = 128) private String title;
        @NotBlank private String content;
        private Long groupId;
    }
}
