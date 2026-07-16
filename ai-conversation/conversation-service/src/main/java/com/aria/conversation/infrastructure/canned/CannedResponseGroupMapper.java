package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface CannedResponseGroupMapper extends BaseMapper<CannedResponseGroupDO> {

    /** 查询所有未删除分组，按 sort_order 升序 */
    default List<CannedResponseGroupDO> selectAllActive() {
        return selectList(Wrappers.lambdaQuery(CannedResponseGroupDO.class)
                .eq(CannedResponseGroupDO::getDeleted, false)
                .orderByAsc(CannedResponseGroupDO::getSortOrder));
    }
}
