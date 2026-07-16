package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface CannedResponseMapper extends BaseMapper<CannedResponseDO> {

    /**
     * 全文检索快捷回复（title + content），结合权限过滤（PUBLIC 或本人 PRIVATE）。
     * 按 use_count 倒序，限制返回条数。
     */
    @Select("""
        SELECT * FROM cs_conversation.cs_canned_response
        WHERE deleted = FALSE
          AND to_tsvector('simple', title || ' ' || content) @@ plainto_tsquery('simple', #{q})
          AND (scope = 'PUBLIC' OR (scope = 'PRIVATE' AND owner_id = #{agentId}))
          AND (#{groupId}::BIGINT IS NULL OR group_id = #{groupId}::BIGINT)
        ORDER BY use_count DESC
        LIMIT #{limit}
        """)
    List<CannedResponseDO> searchByKeyword(@Param("q") String q,
                                           @Param("agentId") Long agentId,
                                           @Param("groupId") Long groupId,
                                           @Param("limit") int limit);

    /** 原子递增 use_count（无需分布式锁，数据库原子操作保证；仅对未软删除记录生效） */
    @Update("UPDATE cs_conversation.cs_canned_response SET use_count = use_count + 1 WHERE id = #{id} AND deleted = FALSE")
    void incrementUseCount(@Param("id") Long id);

    /** 查询指定坐席的私人快捷回复列表 */
    default List<CannedResponseDO> selectPrivateByAgent(Long agentId) {
        return selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getScope, "PRIVATE")
                .eq(CannedResponseDO::getOwnerId, agentId)
                .eq(CannedResponseDO::getDeleted, false)
                .orderByAsc(CannedResponseDO::getSortOrder));
    }
}
