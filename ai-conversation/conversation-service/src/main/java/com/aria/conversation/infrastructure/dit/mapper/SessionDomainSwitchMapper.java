package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SessionDomainSwitchMapper extends BaseMapper<SessionDomainSwitchDO> {
    @Select("SELECT * FROM cs_session_domain_switch WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<SessionDomainSwitchDO> findBySessionId(@Param("sessionId") String sessionId);
}
