package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IntentMapper extends BaseMapper<IntentDO> {

    @Select("SELECT * FROM cs_conversation.cs_intent " +
            "WHERE domain_id = #{domainId} AND enabled = TRUE ORDER BY sort_order ASC")
    List<IntentDO> findByDomainId(Long domainId);
}
