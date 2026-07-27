package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DomainMapper extends BaseMapper<DomainDO> {

    default Optional<DomainDO> findByCode(String code) {
        return Optional.ofNullable(selectOne(Wrappers.<DomainDO>lambdaQuery()
                .eq(DomainDO::getCode, code)
                .eq(DomainDO::getEnabled, true)
                .last("LIMIT 1")));
    }

    default List<DomainDO> findAllEnabled() {
        return selectList(Wrappers.<DomainDO>lambdaQuery()
                .eq(DomainDO::getEnabled, true)
                .orderByAsc(DomainDO::getId));
    }

    /** 仅返回 id/code/name/description 字段（用于下拉列表等轻量场景）。 */
    default List<DomainDO> findAllEnabledSummary() {
        return selectList(Wrappers.<DomainDO>lambdaQuery()
                .select(DomainDO::getId, DomainDO::getCode,
                        DomainDO::getName, DomainDO::getDescription)
                .eq(DomainDO::getEnabled, true)
                .orderByAsc(DomainDO::getId));
    }
}
