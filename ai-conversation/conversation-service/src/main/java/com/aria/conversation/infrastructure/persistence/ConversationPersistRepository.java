package com.aria.conversation.infrastructure.persistence;

import org.springframework.dao.DuplicateKeyException;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话持久化 Repository。
 *
 * <p>封装所有 DB 写入操作，对上层 {@link ConversationPersistenceService} 屏蔽 MyBatis-Plus 细节。
 * 分别持有 {@link ConversationMapper} 和 {@link ConversationMessageMapper}，
 * 避免 ServiceImpl 泛型绑定单一实体类型带来的类型冲突。
 *
 * <p>设计原则：
 * <ul>
 *   <li>所有方法幂等：同一 sessionId 重复调用 startConversation 不报错</li>
 *   <li>消息写入仅追加，不更新</li>
 *   <li>异常不向上抛，由调用方记录日志后决定是否转 DLQ</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConversationPersistRepository {

    private final ConversationMapper        conversationMapper;
    private final ConversationMessageMapper messageMapper;

    /**
     * 创建会话记录（SESSION_START 事件触发）。
     * 若同一 sessionId 已存在，静默忽略（幂等，防止重复消费）。
     *
     * @param sessionId      会话唯一标识
     * @param visitorName    访客名称
     * @param transferReason 转接原因
     * @param tag            问题分类标签
     * @param startedAt      会话开始时间
     */
    public void startConversation(String sessionId, String visitorName,
                                   String transferReason, String tag,
                                   OffsetDateTime startedAt) {
        ConversationEntity entity = new ConversationEntity();
        entity.setSessionId(sessionId);
        entity.setVisitorName(visitorName != null ? visitorName : "访客");
        entity.setTransferReason(transferReason);
        entity.setTag(tag != null && !tag.isBlank() ? tag : "咨询");
        entity.setStatus(SessionStatus.WAITING);
        entity.setStartedAt(startedAt);
        entity.setCreatedAt(startedAt);
        entity.setUpdatedAt(startedAt);

        try {
            conversationMapper.insert(entity);
            log.debug("[Persist] 会话创建 sessionId={}", sessionId);
        } catch (DuplicateKeyException e) {
            // 可能是 AI_CHAT 记录，用户后来转人工时需要升级为 WAITING
            int upgraded = conversationMapper.update(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .lambdaUpdate(ConversationEntity.class)
                            .set(ConversationEntity::getStatus,     SessionStatus.WAITING.getValue())
                            .set(ConversationEntity::getVisitorName, entity.getVisitorName())
                            .set(ConversationEntity::getTransferReason, entity.getTransferReason())
                            .set(ConversationEntity::getTag,         entity.getTag())
                            .set(ConversationEntity::getStartedAt,   entity.getStartedAt())
                            .eq(ConversationEntity::getSessionId,    sessionId)
                            .eq(ConversationEntity::getStatus,
                                    SessionStatus.AI_CHAT.getValue())
            );
            if (upgraded > 0) {
                log.debug("[Persist] AI_CHAT 会话升级为 WAITING sessionId={}", sessionId);
            } else {
                log.debug("[Persist] 会话已存在（非AI_CHAT），忽略重复创建 sessionId={}", sessionId);
            }
        }
    }

    /**
     * 激活会话（SESSION_ACCEPT 事件触发，座席接入时调用）。
     *
     * <p>WAITING → ACTIVE 状态转换由 DB 侧 CAS 保证：仅当 status=WAITING 时才更新，
     * 幂等设计：同一 sessionId 重复调用时 affected=0，静默忽略，防止 MQ 重试覆盖已 ACTIVE 状态。
     *
     * @param sessionId  会话唯一标识
     * @param agentId    接入座席 ID
     * @param acceptedAt 接入时间
     */
    public void activateConversation(String sessionId, String agentId, OffsetDateTime acceptedAt) {
        int affected = conversationMapper.activateBySessionId(sessionId, agentId, acceptedAt);
        if (affected == 0) {
            log.debug("[Persist] 会话不存在或已非 WAITING，忽略激活 sessionId={}", sessionId);
        } else {
            log.debug("[Persist] 会话激活 ACTIVE sessionId={} agentId={}", sessionId, agentId);
        }
    }

    /**
     * 转交会话：将 ACTIVE 会话的 agent_id 更新为目标座席。
     *
     * @param sessionId     会话唯一标识
     * @param targetAgentId 目标座席 ID
     */
    public void transferConversation(String sessionId, String targetAgentId) {
        int affected = conversationMapper.transferBySessionId(sessionId, targetAgentId);
        if (affected == 0) {
            log.debug("[Persist] 会话不存在或非 ACTIVE，忽略转交 sessionId={}", sessionId);
        } else {
            log.debug("[Persist] 会话转交 sessionId={} → agentId={}", sessionId, targetAgentId);
        }
    }

    /**
     * 幂等初始化 AI_CHAT 会话记录（首条 AI 消息时调用）。
     * 若记录已存在（任意状态），静默跳过，不覆盖。
     *
     * @param sessionId 会话唯一标识
     * @param startedAt 首条消息时间
     */
    public void initAiChatSession(String sessionId, OffsetDateTime startedAt) {
        ConversationEntity entity = new ConversationEntity();
        entity.setSessionId(sessionId);
        entity.setVisitorName("访客");
        entity.setTransferReason("");
        entity.setTag("AI 对话");
        entity.setStatus(SessionStatus.AI_CHAT);
        entity.setStartedAt(startedAt);
        entity.setCreatedAt(startedAt);
        entity.setUpdatedAt(startedAt);
        try {
            conversationMapper.insert(entity);
            log.debug("[Persist] AI_CHAT 会话初始化 sessionId={}", sessionId);
        } catch (DuplicateKeyException e) {
            // 已存在（可能已升级为 WAITING/ACTIVE），静默跳过
            log.debug("[Persist] AI_CHAT 会话已存在，跳过 sessionId={}", sessionId);
        }
    }

    /**
     * 查询所有 ACTIVE 状态的会话，供座席工作台刷新后恢复。
     * 从 DB 读取，不依赖 Redis，重启后仍可正确恢复。
     *
     * @return ACTIVE 会话列表，按 started_at 升序
     */
    public List<ConversationEntity> getActiveConversations() {
        return conversationMapper.selectActiveConversations();
    }

    /**
     * 查询当前活跃的 AI 对话会话（status=AI_CHAT 且 ended_at 为 null），
     * 供 {@link #getAllConversations(int)} 聚合使用。
     *
     * @return AI_CHAT 进行中会话列表，按 started_at 升序
     */
    public List<ConversationEntity> getAiChatConversations() {
        return conversationMapper.selectAiChatConversations();
    }

    /**
     * 查询最近已关闭的会话列表（按 ended_at 倒序，最多返回 limit 条）。
     * 供座席工作台「已结束」Tab 展示历史会话（仅转人工后有 cs_conversation 记录的会话）。
     *
     * @param limit 返回条数上限
     * @return CLOSED 会话列表
     */
    public List<ConversationEntity> getClosedConversations(int limit) {
        return conversationMapper.selectClosedConversations(limit);
    }

    /**
     * 检查会话在 DB 中是否为 ACTIVE（用于 Redis 丢失时的兜底）。
     *
     * @param sessionId 会话唯一标识
     * @return true 表示 DB 中 ACTIVE
     */
    public boolean isActiveInDb(String sessionId) {
        return conversationMapper.isActiveInDb(sessionId);
    }

    /**
     * 从 DB 查询会话当前状态（Redis 丢失时的兜底查询）。
     *
     * @param sessionId 会话唯一标识
     * @return 会话状态，会话不存在时返回 null
     */
    public SessionStatus getStatusFromDb(String sessionId) {
        return conversationMapper.getStatusFromDb(sessionId);
    }

    /**
     * 查询同一访客的历史会话列表，排除当前会话。
     *
     * @param visitorName      访客名称
     * @param excludeSessionId 要排除的会话 ID（当前会话）
     * @param limit            返回条数上限
     * @return 历史会话列表，按 started_at 倒序
     */
    public List<ConversationEntity> getVisitorHistory(String visitorName,
                                                      String excludeSessionId,
                                                      int limit) {
        return conversationMapper.selectByVisitorName(visitorName, excludeSessionId, limit);
    }

    /**
     * 批量统计多个会话的消息总数，避免 N+1 查询。
     *
     * @param sessionIds 会话 ID 列表
     * @return sessionId → 消息数量 Map，无消息的 sessionId 不在 Map 中（调用方默认取 0）
     */
    public Map<String, Long> batchGetMessageCount(List<String> sessionIds) {
        return messageMapper.countBySessionIds(sessionIds);
    }

    /**
     * 关闭会话记录（SESSION_END 事件触发）。
     * 若会话不存在或已关闭，静默忽略。
     *
     * @param sessionId 会话唯一标识
     * @param endedAt   会话结束时间
     * @param closedBy  关闭发起方（agent / visitor / system）
     */
    public void closeConversation(String sessionId, OffsetDateTime endedAt, String closedBy) {
        int affected = conversationMapper.closeBySessionId(sessionId, endedAt, closedBy);
        if (affected == 0) {
            log.debug("[Persist] 会话不存在或已关闭，忽略 sessionId={}", sessionId);
        } else {
            log.debug("[Persist] 会话关闭 sessionId={} closedBy={}", sessionId, closedBy);
        }
    }

    /**
     * 幂等写入座席首条回复时间（仅在 first_reply_at 为 NULL 时才写入）。
     * 由 ConversationMessageConsumer 消费到首条 role=agent 消息时调用。
     *
     * @param sessionId    会话唯一标识
     * @param firstReplyAt 座席首条回复时间
     */
    public void setFirstReplyAtIfAbsent(String sessionId, OffsetDateTime firstReplyAt) {
        int affected = conversationMapper.setFirstReplyAtIfAbsent(sessionId, firstReplyAt);
        if (affected > 0) {
            log.debug("[Persist] 首次回复时间写入 sessionId={}", sessionId);
        }
    }

    /**
     * 批量写入消息明细（MESSAGE 事件触发）。
     *
     * <p>幂等保障：{@link DuplicateKeyException} 视为已写入，静默跳过（MQ 至少一次投递语义）。
     * 其他异常视为写入失败，记录后收集，全部处理完毕再统一抛出，
     * 阻止调用方 ACK，保留在 PEL 等待重试。
     *
     * @param messages 消息实体列表，不得为 null
     * @throws RuntimeException 存在非幂等写入失败时抛出，阻止调用方 ACK
     */
    public void saveMessages(List<ConversationMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (ConversationMessageEntity msg : messages) {
            try {
                messageMapper.insert(msg);
            } catch (DuplicateKeyException e) {
                // (session_id, seq) 唯一索引冲突：消息已存在，MQ 重试时幂等跳过
                log.debug("[Persist] 消息已存在，幂等跳过 sessionId={} seq={}",
                        msg.getSessionId(), msg.getSeq());
            } catch (Exception e) {
                // 其他异常（DB 连接失败/超时等）收集后统一抛出，阻止调用方 ACK → 触发 Spring AMQP 重试 → DLQ
                log.error("[Persist] 消息写入失败 sessionId={} role={}",
                        msg.getSessionId(), msg.getRole(), e);
                failures.add(msg.getSessionId() + "/" + msg.getRole());
            }
        }
        if (!failures.isEmpty()) {
            throw new RuntimeException("[Persist] 部分消息写入失败，等待 PEL 重试: " + failures);
        }
        log.debug("[Persist] 批量写入消息 count={}", messages.size());
    }
}
