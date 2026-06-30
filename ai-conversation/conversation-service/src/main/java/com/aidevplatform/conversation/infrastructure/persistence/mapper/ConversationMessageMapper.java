package com.aidevplatform.conversation.infrastructure.persistence.mapper;

import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
}
