package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * SLA 策略 Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，单表查询通过 LambdaWrapper 完成，
 * 类型安全，无魔法字符串 SQL。
 */
@Mapper
public interface SlaPolicyMapper extends BaseMapper<SlaPolicyEntity> {

    /**
     * 查询所有已启用的 SLA 策略，按优先级倒序（priority DESC）、id 升序排列。
     * 调用方按序遍历，第一个命中的策略即为目标策略。
     *
     * @return 已启用策略列表，无数据时返回空列表
     */
    default List<SlaPolicyEntity> selectAllEnabled() {
        return selectList(Wrappers.<SlaPolicyEntity>lambdaQuery()
                .eq(SlaPolicyEntity::getIsEnabled, true)
                .orderByDesc(SlaPolicyEntity::getPriority)
                .orderByAsc(SlaPolicyEntity::getId));
    }
}
