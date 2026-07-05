package com.aria.conversation.infrastructure.dit.repository;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.mapper.SessionDomainSwitchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话域切换历史仓储（MySQL 持久化）。
 *
 * <p>写入失败不向上层传播，保证切换主流程不因审计写入失败而中断。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionDomainSwitchRepository {

    private final SessionDomainSwitchMapper switchMapper;

    /**
     * 记录一次域切换事件。
     *
     * @param sessionId      会话 ID
     * @param fromDomain     切换前域 code（初始进入时为 null）
     * @param toDomain       切换后域 code
     * @param switchType     切换类型，见 {@link SwitchType}
     * @param triggerMessage 触发切换的用户消息原文
     * @param reason         切换原因
     * @param msgSeq         关联消息 seq（可为 null）
     */
    public void record(String sessionId, String fromDomain, String toDomain,
                       String switchType, String triggerMessage, String reason, Long msgSeq) {
        try {
            SessionDomainSwitchDO switchDO = new SessionDomainSwitchDO();
            switchDO.setSessionId(sessionId);
            switchDO.setFromDomain(fromDomain);
            switchDO.setToDomain(toDomain);
            switchDO.setSwitchType(switchType);
            switchDO.setTriggerMessage(triggerMessage);
            switchDO.setReason(reason);
            switchDO.setMsgSeq(msgSeq);
            switchDO.setCreatedAt(OffsetDateTime.now());
            switchMapper.insert(switchDO);
            log.debug("[Domain] 记录域切换 sessionId={} from={} to={} type={}",
                    sessionId, fromDomain, toDomain, switchType);
        } catch (Exception e) {
            log.error("[Domain] 域切换历史写入失败 sessionId={} from={} to={} type={}",
                    sessionId, fromDomain, toDomain, switchType, e);
        }
    }

    /**
     * 查询会话的域切换历史（按时间升序）。
     *
     * @param sessionId 会话 ID
     * @return 切换历史列表
     */
    public List<SessionDomainSwitchDO> findHistory(String sessionId) {
        return switchMapper.findBySessionId(sessionId);
    }
}
