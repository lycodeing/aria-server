package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.VisitorHistoryDTO;
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

    private static final String AI_SUMMARY_KEY_PREFIX = "ai_summary:";
    private static final int    VISITOR_HISTORY_LIMIT = 20;

    private final ConversationPersistRepository persistRepository;
    private final StringRedisTemplate           redisTemplate;

    /**
     * 查询指定访客的历史会话列表（不含当前会话）。
     *
     * @param visitorName      访客名称，不能为空
     * @param excludeSessionId 要排除的会话 ID（当前会话），可为 null
     * @return 历史会话 DTO 列表，按 startedAt 倒序，最多 {@value #VISITOR_HISTORY_LIMIT} 条
     */
    public List<VisitorHistoryDTO> getVisitorHistory(String visitorName, String excludeSessionId) {
        List<ConversationEntity> entities =
                persistRepository.getVisitorHistory(visitorName, excludeSessionId, VISITOR_HISTORY_LIMIT);
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

        // 组装 DTO
        return entities.stream().map(e -> {
            int idx       = sessionIds.indexOf(e.getSessionId());
            long msgCount = msgCounts.getOrDefault(e.getSessionId(), 0L);
            String summary = (summaries != null && idx >= 0 && idx < summaries.size())
                    ? summaries.get(idx)
                    : null;
            return new VisitorHistoryDTO(
                    e.getSessionId(),
                    e.getTag(),
                    e.getStatus() != null ? e.getStatus().name() : null,
                    e.getStartedAt(),
                    e.getEndedAt(),
                    (int) Math.min(msgCount, Integer.MAX_VALUE),
                    summary);
        }).collect(Collectors.toList());
    }
}
