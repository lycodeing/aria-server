package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对话消息 Mapper。
 *
 * <p>继承 MyBatis-Plus BaseMapper，使用 saveBatch（通过 IService）进行批量插入。
 * 消息为只读追加型数据，无需自定义更新方法。
 *
 * <p>所有自定义查询通过 {@code Wrappers.lambdaQuery()} 完成，禁止写 @Select 魔法字符串 SQL。
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

    /**
     * 增量查询：返回 sessionId 在 sinceSeq 之后的所有消息（seq 严格大于）。
     *
     * <p>支持客户端断线重连后通过 {@code GET /chat/history?sinceSeq=N} 拉增量，
     * 走索引 {@code idx_cs_msg_session_seq (session_id, seq)}，性能稳定。
     *
     * <p>seq 为 NULL 的历史遗留数据不会返回（视为不参与增量同步）。
     *
     * @param sessionId 会话唯一标识
     * @param sinceSeq  起始 seq（不含），客户端传入 lastSeq
     * @return 按 seq 升序排列的消息列表，无新消息时返回空列表
     */
    default List<ConversationMessageEntity> findBySessionSinceSeq(
            @Param("sessionId") String sessionId,
            @Param("sinceSeq") long sinceSeq) {
        return selectList(Wrappers.lambdaQuery(ConversationMessageEntity.class)
                .eq(ConversationMessageEntity::getSessionId, sessionId)
                .isNotNull(ConversationMessageEntity::getSeq)
                .gt(ConversationMessageEntity::getSeq, sinceSeq)
                .orderByAsc(ConversationMessageEntity::getSeq));
    }

    /**
     * 查询最近有消息的 session_id 列表及其最后活跃时间，用于展示 AI 纯对话历史。
     *
     * <p>按每个 session 的 MAX(created_at) 倒序，取最近 limit 条。
     * 结果 Map 包含：session_id（String）、last_active_at（OffsetDateTime）。
     *
     * @param limit 返回条数上限
     * @return session_id 和最后活跃时间的 Map 列表
     */
    default List<Map<String, Object>> selectRecentSessionIds(@Param("limit") int limit) {
        return selectMaps(new QueryWrapper<ConversationMessageEntity>()
                .select("session_id, MAX(created_at) AS last_active_at")
                .groupBy("session_id")
                .orderByDesc("MAX(created_at)")
                .last("LIMIT " + limit));
    }

    /**
     * 批量统计多个会话的消息总数，避免 N+1 查询。
     *
     * <p>结果 Map：key=sessionId，value=消息数量；没有消息的 sessionId 不在 Map 中（调用方默认取 0）。
     *
     * @param sessionIds 会话 ID 列表，不得为空
     * @return sessionId → 消息数量 的 Map
     */
    default Map<String, Long> countBySessionIds(@Param("sessionIds") List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        // 利用 QueryWrapper 的 groupBy + in 实现一条 SQL 批量聚合
        List<Map<String, Object>> rows = selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConversationMessageEntity>()
                        .select("session_id, COUNT(*) AS cnt")
                        .in("session_id", sessionIds)
                        .groupBy("session_id"));
        Map<String, Long> result = new java.util.HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object sessionId = row.get("session_id");
            Object cnt       = row.get("cnt");
            if (sessionId != null && cnt != null) {
                result.put(sessionId.toString(), ((Number) cnt).longValue());
            }
        }
        return result;
    }

    /**
     * 查询指定 session 当前最大 seq（用于 Redis 计数器失效后从 DB 恢复初始值）。
     *
     * @param sessionId 会话唯一标识
     * @return 最大 seq，无数据时返回 0
     */
    default long selectMaxSeq(@Param("sessionId") String sessionId) {
        ConversationMessageEntity one = selectOne(
                Wrappers.lambdaQuery(ConversationMessageEntity.class)
                        .eq(ConversationMessageEntity::getSessionId, sessionId)
                        .isNotNull(ConversationMessageEntity::getSeq)
                        .orderByDesc(ConversationMessageEntity::getSeq)
                        .last("LIMIT 1"));
        return one != null && one.getSeq() != null ? one.getSeq() : 0L;
    }

    /**
     * 查询指定 session 最近一条"可反馈回复"的 seq。
     *
     * <p>用于消息反馈接口：前端未指定 seq 时，回落到"最新一条回复"。
     * 回复角色包含 {@link MessageRole#ASSISTANT}（AI 机器人回复）与
     * {@link MessageRole#AGENT}（人工座席回复）。自消息角色模型重构后，人工座席
     * 回复以 {@code agent} 落库（不再用 {@code assistant}），纯人工会话不再有
     * {@code assistant} 行，故必须同时匹配两种回复角色——否则纯人工会话的点赞/点踩
     * 会因找不到可反馈消息而 400。
     *
     * <p>只考虑 seq 非空的行；DIT 迁移前的历史消息 seq=NULL 不参与。
     *
     * @param sessionId 会话唯一标识
     * @return 最近一条回复消息的 seq，无回复时返回空
     */
    default Optional<Long> selectLastReplySeq(@Param("sessionId") String sessionId) {
        ConversationMessageEntity one = selectOne(
                Wrappers.lambdaQuery(ConversationMessageEntity.class)
                        .eq(ConversationMessageEntity::getSessionId, sessionId)
                        .in(ConversationMessageEntity::getRole, MessageRole.ASSISTANT, MessageRole.AGENT)
                        .isNotNull(ConversationMessageEntity::getSeq)
                        .orderByDesc(ConversationMessageEntity::getSeq)
                        .last("LIMIT 1"));
        return one != null && one.getSeq() != null ? Optional.of(one.getSeq()) : Optional.empty();
    }
}
