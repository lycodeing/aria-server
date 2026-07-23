package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 标签 Mapper。
 *
 * <p>单表查询通过 LambdaWrapper 完成；usage_count 需要原子更新，
 * 直接使用 @Update SQL 保证数据库侧原子性，避免先读后写的竞态条件。
 */
@Mapper
public interface TagMapper extends BaseMapper<TagEntity> {

    /**
     * 按名称精确查询标签（名称全局唯一）。
     *
     * @param name 标签名称
     * @return 匹配的标签实体，不存在时返回 null
     */
    default TagEntity selectByName(String name) {
        return selectOne(Wrappers.<TagEntity>lambdaQuery()
                .eq(TagEntity::getName, name));
    }

    /**
     * 原子自增 usage_count（打标时调用）。
     *
     * @param id 标签 ID
     */
    @Update("UPDATE cs_conversation.cs_tag SET usage_count = usage_count + 1 WHERE id = #{id}")
    void atomicIncrUsageCount(@Param("id") Long id);

    /**
     * 原子自减 usage_count，最小值为 0（取消打标时调用）。
     *
     * @param id 标签 ID
     */
    @Update("UPDATE cs_conversation.cs_tag SET usage_count = GREATEST(usage_count - 1, 0) WHERE id = #{id}")
    void atomicDecrUsageCount(@Param("id") Long id);
}
