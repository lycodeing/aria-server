package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.ConversationTagEntity;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话-标签关联 Mapper。
 *
 * <p>{@link #selectTagsBySessionId} 需要 JOIN cs_tag，使用 XML 实现。
 * 其余单表操作通过 LambdaWrapper 完成。
 */
@Mapper
public interface ConversationTagMapper extends BaseMapper<ConversationTagEntity> {

    /**
     * 查询会话所有标签（JOIN cs_tag），按打标时间倒序。
     * SQL 实现见 resources/mapper/ConversationTagMapper.xml。
     *
     * @param sessionId 会话唯一标识
     * @return 标签列表，无标签时返回空列表
     */
    List<TagEntity> selectTagsBySessionId(@Param("sessionId") String sessionId);

    /**
     * 检查会话是否已打某标签（幂等判断）。
     *
     * @param sessionId 会话唯一标识
     * @param tagId     标签 ID
     * @return true 表示已存在关联
     */
    default boolean existsTag(String sessionId, Long tagId) {
        return exists(Wrappers.<ConversationTagEntity>lambdaQuery()
                .eq(ConversationTagEntity::getSessionId, sessionId)
                .eq(ConversationTagEntity::getTagId, tagId));
    }

    /**
     * 删除会话与指定标签的关联（取消打标）。
     *
     * @param sessionId 会话唯一标识
     * @param tagId     标签 ID
     */
    default void deleteBySessionIdAndTagId(String sessionId, Long tagId) {
        delete(Wrappers.<ConversationTagEntity>lambdaQuery()
                .eq(ConversationTagEntity::getSessionId, sessionId)
                .eq(ConversationTagEntity::getTagId, tagId));
    }
}
