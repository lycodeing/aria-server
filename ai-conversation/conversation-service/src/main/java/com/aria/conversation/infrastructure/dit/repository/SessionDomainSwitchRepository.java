package com.aria.conversation.infrastructure.dit.repository;

import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
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
     * @param cmd 域切换命令对象，见 {@link DomainSwitchRecord}
     */
    public void record(DomainSwitchRecord cmd) {
        try {
            SessionDomainSwitchDO switchDO = new SessionDomainSwitchDO();
            switchDO.setSessionId(cmd.sessionId());
            switchDO.setFromDomain(cmd.fromDomain());
            switchDO.setToDomain(cmd.toDomain());
            switchDO.setSwitchType(cmd.switchType());
            switchDO.setTriggerMessage(cmd.triggerMessage());
            switchDO.setReason(cmd.reason());
            switchDO.setMsgSeq(cmd.msgSeq());
            switchDO.setCreatedAt(OffsetDateTime.now());
            switchMapper.insert(switchDO);
            log.info("[Domain] 记录域切换 sessionId={} {}→{} type={}",
                    cmd.sessionId(), cmd.fromDomain(), cmd.toDomain(), cmd.switchType());
        } catch (Exception e) {
            log.error("[Domain] 记录域切换失败 sessionId={}", cmd.sessionId(), e);
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
