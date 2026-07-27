package com.aria.conversation.infrastructure.dit.mapper;

import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 意图工具关联 Mapper。
 * JOIN 查询定义在 IntentToolMapper.xml 中。
 */
@Mapper
public interface IntentToolMapper extends BaseMapper<IntentToolDO> {

    /**
     * 查询指定意图下已启用的工具列表，按执行顺序排序。
     * SQL：cs_intent_tool JOIN cs_tool（过滤 enabled=TRUE）
     */
    List<IntentToolDO> findByIntentId(@Param("intentId") Long intentId);
}
