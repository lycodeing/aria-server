package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 坐席反馈 Mapper。
 */
@Mapper
public interface SessionFeedbackMapper extends BaseMapper<SessionFeedbackEntity> {
}
