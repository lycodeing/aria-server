package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentToolMapper extends BaseMapper<IntentToolDO> {

    @Select("SELECT it.* FROM cs_conversation.cs_intent_tool it " +
            "JOIN cs_conversation.cs_tool t ON it.tool_id = t.id " +
            "WHERE it.intent_id = #{intentId} AND t.enabled = TRUE " +
            "ORDER BY it.execution_order ASC")
    List<IntentToolDO> findByIntentId(Long intentId);
}
