package com.aria.conversation.infrastructure.dit.domain;

/** 域切换记录命令对象（减少 record() 方法参数个数，符合阿里规范 §1.6.1）。 */
public record DomainSwitchRecord(
        String sessionId,
        String fromDomain,
        String toDomain,
        String switchType,
        String triggerMessage,
        String reason,
        Long msgSeq
) {}
