package com.aria.conversation.domain.model.event;

import java.util.List;

/**
 * SLA 自动升级请求事件（domain 层）。
 *
 * <p>由 SlaBreachNotifier（infrastructure）发布，SlaEscalationHandler（application）订阅。
 * 存放在 domain 层，供上下两层无层次违规地引用。
 *
 * @param sessionId     会话唯一标识
 * @param targetAgentId 升级目标座席 ID
 * @param breachIds     对应 cs_sla_breach.id 列表，升级成功后写 escalated_at
 */
public record SlaEscalationRequestedEvent(
        String sessionId,
        String targetAgentId,
        List<Long> breachIds
) {}
