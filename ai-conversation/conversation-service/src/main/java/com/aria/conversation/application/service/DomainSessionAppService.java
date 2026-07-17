package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 域会话生命周期管理器。
 *
 * <p>封装域对话的两步初始化编排：
 * <ol>
 *   <li>读取或首次写入 session 激活域（Redis）</li>
 *   <li>ROUTER 小模型域路由决策，必要时切换域并写审计日志</li>
 * </ol>
 *
 * <p><b>线程要求：</b>包含阻塞操作（Redis 读写、小模型 HTTP 调用），
 * 调用方必须在 {@link Schedulers#boundedElastic()} 线程上调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainSessionAppService {

    /** 激活域 Redis 仓储，存储 sessionId → domainCode 的映射关系 */
    private final SessionDomainRepository        sessionDomainRepo;
    /** 域切换审计仓储，记录每次域变更的原因、来源和时间 */
    private final SessionDomainSwitchRepository  domainSwitchRepo;
    /** 对话历史仓储，提供最近 N 轮历史作为域路由的多轮上下文 */
    private final ConversationHistoryRepository  historyRepository;
    /** 域路由服务（@Primary 实现为 HybridDomainRoutingService），Tier1 规则 → Tier2 小模型 */
    private final DomainRoutingService           domainRoutingService;

    /**
     * 解析当前会话的活跃域，编排域初始化和路由流程。
     *
     * <p>会话记录的创建由 {@code /session/init} 接口负责，本方法不再隐式初始化。
     *
     * <p><b>线程要求：</b>包含阻塞操作（Redis 读写、HTTP 调用），
     * 调用方必须在 {@link Schedulers#boundedElastic()} 线程上调用。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息（用于路由上下文和审计记录）
     * @param domainCode 前端传入的默认域标识，仅首次进入时使用
     * @return 最终确定的活跃域编码
     */
    public String resolveActiveDomain(String sessionId, String message, String domainCode) {
        String activeDomain = resolveOrInitDomain(sessionId, message, domainCode);
        return routeDomainIfNeeded(sessionId, message, activeDomain);
    }

    /**
     * 读取 session 当前激活域；若不存在（首次进入），则以 {@code domainCode} 初始化
     * 并写入一条 {@link SwitchType#INITIAL} 类型的域切换记录。
     */
    private String resolveOrInitDomain(String sessionId, String message, String domainCode) {
        return sessionDomainRepo.find(sessionId).orElseGet(() -> {
            saveDomainSwitch(sessionId, null, domainCode, SwitchType.INITIAL, message, "用户进入服务入口");
            log.info("[DomainSession] sessionId={} 初始化激活域={}", sessionId, domainCode);
            return domainCode;
        });
    }

    /**
     * 调用域路由服务进行路由决策；若建议切换则更新 Redis 激活域并写审计日志。
     * 路由过程异常时降级保持当前域，不中断对话流程。
     */
    private String routeDomainIfNeeded(String sessionId, String message, String activeDomain) {
        try {
            List<ConversationMessage> history = historyRepository.findAll(sessionId);
            DomainRoutingService.RouteResult routing =
                    domainRoutingService.route(message, activeDomain, history);
            if (!routing.shouldSwitch()) {
                return activeDomain;
            }
            String newDomain = routing.suggestedDomain();
            saveDomainSwitch(sessionId, activeDomain, newDomain,
                    SwitchType.ROUTER_MODEL, message, "小模型检测切换");
            log.info("[DomainSession] sessionId={} 域切换 {} -> {}", sessionId, activeDomain, newDomain);
            return newDomain;
        } catch (Exception e) {
            log.warn("[DomainSession] sessionId={} 路由异常，降级保持当前域={}", sessionId, activeDomain, e);
            return activeDomain;
        }
    }

    /**
     * 顺序保存域绑定关系并记录域切换审计日志（非事务性双写）。
     *
     * <p>先更新 Redis 激活域绑定，再写入审计日志。两步操作相互独立，
     * 若第二步失败，Redis 已更新但审计记录缺失（最终一致，非原子）。
     * 业务可接受此风险；如需强一致，需引入分布式事务或补偿机制。
     *
     * @param sessionId  会话 ID
     * @param fromDomain 切换前的域（首次进入时为 null）
     * @param toDomain   切换后的目标域
     * @param switchType 切换类型，见 {@link SwitchType}
     * @param message    触发切换的用户消息
     * @param reason     切换原因描述，用于审计分析
     */
    private void saveDomainSwitch(String sessionId, String fromDomain, String toDomain,
                                  String switchType, String message, String reason) {
        sessionDomainRepo.save(sessionId, toDomain);
        domainSwitchRepo.record(new DomainSwitchRecord(
                sessionId, fromDomain, toDomain, switchType, message, reason, null));
    }
}
