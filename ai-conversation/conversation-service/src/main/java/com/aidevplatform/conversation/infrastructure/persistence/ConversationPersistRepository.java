package com.aidevplatform.conversation.infrastructure.persistence;

import org.springframework.dao.DuplicateKeyException;
import com.aidevplatform.conversation.domain.SessionStatus;
import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.aidevplatform.conversation.infrastructure.persistence.mapper.ConversationMapper;
import com.aidevplatform.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
        // 【强制】使用枚举 name()，消除魔法字符串
        entity.setTag(tag != null && !tag.isBlank() ? tag : "咨询");
        entity.setStatus(SessionStatus.WAITING);
        entity.setStartedAt(startedAt);

        try {
            conversationMapper.insert(entity);
            log.debug("[Persist] 会话创建 sessionId={}", sessionId);
        } catch (DuplicateKeyException e) {
            // 幂等处理：同一 sessionId 重复消费（MQ 至少一次语义），静默忽略
            log.debug("[Persist] 会话已存在，忽略重复创建 sessionId={}", sessionId);
        }
        // 其他异常（DB 连接失败/超时等）向上传播，触发 Spring AMQP 重试 → DLQ
    }

    /**
     * 激活会话（SESSION_ACCEPT 事件触发，座席接入时调用）。
     * 将 DB 中的状态从 WAITING 更新为 ACTIVE，作为持久化的 source of truth。
     * 幂等：重复 accept 同一会话时，DB 行已是 ACTIVE，UPDATE 影响行数为 0，静默忽略。
     *
     * @param sessionId  会话唯一标识
     * @param acceptedAt 接入时间
     */
    public void activateConversation(String sessionId, OffsetDateTime acceptedAt) {
        int affected = conversationMapper.activateBySessionId(sessionId, acceptedAt);
        if (affected == 0) {
            log.debug("[Persist] 会话不存在或已非 WAITING，忽略激活 sessionId={}", sessionId);
        } else {
            log.debug("[Persist] 会话激活 ACTIVE sessionId={}", sessionId);
        }
    }

    /**
     * 查询所有进行中（ACTIVE）的会话，供座席工作台刷新后恢复。
     * 从 DB 读取，不依赖 Redis，重启后仍可正确恢复。
     *
     * @return ACTIVE 会话列表，按 started_at 升序
     */
    public List<ConversationEntity> getActiveConversations() {
        return conversationMapper.selectActiveConversations();
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
     * 关闭会话记录（SESSION_END 事件触发）。
     * 若会话不存在或已关闭，静默忽略。
     *
     * @param sessionId 会话唯一标识
     * @param endedAt   会话结束时间
     */
    public void closeConversation(String sessionId, OffsetDateTime endedAt) {
        int affected = conversationMapper.closeBySessionId(sessionId, endedAt);
        if (affected == 0) {
            log.debug("[Persist] 会话不存在或已关闭，忽略 sessionId={}", sessionId);
        } else {
            log.debug("[Persist] 会话关闭 sessionId={}", sessionId);
        }
    }

    /**
     * 批量写入消息明细（MESSAGE 事件触发）。
     * 使用循环 insert 保证每条消息独立，失败时抛出异常，
     * 由 ConversationPersistenceService 决定是否 ACK（不 ACK 则留在 PEL 重试）。
     *
     * @param messages 消息实体列表，不得为 null
     * @throws RuntimeException 任意一条消息写入失败时抛出，阻止调用方 ACK
     */
    public void saveMessages(List<ConversationMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (ConversationMessageEntity msg : messages) {
            try {
                messageMapper.insert(msg);
            } catch (Exception e) {
                log.error("[Persist] 消息写入失败 sessionId={} role={}",
                        msg.getSessionId(), msg.getRole(), e);
                failures.add(msg.getSessionId() + "/" + msg.getRole());
            }
        }
        if (!failures.isEmpty()) {
            // 有写入失败，向上抛出让 processSingle 不 ACK，保留在 PEL 等待重试
            throw new RuntimeException("[Persist] 部分消息写入失败，等待 PEL 重试: " + failures);
        }
        log.debug("[Persist] 批量写入消息 count={}", messages.size());
    }
}
