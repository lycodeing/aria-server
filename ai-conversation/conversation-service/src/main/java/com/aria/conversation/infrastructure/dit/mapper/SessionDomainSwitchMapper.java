package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SessionDomainSwitchMapper extends BaseMapper<SessionDomainSwitchDO> {

    default List<SessionDomainSwitchDO> findBySessionId(String sessionId) {
        return selectList(Wrappers.<SessionDomainSwitchDO>lambdaQuery()
                .eq(SessionDomainSwitchDO::getSessionId, sessionId)
                .orderByAsc(SessionDomainSwitchDO::getCreatedAt));
    }
}
