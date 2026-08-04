package com.aria.conversation.interfaces.assembler;

import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.aria.conversation.interfaces.rest.vo.SessionMessageVO;
import com.aria.conversation.interfaces.rest.vo.SessionRecordVO;

import java.time.Duration;

/**
 * 会话记录领域对象 ↔ 接口层 VO 转换器。
 *
 * <p>静态工具类，集中管理 {@link ConversationEntity} → {@link SessionRecordVO}、
 * {@link ConversationMessageEntity} → {@link SessionMessageVO} 的映射逻辑，
 * 使 Controller / ApplicationService 不内联转换代码（对齐 {@code UserAssembler} 范式）。
 */
public final class SessionAssembler {

    private SessionAssembler() {
    }

    /**
     * 会话实体 → 列表项 VO。
     *
     * <p>agentName 已在写路径快照进 {@code cs_conversation.agent_name}，此处直接读取，
     * 无需跨服务实时解析；快照为空时（历史遗留数据）回退展示 agentId。
     *
     * @param e        会话实体
     * @param msgCount 该会话消息总数（批量聚合结果）
     */
    public static SessionRecordVO toRecordVO(ConversationEntity e, int msgCount) {
        String agentName = e.getAgentName() != null && !e.getAgentName().isBlank()
                ? e.getAgentName()
                : e.getAgentId();
        return new SessionRecordVO(
                e.getSessionId(),
                e.getVisitorName(),
                e.getAgentId(),
                agentName,
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getTag(),
                e.getTransferReason(),
                e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                e.getAcceptedAt() != null ? e.getAcceptedAt().toString() : null,
                e.getEndedAt() != null ? e.getEndedAt().toString() : null,
                e.getClosedBy() != null ? e.getClosedBy().name() : null,
                msgCount,
                null,   // csatScore — CSAT 数据源接线后补充
                null,   // csatComment
                calcDurationSec(e)
        );
    }

    /**
     * 消息实体 → 详情抽屉消息 VO。
     */
    public static SessionMessageVO toMessageVO(ConversationMessageEntity e) {
        return new SessionMessageVO(
                e.getRole() != null ? e.getRole().getValue() : null,
                e.getContent(),
                e.getSeq(),
                e.getCreatedAt() != null ? e.getCreatedAt().toEpochSecond() * 1000 : null,
                e.getToolName(),
                e.getToolRequestId()
        );
    }

    /**
     * 计算会话时长（秒）。进行中（无 endedAt）或时间异常（endedAt 早于 startedAt）时返回 null。
     */
    private static Long calcDurationSec(ConversationEntity e) {
        if (e.getStartedAt() == null || e.getEndedAt() == null) {
            return null;
        }
        long dur = Duration.between(e.getStartedAt(), e.getEndedAt()).getSeconds();
        return dur >= 0 ? dur : null;
    }
}
