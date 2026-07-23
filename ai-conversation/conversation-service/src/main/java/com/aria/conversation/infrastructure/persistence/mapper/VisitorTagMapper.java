package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.entity.VisitorTagEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 访客-标签关联 Mapper。
 *
 * <p>{@link #selectTagsByVisitorId} 需要 JOIN cs_tag，使用 XML 实现。
 * 其余单表操作通过 LambdaWrapper 完成。
 */
@Mapper
public interface VisitorTagMapper extends BaseMapper<VisitorTagEntity> {

    /**
     * 查询访客所有标签（JOIN cs_tag），按打标时间倒序。
     * SQL 实现见 resources/mapper/VisitorTagMapper.xml。
     *
     * @param visitorId 访客唯一标识
     * @return 标签列表，无标签时返回空列表
     */
    List<TagEntity> selectTagsByVisitorId(@Param("visitorId") String visitorId);

    /**
     * 检查访客是否已打某标签（幂等判断）。
     *
     * @param visitorId 访客唯一标识
     * @param tagId     标签 ID
     * @return true 表示已存在关联
     */
    default boolean existsTag(String visitorId, Long tagId) {
        return exists(Wrappers.<VisitorTagEntity>lambdaQuery()
                .eq(VisitorTagEntity::getVisitorId, visitorId)
                .eq(VisitorTagEntity::getTagId, tagId));
    }

    /**
     * 删除访客与指定标签的关联（取消打标）。
     *
     * @param visitorId 访客唯一标识
     * @param tagId     标签 ID
     */
    default void deleteByVisitorIdAndTagId(String visitorId, Long tagId) {
        delete(Wrappers.<VisitorTagEntity>lambdaQuery()
                .eq(VisitorTagEntity::getVisitorId, visitorId)
                .eq(VisitorTagEntity::getTagId, tagId));
    }
}
