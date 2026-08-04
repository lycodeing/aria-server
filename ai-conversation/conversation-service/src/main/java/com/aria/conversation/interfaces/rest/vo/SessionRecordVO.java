package com.aria.conversation.interfaces.rest.vo;

/**
 * 会话查询列表项 VO（返回给前端）。
 *
 * @param sessionId       会话唯一标识
 * @param visitorName     访客名称
 * @param agentId         接入座席 ID（AI_CHAT/WAITING 阶段为 null）
 * @param agentName       接入座席显示名称（批量关联 sys_user.display_name）
 * @param status          会话状态：AI_CHAT / WAITING / ACTIVE / CLOSED
 * @param tag             问题标签
 * @param transferReason  转接原因
 * @param startedAt       会话开始时间（ISO 8601）
 * @param acceptedAt      座席接入时间（可能为 null）
 * @param endedAt         会话结束时间（进行中为 null）
 * @param closedBy        结束方：AGENT / VISITOR / SYSTEM（进行中为 null）
 * @param msgCount        消息总数
 * @param csatScore       满意度评分 1-5（未评价为 null）
 * @param csatComment     满意度评语（可能为 null）
 * @param durationSec     会话时长（秒），进行中为 null
 */
public record SessionRecordVO(
        String sessionId,
        String visitorName,
        String agentId,
        String agentName,
        String status,
        String tag,
        String transferReason,
        String startedAt,
        String acceptedAt,
        String endedAt,
        String closedBy,
        Integer msgCount,
        Integer csatScore,
        String csatComment,
        Long durationSec
) {}
