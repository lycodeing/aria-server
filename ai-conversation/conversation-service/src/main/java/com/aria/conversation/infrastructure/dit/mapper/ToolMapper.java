package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface ToolMapper extends BaseMapper<ToolDO> {

    default Optional<ToolDO> findByCode(String code) {
        return Optional.ofNullable(selectOne(Wrappers.<ToolDO>lambdaQuery()
                .eq(ToolDO::getCode, code)
                .eq(ToolDO::getEnabled, true)
                .last("LIMIT 1")));
    }
}
