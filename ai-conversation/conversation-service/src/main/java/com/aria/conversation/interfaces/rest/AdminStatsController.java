package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.web.response.R;
import com.aria.conversation.application.service.ObservabilityStatsAppService;
import com.aria.conversation.interfaces.dto.StatsPeriod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可观测性统计查询 Controller（管理端）。
 *
 * <p>为管理台提供三类落库指标的聚合查询接口，全部委托 {@link ObservabilityStatsAppService}：
 * <ul>
 *   <li>{@code GET /intent-classification} — DIT 三层命中率与延迟</li>
 *   <li>{@code GET /rag-quality} — RAG 检索质量与 miss 榜</li>
 *   <li>{@code GET /llm-cost} — LLM Token 成本</li>
 * </ul>
 *
 * <p>{@code period} 非法值由 {@link StatsPeriod#parse} 抛 {@link IllegalArgumentException}，
 * 统一在本类 {@link #handleBadPeriod} 转 400。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final ObservabilityStatsAppService statsAppService;

    /** DIT 三层命中率报表。 */
    @GetMapping("/intent-classification")
    @SaCheckPermission("system:session:query")
    public R<Map<String, Object>> intentClassification(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(required = false) String domainCode) {
        return R.ok(statsAppService.intentClassificationStats(StatsPeriod.parse(period), domainCode));
    }

    /** RAG 检索质量报表。 */
    @GetMapping("/rag-quality")
    @SaCheckPermission("system:session:query")
    public R<Map<String, Object>> ragQuality(
            @RequestParam(defaultValue = "7d") String period) {
        return R.ok(statsAppService.ragQualityStats(StatsPeriod.parse(period)));
    }

    /** LLM Token 成本报表。 */
    @GetMapping("/llm-cost")
    @SaCheckPermission("system:session:query")
    public R<Map<String, Object>> llmCost(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(required = false) String modelName) {
        return R.ok(statsAppService.llmCostStats(StatsPeriod.parse(period), modelName));
    }

    /** period 非法值统一转 400，避免落到全局 500 处理器。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleBadPeriod(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }
}
