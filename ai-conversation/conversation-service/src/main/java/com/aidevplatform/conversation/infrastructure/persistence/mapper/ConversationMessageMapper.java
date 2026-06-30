package com.aidevplatform.conversation.infrastructure.persistence.mapper;

import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper。
 *
 * <p>继承 MyBatis-Plus BaseMapper，使用 saveBatch（通过 IService）进行批量插入。
 * 消息为只读追加型数据，无需自定义更新方法。
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
}
