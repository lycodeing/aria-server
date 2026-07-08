package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话 Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，所有单表查询/更新通过
 * {@code Wrappers.lambdaUpdate()} / {@code Wrappers.lambdaQuery()} 完成，
 * 禁止在此写 @Update/@Select 魔法字符串 SQL。
 *
 * <p>编码规范（项目级）：
 * <pre>
 * // ✅ 正确：LambdaWrapper，类型安全，无魔法字符串
 * update(Wrappers.lambdaUpdate(XxxEntity.class)
 *     .set(XxxEntity::getStatus, SessionStatus.ACTIVE.getValue())
 *     .eq(XxxEntity::getSessionId, sessionId)
 *     .eq(XxxEntity::getStatus, SessionStatus.WAITING.getValue()));
 *
 * // ❌ 禁止：@Update("UPDATE ... SET status='ACTIVE' WHERE ...")
 * </pre>
 *
 * <p>复杂多表 JOIN / 原生 SQL 才使用 XML Mapper，单表 CRUD 一律用 Wrapper API。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * 将会话状态从 WAITING 更新为 ACTIVE，并写入接入座席 ID。
     * 仅当状态为 WAITING 时才更新，防止重复接入（幂等）。
     *
     * @param sessionId  会话唯一标识
     * @param agentId    接入座席 ID
     * @param acceptedAt 接入时间
     * @return 受影响行数（0 表示不存在或已非 WAITING）
     */
    default int activateBySessionId(@Param("sessionId") String sessionId,
                                    @Param("agentId") String agentId,
                                    @Param("acceptedAt") OffsetDateTime acceptedAt) {
        return update(Wrappers.lambdaUpdate(ConversationEntity.class)
                .set(ConversationEntity::getStatus,    SessionStatus.ACTIVE.getValue())
                .set(ConversationEntity::getAgentId,   agentId)
                .set(ConversationEntity::getUpdatedAt, acceptedAt)
                .eq(ConversationEntity::getSessionId,  sessionId)
                .eq(ConversationEntity::getStatus,     SessionStatus.WAITING.getValue())
        );
    }

    /**
     * 将 ACTIVE 会话的 agent_id 更新为目标座席（转交）。
     * 仅当会话仍为 ACTIVE 时才更新，避免覆盖已关闭会话。
     *
     * @param sessionId     会话唯一标识
     * @param targetAgentId 目标座席 ID
     * @return 受影响行数（0 表示不存在或已非 ACTIVE）
     */
    default int transferBySessionId(@Param("sessionId") String sessionId,
                                    @Param("targetAgentId") String targetAgentId) {
        return update(Wrappers.lambdaUpdate(ConversationEntity.class)
                .set(ConversationEntity::getAgentId,  targetAgentId)
                .eq(ConversationEntity::getSessionId, sessionId)
                .eq(ConversationEntity::getStatus,    SessionStatus.ACTIVE.getValue())
        );
    }

    /**
     * 查询所有 ACTIVE 状态的会话，供座席工作台刷新恢复。
     * 使用 LambdaQueryWrapper，避免 @Select 中的魔法字符串。
     *
     * @return ACTIVE 会话列表，按 started_at 升序
     */
    default List<ConversationEntity> selectActiveConversations() {
        return selectList(Wrappers.lambdaQuery(ConversationEntity.class)
                .eq(ConversationEntity::getStatus, SessionStatus.ACTIVE.getValue())
                .orderByAsc(ConversationEntity::getStartedAt)
        );
    }

    /**
     * 检查会话在 DB 中是否为 ACTIVE（Redis 丢失时的兜底查询）。
     * 使用 LambdaQueryWrapper count，避免 @Select 魔法字符串。
     *
     * @param sessionId 会话唯一标识
     * @return true 表示 DB 中为 ACTIVE
     */
    default boolean isActiveInDb(@Param("sessionId") String sessionId) {
        return selectCount(Wrappers.lambdaQuery(ConversationEntity.class)
                .eq(ConversationEntity::getSessionId, sessionId)
                .eq(ConversationEntity::getStatus,    SessionStatus.ACTIVE.getValue())
        ) > 0;
    }

    /**
     * 从 DB 查询会话当前状态（Redis 丢失时的兜底查询）。
     *
     * @param sessionId 会话唯一标识
     * @return 会话状态，会话不存在时返回 null
     */
    default SessionStatus getStatusFromDb(@Param("sessionId") String sessionId) {
        ConversationEntity entity = selectOne(Wrappers.lambdaQuery(ConversationEntity.class)
                .select(ConversationEntity::getStatus)
                .eq(ConversationEntity::getSessionId, sessionId)
        );
        return entity != null ? entity.getStatus() : null;
    }

    /**
     * 查询最近已关闭或 AI 对话的会话（status IN ('CLOSED','AI_CHAT')），
     * 供座席工作台「已结束」Tab 展示。按 updated_at 倒序，限制返回条数。
     *
     * @param limit 返回条数上限
     * @return CLOSED / AI_CHAT 会话列表，按 updated_at 倒序
     */
    default List<ConversationEntity> selectClosedConversations(@Param("limit") int limit) {
        return selectList(Wrappers.lambdaQuery(ConversationEntity.class)
                .in(ConversationEntity::getStatus,
                        SessionStatus.CLOSED.getValue(),
                        SessionStatus.AI_CHAT.getValue())
                .orderByDesc(ConversationEntity::getUpdatedAt)
                .last("LIMIT " + limit)
        );
    }

    /**
     * 查询指定访客的历史会话列表，排除当前进行中的会话。
     *
     * <p>按 started_at 倒序返回最近 limit 条，用于座席工作台「历史工单」抽屉展示。
     *
     * @param visitorName     访客名称
     * @param excludeSessionId 排除的会话 ID（通常为当前会话）
     * @param limit           返回条数上限
     * @return 历史会话列表，按 started_at 倒序
     */
    default List<ConversationEntity> selectByVisitorName(
            @Param("visitorName") String visitorName,
            @Param("excludeSessionId") String excludeSessionId,
            @Param("limit") int limit) {
        return selectList(Wrappers.lambdaQuery(ConversationEntity.class)
                .eq(ConversationEntity::getVisitorName, visitorName)
                .ne(excludeSessionId != null && !excludeSessionId.isBlank(),
                        ConversationEntity::getSessionId, excludeSessionId)
                .orderByDesc(ConversationEntity::getStartedAt)
                .last("LIMIT " + limit)
        );
    }

    /**
     * 将会话状态更新为 CLOSED，记录结束时间。
     * 仅当会话非 CLOSED 状态时才更新（幂等），防止重复关闭。
     *
     * @param sessionId 会话唯一标识
     * @param endedAt   结束时间
     * @return 受影响行数（0 表示不存在或已关闭）
     */
    default int closeBySessionId(@Param("sessionId") String sessionId,
                                 @Param("endedAt") OffsetDateTime endedAt) {
        return update(Wrappers.lambdaUpdate(ConversationEntity.class)
                .set(ConversationEntity::getStatus,    SessionStatus.CLOSED.getValue())
                .set(ConversationEntity::getEndedAt,   endedAt)
                .set(ConversationEntity::getUpdatedAt, endedAt)
                .eq(ConversationEntity::getSessionId,  sessionId)
                .ne(ConversationEntity::getStatus,     SessionStatus.CLOSED.getValue())
        );
    }
}
