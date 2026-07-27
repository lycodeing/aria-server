package com.aria.conversation.infrastructure.canned;

import com.aria.conversation.domain.CannedResponseScope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CannedResponseMapper extends BaseMapper<CannedResponseDO> {

    /**
     * 全文检索快捷回复（title + content），结合权限过滤（PUBLIC 或本人 PRIVATE）。
     * 使用 PostgreSQL to_tsvector/plainto_tsquery，SQL 定义在 CannedResponseMapper.xml。
     */
    List<CannedResponseDO> searchByKeyword(@Param("q") String q,
                                           @Param("agentId") Long agentId,
                                           @Param("groupId") Long groupId,
                                           @Param("limit") int limit);

    /** 原子递增 use_count（数据库侧原子操作；仅对未软删除记录生效） */
    default void incrementUseCount(Long id) {
        update(null, Wrappers.<CannedResponseDO>lambdaUpdate()
                .setSql("use_count = use_count + 1")
                .eq(CannedResponseDO::getId, id)
                .eq(CannedResponseDO::getDeleted, false));
    }

    /** 查询指定坐席的私人快捷回复列表 */
    default List<CannedResponseDO> selectPrivateByAgent(Long agentId) {
        return selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getScope, CannedResponseScope.PRIVATE)
                .eq(CannedResponseDO::getOwnerId, agentId)
                .eq(CannedResponseDO::getDeleted, false)
                .orderByAsc(CannedResponseDO::getSortOrder));
    }
}
