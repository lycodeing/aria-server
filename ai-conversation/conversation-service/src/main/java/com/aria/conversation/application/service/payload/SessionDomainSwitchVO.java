package com.aria.conversation.application.service.payload;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;

import java.time.OffsetDateTime;

/** 会话域切换历史 VO，用于 REST 响应（不暴露 DO 内部结构）。 */
public record SessionDomainSwitchVO(
        Long id,
        String sessionId,
        String fromDomain,
        String toDomain,
        String switchType,
        String triggerMessage,
        String reason,
        Long msgSeq,
        OffsetDateTime createdAt
) {
    public static SessionDomainSwitchVO from(SessionDomainSwitchDO do_) {
        return new SessionDomainSwitchVO(
                do_.getId(), do_.getSessionId(), do_.getFromDomain(), do_.getToDomain(),
                do_.getSwitchType(), do_.getTriggerMessage(), do_.getReason(),
                do_.getMsgSeq(), do_.getCreatedAt()
        );
    }
}
