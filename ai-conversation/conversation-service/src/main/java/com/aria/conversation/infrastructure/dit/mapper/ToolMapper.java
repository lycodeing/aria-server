package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ToolMapper extends BaseMapper<ToolDO> {

    @Select("SELECT * FROM cs_conversation.cs_tool WHERE code = #{code} AND enabled = TRUE LIMIT 1")
    Optional<ToolDO> findByCode(String code);
}
