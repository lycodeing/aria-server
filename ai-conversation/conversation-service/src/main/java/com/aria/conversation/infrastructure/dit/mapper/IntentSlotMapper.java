package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IntentSlotMapper extends BaseMapper<IntentSlotDO> {

    default List<IntentSlotDO> findByIntentId(Long intentId) {
        return selectList(Wrappers.<IntentSlotDO>lambdaQuery()
                .eq(IntentSlotDO::getIntentId, intentId)
                .orderByAsc(IntentSlotDO::getSortOrder));
    }
}
