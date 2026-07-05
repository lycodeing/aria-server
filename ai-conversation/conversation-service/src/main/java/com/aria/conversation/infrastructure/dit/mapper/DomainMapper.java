package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DomainMapper extends BaseMapper<DomainDO> {

    @Select("SELECT * FROM cs_conversation.cs_domain WHERE code = #{code} AND enabled = TRUE LIMIT 1")
    Optional<DomainDO> findByCode(String code);

    @Select("SELECT * FROM cs_conversation.cs_domain WHERE enabled = TRUE ORDER BY id ASC")
    List<DomainDO> findAllEnabled();

    @Select("SELECT id, code, name, description FROM cs_conversation.cs_domain WHERE enabled = TRUE ORDER BY id ASC")
    List<DomainDO> findAllEnabledSummary();
}
