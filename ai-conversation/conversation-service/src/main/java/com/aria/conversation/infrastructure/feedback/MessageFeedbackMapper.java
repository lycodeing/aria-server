package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 访客消息反馈 Mapper。
 *
 * <p>所有查询/更新通过 MyBatis-Plus LambdaWrapper，避免 @Select 魔法字符串 SQL。
 */
@Mapper
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedbackDO> {

    /**
     * 按 (sessionId, seq) 查找反馈记录。
     *
     * <p>DB 侧 {@code uq_msg_feedback (session_id, seq)} 唯一索引兜底并发插入，
     * 应用层 Service 先查后写，正常路径不会踩到唯一冲突。
     */
    default Optional<MessageFeedbackDO> findBySessionAndSeq(String sessionId, long seq) {
        return Optional.ofNullable(selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getSessionId, sessionId)
                        .eq(MessageFeedbackDO::getSeq, seq)));
    }

    /**
     * 按 (sessionId, seq) 删除反馈记录。
     *
     * <p>用于「取消反馈」场景（feedback=null）。幂等：删除不存在的行返回 0，Service 不感知。
     */
    default int deleteBySessionAndSeq(String sessionId, long seq) {
        return delete(Wrappers.lambdaQuery(MessageFeedbackDO.class)
                .eq(MessageFeedbackDO::getSessionId, sessionId)
                .eq(MessageFeedbackDO::getSeq, seq));
    }
}
