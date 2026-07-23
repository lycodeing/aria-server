package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.web.response.R;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.infrastructure.cache.SlaPolicyCache;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.SlaPolicyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * SLA 策略与违规记录管理接口。
 *
 * <pre>
 * GET    /api/v1/admin/sla/policies        → 获取所有策略（按 priority DESC, id ASC）
 * POST   /api/v1/admin/sla/policies        → 创建策略
 * PUT    /api/v1/admin/sla/policies/{id}   → 更新策略
 * DELETE /api/v1/admin/sla/policies/{id}   → 删除策略
 * GET    /api/v1/admin/sla/breaches        → 分页查询违规记录（可按 sessionId/breachType/日期过滤）
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/sla")
@RequiredArgsConstructor
public class SlaController {

    private static final int NOT_FOUND = 40400;

    private final SlaPolicyMapper slaPolicyMapper;
    private final SlaBreachMapper slaBreachMapper;
    private final SlaPolicyCache  slaPolicyCache;

    // ── 策略 CRUD ────────────────────────────────────────────────────────────

    /**
     * 获取所有 SLA 策略，按优先级降序、id 升序排列。
     */
    @GetMapping("/policies")
    @SaCheckPermission("system:sla:manage")
    public R<List<SlaPolicyEntity>> listPolicies() {
        return R.ok(slaPolicyMapper.selectList(
                Wrappers.<SlaPolicyEntity>lambdaQuery()
                        .orderByDesc(SlaPolicyEntity::getPriority)
                        .orderByAsc(SlaPolicyEntity::getId)));
    }

    /**
     * 创建 SLA 策略。
     */
    @PostMapping("/policies")
    @SaCheckPermission("system:sla:manage")
    public R<SlaPolicyEntity> createPolicy(@RequestBody @Valid PolicyReq req) {
        SlaPolicyEntity entity = buildEntity(null, req);
        slaPolicyMapper.insert(entity);
        slaPolicyCache.evict();
        return R.ok(entity);
    }

    /**
     * 更新 SLA 策略。策略不存在时返回 404。
     */
    @PutMapping("/policies/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> updatePolicy(@PathVariable Long id,
                                @RequestBody @Valid PolicyReq req) {
        if (slaPolicyMapper.selectById(id) == null) {
            throw new BusinessException(NOT_FOUND, "SLA 策略不存在: " + id);
        }
        SlaPolicyEntity entity = buildEntity(id, req);
        slaPolicyMapper.updateById(entity);
        slaPolicyCache.evict();
        return R.ok();
    }

    /**
     * 删除 SLA 策略。
     */
    @DeleteMapping("/policies/{id}")
    @SaCheckPermission("system:sla:manage")
    public R<Void> deletePolicy(@PathVariable Long id) {
        slaPolicyMapper.deleteById(id);
        slaPolicyCache.evict();
        return R.ok();
    }

    // ── 违规记录查询 ──────────────────────────────────────────────────────────

    /**
     * 分页查询 SLA 违规记录。
     *
     * @param sessionId  可选，按会话 ID 精确过滤
     * @param breachType 可选，按违规类型过滤（WAIT / FRT / HANDLE）
     * @param startDate  可选，违规时间起始日期（含）
     * @param endDate    可选，违规时间结束日期（含）
     * @param page       页码，默认 1
     * @param pageSize   每页条数，默认 20，最大 100
     */
    @GetMapping("/breaches")
    @SaCheckPermission("system:sla:view")
    public R<List<SlaBreachEntity>> listBreaches(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String breachType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        if (pageSize > 100) {
            pageSize = 100;
        }

        var wrapper = Wrappers.<SlaBreachEntity>lambdaQuery();
        if (sessionId  != null) wrapper.eq(SlaBreachEntity::getSessionId,  sessionId);
        if (breachType != null) wrapper.eq(SlaBreachEntity::getBreachType, breachType);
        if (startDate  != null) wrapper.ge(SlaBreachEntity::getBreachAt,
                startDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        if (endDate    != null) wrapper.lt(SlaBreachEntity::getBreachAt,
                endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        wrapper.orderByDesc(SlaBreachEntity::getBreachAt);

        Page<SlaBreachEntity> pageObj = new Page<>(page, pageSize);
        slaBreachMapper.selectPage(pageObj, wrapper);
        return R.ok(pageObj.getRecords());
    }

    // ── 请求 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class PolicyReq {
        @NotBlank
        private String name;
        @NotNull
        private Boolean isEnabled;
        @NotNull
        private Integer priority;
        private List<String> matchVisitorTags;
        private List<String> matchTransferTags;
        private String timeMode = "CALENDAR";
        @NotNull
        private Integer waitTimeTargetSec;
        @NotNull
        private Integer frtTargetSec;
        @NotNull
        private Integer handleTimeTargetSec;
        private Integer warningThresholdPct = 80;
        @NotNull @Valid
        private SlaBreachActions actions;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private SlaPolicyEntity buildEntity(Long id, PolicyReq req) {
        SlaPolicyEntity e = new SlaPolicyEntity();
        e.setId(id);
        e.setName(req.getName());
        e.setIsEnabled(req.getIsEnabled());
        e.setPriority(req.getPriority());
        e.setMatchVisitorTags(req.getMatchVisitorTags());
        e.setMatchTransferTags(req.getMatchTransferTags());
        e.setTimeMode(req.getTimeMode());
        e.setWaitTimeTargetSec(req.getWaitTimeTargetSec());
        e.setFrtTargetSec(req.getFrtTargetSec());
        e.setHandleTimeTargetSec(req.getHandleTimeTargetSec());
        e.setWarningThresholdPct(req.getWarningThresholdPct());
        e.setActions(req.getActions());
        return e;
    }
}
