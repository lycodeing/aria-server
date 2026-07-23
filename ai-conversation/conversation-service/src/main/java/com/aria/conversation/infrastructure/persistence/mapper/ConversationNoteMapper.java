package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.ConversationNoteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 会话备注 Mapper。
 *
 * <p>所有查询通过 LambdaWrapper 完成，无需 XML。
 */
@Mapper
public interface ConversationNoteMapper extends BaseMapper<ConversationNoteEntity> {

    /**
     * 查询指定会话的所有备注，按 create_time 升序（时间线展示）。
     *
     * @param sessionId 会话唯一标识
     * @return 备注列表，无备注时返回空列表
     */
    default List<ConversationNoteEntity> selectBySessionId(String sessionId) {
        return selectList(Wrappers.<ConversationNoteEntity>lambdaQuery()
                .eq(ConversationNoteEntity::getSessionId, sessionId)
                .orderByAsc(ConversationNoteEntity::getCreateTime));
    }
}
