package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IntentMapper extends BaseMapper<IntentDO> {

    default List<IntentDO> findByDomainId(Long domainId) {
        return selectList(Wrappers.<IntentDO>lambdaQuery()
                .eq(IntentDO::getDomainId, domainId)
                .eq(IntentDO::getEnabled, true)
                .orderByAsc(IntentDO::getSortOrder));
    }
}
