package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.ToolCallLogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogDO> {
    // 仅使用 BaseMapper 的 insert，查询通过管理后台走独立接口
}
