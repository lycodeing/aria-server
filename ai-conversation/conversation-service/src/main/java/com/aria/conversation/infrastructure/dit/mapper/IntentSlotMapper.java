package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentSlotMapper extends BaseMapper<IntentSlotDO> {

    @Select("SELECT * FROM cs_conversation.cs_intent_slot " +
            "WHERE intent_id = #{intentId} ORDER BY sort_order ASC")
    List<IntentSlotDO> findByIntentId(Long intentId);
}
