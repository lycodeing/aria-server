package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.VisitorHistoryDTO;
import com.aria.conversation.infrastructure.cache.ConversationCacheKeys;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 访客历史会话查询服务。
 *
 * <p>封装"查同访客历史工单"的完整业务逻辑：
 * <ol>
 *   <li>从 DB 查询同访客的历史会话列表（排除当前会话）</li>
 *   <li>批量查询每个会话的消息计数（一条 SQL，避免 N+1）</li>
 *   <li>批量从 Redis 读取 AI 摘要缓存（一次 pipeline，避免 N 次 GET）</li>
 *   <li>组装为 {@link VisitorHistoryDTO} 返回给 Controller 层</li>
 * </ol>
 *
 * <p>Controller 负责将 DTO 映射为 {@code VisitorHistoryVO} 对外响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorHistoryService {

    private static final String AI_SUMMARY_KEY_PREFIX = ConversationCacheKeys.AI_SUMMARY_PREFIX;
    private static final int    VISITOR_HISTORY_LIMIT = 20;

    private final ConversationPersistRepository persistRepository;
    private final StringRedisTemplate           redisTemplate;

    /**
     * 查询指定访客的历史会话列表（不含当前会话）——按匿名 ID 聚合同一访客。
     *
     * <p>设计主路径：访客匿名 ID（X-Anonymous-Id）是跨会话的稳定身份键，
     * 即使访客修改展示名也能正确归并其全部历史工单。
     *
     * @param visitorId        访客唯一标识（X-Anonymous-Id），不能为空
     * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
     * @return 历史会话 DTO 列表，按 startedAt 倒序，最多 {@value #VISITOR_HISTORY_LIMIT} 条
     */
    public List<VisitorHistoryDTO> getVisitorHistory(String visitorId, String excludeSessionId) {
        List<ConversationEntity> entities =
                persistRepository.getVisitorHistoryByVisitorId(visitorId, excludeSessionId, VISITOR_HISTORY_LIMIT);
        return toDTOs(entities);
    }

    /**
     * 按访客展示名查询历史会话列表（不含当前会话）。
     *
     * <p>兼容路径：座席工作台在仅持有访客展示名（userName）而未携带匿名 ID 的场景下，
     * 仍可查询该访客的历史工单。展示名可能不唯一（多人同名 / 同人改名），
     * 故精度弱于 {@link #getVisitorHistory(String, String)}，仅作为兜底。
     *
     * @param visitorName      访客展示名（userName）
     * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
     * @return 历史会话 DTO 列表，按 startedAt 倒序，最多 {@value #VISITOR_HISTORY_LIMIT} 条
     */
    public List<VisitorHistoryDTO> getVisitorHistoryByName(String visitorName, String excludeSessionId) {
        List<ConversationEntity> entities =
                persistRepository.getVisitorHistory(visitorName, excludeSessionId, VISITOR_HISTORY_LIMIT);
        return toDTOs(entities);
    }

    /** 批量装配历史会话 DTO（消息计数 + AI 摘要），与身份解析方式无关。 */
    private List<VisitorHistoryDTO> toDTOs(List<ConversationEntity> entities) {
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> sessionIds = entities.stream()
                .map(ConversationEntity::getSessionId)
                .toList();

        // 批量查消息计数（1 条 SQL）
        Map<String, Long> msgCounts = persistRepository.batchGetMessageCount(sessionIds);

        // 批量读 Redis AI 摘要（1 次 multiGet）
        List<String> redisKeys = sessionIds.stream()
                .map(id -> AI_SUMMARY_KEY_PREFIX + id)
                .toList();
        List<String> summaries = redisTemplate.opsForValue().multiGet(redisKeys);

        // 组装 DTO：用 Map 建立 sessionId → 摘要的 O(1) 查找，替代流内 indexOf O(n²)
        Map<String, String> summaryBySessionId = new java.util.HashMap<>(sessionIds.size() * 2);
        for (int i = 0; i < sessionIds.size(); i++) {
            String sid = sessionIds.get(i);
            String summary = (summaries != null && i < summaries.size()) ? summaries.get(i) : null;
            summaryBySessionId.put(sid, summary);
        }

        return entities.stream().map(e -> {
            long msgCount = msgCounts.getOrDefault(e.getSessionId(), 0L);
            String summary = summaryBySessionId.get(e.getSessionId());
            return new VisitorHistoryDTO(
                    e.getSessionId(),
                    e.getTag(),
                    e.getStatus() != null ? e.getStatus().name() : null,
                    e.getStartedAt(),
                    e.getEndedAt(),
                    (int) Math.min(msgCount, Integer.MAX_VALUE),
                    summary,
                    e.getTransferReason());
        }).collect(Collectors.toList());
    }
}
