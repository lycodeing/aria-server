package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.service.SlaPolicyMatcher;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.mapper.VisitorTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SLA 违规检测器（薄编排层）。
 *
 * <p>职责：串联 Matcher → Evaluator → Recorder → Notifier，自身不包含任何业务判断逻辑。
 *
 * <p>流程：
 * <ol>
 *   <li>从 {@link VisitorTagMapper} 查询访客标签名列表</li>
 *   <li>通过 {@link SlaPolicyMatcher} 匹配命中策略（无则跳过）</li>
 *   <li>通过 {@link SlaBreachEvaluator} 计算违规候选</li>
 *   <li>通过 {@link SlaBreachRecorder} 幂等写入数据库</li>
 *   <li>通过 {@link SlaBreachNotifier} 批量发出告警/升级</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachDetector {

    private final SlaPolicyMatcher   slaPolicyMatcher;
    private final SlaBreachEvaluator evaluator;
    private final SlaBreachRecorder  recorder;
    private final SlaBreachNotifier  notifier;
    private final VisitorTagMapper   visitorTagMapper;

    /**
     * 检测单个会话的 SLA 违规并触发相应动作。
     *
     * @param session 待检测会话（status 为 WAITING 或 ACTIVE）
     * @param now     检测基准时间，由 {@link SlaBreachScanScheduler} 在每轮扫描入口统一捕获，
     *                保证同批次所有会话使用相同基准时间
     */
    public void check(ConversationEntity session, OffsetDateTime now) {
        // 获取访客标签名列表（用于策略匹配）；访客 ID 为空时跳过查询
        List<String> visitorTagNames = Collections.emptyList();
        if (session.getVisitorId() != null && !session.getVisitorId().isBlank()) {
            visitorTagNames = visitorTagMapper
                    .selectTagsByVisitorId(session.getVisitorId())
                    .stream()
                    .map(TagEntity::getName)
                    .toList();
        }

        // 策略匹配：无命中策略则该会话不受 SLA 监控
        SlaPolicyEntity policy = slaPolicyMatcher.findPolicy(session, visitorTagNames);
        if (policy == null) {
            return;
        }

        // 评估违规候选
        List<BreachCandidate> candidates = evaluator.evaluate(session, policy, now);

        // 幂等写入，过滤掉已存在的记录
        List<SlaBreachEntity> newBreaches = candidates.stream()
                .map(c -> recorder.record(c, policy.getId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        // 有新违规时才通知，避免空调用
        if (!newBreaches.isEmpty()) {
            notifier.notifyBatch(newBreaches, policy, session);
        }
    }
}
