package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.csat.CsatRatingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsatService {

    private static final int INVALID_PARAM   = 40000;
    private static final int ALREADY_RATED   = 40901;
    private static final int NOT_FOUND       = 40400;

    /** 评价邀请有效期（小时） */
    private static final long EXPIRY_HOURS = 24;

    private final CsatRatingMapper mapper;

    /**
     * 创建评价邀请（幂等：同一 session 已存在时直接返回已有记录）。
     *
     * @param sessionId 会话 ID
     * @param visitorId 访客标识（可为 null，匿名访客）
     * @param agentId   接待坐席 ID（AI 会话为 null）
     * @param channel   HUMAN / AI
     * @return 新建或已有的 CsatRatingDO
     */
    @Transactional(rollbackFor = Exception.class)
    public CsatRatingDO createInvitation(String sessionId, String visitorId,
                                          Long agentId, String channel) {
        return mapper.findBySessionId(sessionId).orElseGet(() -> {
            CsatRatingDO do_ = new CsatRatingDO();
            do_.setSessionId(sessionId);
            do_.setVisitorId(visitorId);
            do_.setAgentId(agentId);
            do_.setChannel(channel != null ? channel : "AI");
            do_.setStatus("PENDING");
            do_.setRequestedAt(OffsetDateTime.now());
            do_.setExpiredAt(OffsetDateTime.now().plusHours(EXPIRY_HOURS));
            mapper.insert(do_);
            log.info("[CSAT] 评价邀请已创建 sessionId={} channel={}", sessionId, channel);
            return do_;
        });
    }

    /**
     * 访客提交评分。
     *
     * @param csatId  评价记录 ID
     * @param score   1–5 星
     * @param comment 文字说明（可 null）
     */
    @Transactional(rollbackFor = Exception.class)
    public void rate(Long csatId, Short score, String comment) {
        if (score == null || score < 1 || score > 5) {
            throw new BusinessException(INVALID_PARAM, "评分必须在 1–5 之间");
        }
        CsatRatingDO do_ = requireCsat(csatId);
        if (!"PENDING".equals(do_.getStatus())) {
            throw new BusinessException(ALREADY_RATED,
                "已评价或已过期，不可重复提交（status=" + do_.getStatus() + "）");
        }
        do_.setScore(score);
        do_.setComment(comment);
        do_.setStatus("RATED");
        do_.setRatedAt(OffsetDateTime.now());
        mapper.updateById(do_);
        log.info("[CSAT] 评价已提交 csatId={} score={}", csatId, score);
    }

    /**
     * 访客跳过评价。
     *
     * @param csatId 评价记录 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void skip(Long csatId) {
        CsatRatingDO do_ = requireCsat(csatId);
        if (!"PENDING".equals(do_.getStatus())) {
            // 幂等：非 PENDING 状态静默忽略
            log.debug("[CSAT] skip 忽略（已非 PENDING） csatId={} status={}", csatId, do_.getStatus());
            return;
        }
        mapper.updateStatus(csatId, "SKIPPED", null);
        log.info("[CSAT] 评价已跳过 csatId={}", csatId);
    }

    /**
     * 批量过期已超时的 PENDING 记录（供 Scheduler 调用）。
     *
     * @return 过期条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int expirePending() {
        List<CsatRatingDO> expired = mapper.findPendingExpired();
        if (expired.isEmpty()) return 0;
        List<Long> ids = expired.stream().map(CsatRatingDO::getId).toList();
        mapper.batchExpire(ids);
        log.info("[CSAT] 批量过期 {} 条 PENDING 评价", ids.size());
        return ids.size();
    }

    private CsatRatingDO requireCsat(Long id) {
        CsatRatingDO do_ = mapper.selectById(id);
        if (do_ == null) throw new BusinessException(NOT_FOUND, "评价记录不存在: " + id);
        return do_;
    }
}
