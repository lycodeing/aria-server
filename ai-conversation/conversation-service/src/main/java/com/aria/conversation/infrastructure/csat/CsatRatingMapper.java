package com.aria.conversation.infrastructure.csat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aria.conversation.domain.CsatStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface CsatRatingMapper extends BaseMapper<CsatRatingDO> {

    /** 按 sessionId 查找评价记录（全局唯一） */
    default Optional<CsatRatingDO> findBySessionId(String sessionId) {
        return Optional.ofNullable(selectOne(
            Wrappers.lambdaQuery(CsatRatingDO.class)
                .eq(CsatRatingDO::getSessionId, sessionId)));
    }

    /**
     * 幂等插入：依赖 uq_csat_session 唯一约束，并发下命中约束时静默跳过（不抛异常、不中断事务）。
     * 返回受影响行数：1 表示本次插入成功，0 表示 sessionId 已存在。
     */
    @Insert("""
            INSERT INTO cs_conversation.cs_csat_rating
                (session_id, visitor_id, agent_id, channel, status, requested_at, expired_at)
            VALUES
                (#{sessionId}, #{visitorId}, #{agentId}, #{channel}, #{status}, #{requestedAt}, #{expiredAt})
            ON CONFLICT (session_id) DO NOTHING
            """)
    int insertIfAbsent(CsatRatingDO rating);

    /** 更新评价状态（RATED / SKIPPED / EXPIRED） */
    default void updateStatus(Long id, CsatStatus status, OffsetDateTime ratedAt) {
        update(Wrappers.lambdaUpdate(CsatRatingDO.class)
            .set(CsatRatingDO::getStatus, status)
            .set(ratedAt != null, CsatRatingDO::getRatedAt, ratedAt)
            .eq(CsatRatingDO::getId, id));
    }

    /** 查询所有已过期但仍 PENDING 的记录，供 Scheduler 批量过期 */
    default List<CsatRatingDO> findPendingExpired() {
        return selectList(Wrappers.lambdaQuery(CsatRatingDO.class)
            .eq(CsatRatingDO::getStatus, CsatStatus.PENDING)
            .lt(CsatRatingDO::getExpiredAt, OffsetDateTime.now()));
    }

    /** 批量将指定 ID 标记为 EXPIRED（空列表安全跳过） */
    default void batchExpire(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        update(Wrappers.<CsatRatingDO>lambdaUpdate()
                .set(CsatRatingDO::getStatus, CsatStatus.EXPIRED)
                .in(CsatRatingDO::getId, ids));
    }
}
