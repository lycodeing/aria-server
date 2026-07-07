package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

/**
 * 内置 LangChain4j 工具：{@code switch_domain} 和 {@code transfer_to_agent}。
 *
 * <p>per-request 实例化（非 Spring Bean），构造器接收请求上下文 {@link InvocationParameters}
 * 和仓储依赖。{@code @Tool} 方法只声明需要 LLM 填充的业务参数（{@code @P} 注解），
 * 上下文字段通过 {@code this} 访问，不暴露给 LLM JSON Schema。
 *
 * <p>域列表信息由 {@code SystemPromptBuilder} 写入 system prompt，
 * LLM 从 system prompt 中获知可切换的域。
 */
@Slf4j
public class BuiltinTools {

    private final InvocationParameters ctx;
    private final SessionDomainRepository sessionDomainRepo;
    private final SessionDomainSwitchRepository domainSwitchRepo;
    private final ObjectMapper objectMapper;
    /** 转接入队服务，AI 工具触发转接时直接调用，不依赖前端消费 SSE */
    private final SessionQueueService sessionQueueService;

    /** 转接人工的意图 code，与前端 SSE payload 约定一致 */
    private static final String INTENT_CODE_AGENT_TRANSFER = "agent_transfer";

    public BuiltinTools(InvocationParameters ctx,
                        SessionDomainRepository sessionDomainRepo,
                        SessionDomainSwitchRepository domainSwitchRepo,
                        ObjectMapper objectMapper,
                        SessionQueueService sessionQueueService) {
        this.ctx = ctx;
        this.sessionDomainRepo = sessionDomainRepo;
        this.domainSwitchRepo = domainSwitchRepo;
        this.objectMapper = objectMapper;
        this.sessionQueueService = sessionQueueService;
    }

    /**
     * 当用户问题与当前服务域无关时切换到正确的服务域。
     * 可用域列表已在 system prompt 中说明。
     *
     * @param targetDomainCode 目标域 code
     * @param reason           切换原因（可选）
     * @return 工具执行结果描述
     */
    @Tool(name = "switch_domain",
          value = "当用户问题与当前服务域无关时调用，切换到正确的服务域。可用域列表见系统提示。")
    public String switchDomain(
            @P("目标域 code") String targetDomainCode,
            @P("切换原因（可选）") String reason) {

        if (targetDomainCode == null || targetDomainCode.isBlank()) {
            log.warn("[BuiltinTool] switch_domain 缺少 target_domain_code sessionId={}",
                    ctx.sessionId());
            return "域切换参数缺失，保持当前服务域。";
        }

        // 校验目标域是否在已知域列表中，防止 LLM 幻觉出不存在的域 code 被持久化到 Redis
        boolean knownDomain = ctx.allDomains().stream()
                .anyMatch(d -> d.code().equals(targetDomainCode));
        if (!knownDomain) {
            log.warn("[BuiltinTool] switch_domain 目标域 {} 不存在，保持当前服务域 sessionId={}",
                    targetDomainCode, ctx.sessionId());
            return "指定的服务域不存在，保持当前服务域。";
        }

        String safeReason = (reason == null || reason.isBlank()) ? "LLM 工具触发切换" : reason;
        log.info("[BuiltinTool] switch_domain {} → {} reason={} sessionId={}",
                ctx.currentDomainCode(), targetDomainCode, safeReason, ctx.sessionId());

        try {
            sessionDomainRepo.save(ctx.sessionId(), targetDomainCode);
            domainSwitchRepo.record(new DomainSwitchRecord(
                    ctx.sessionId(), ctx.currentDomainCode(), targetDomainCode,
                    SwitchType.LLM_TOOL, ctx.userMessage(), safeReason, null));
            ctx.eventSink().tryEmitNext(ChatEvent.domainSwitch(targetDomainCode));
        } catch (Exception e) {
            log.error("[BuiltinTool] switch_domain 持久化失败 sessionId={} target={}",
                    ctx.sessionId(), targetDomainCode, e);
            return "域切换失败，请重试。";
        }

        return "正在为您切换到对应服务...";
    }

    /**
     * 当用户明确要求转接人工客服时调用。
     *
     * <p>执行两步操作（顺序不可颠倒）：
     * <ol>
     *   <li>调用 {@link SessionQueueService#enqueue} 将 session 入队，
     *       使后端状态变为 WAITING，保证页面关闭后重开能通过 GET /chat/state 恢复</li>
     *   <li>发射 {@code event:transfer} SSE 通知前端同步 UI 状态（建立 WebSocket）</li>
     * </ol>
     *
     * @return 工具执行结果描述
     */
    @Tool(name = "transfer_to_agent",
          value = "当用户明确要求转接人工客服时调用")
    public String transferToAgent() {
        log.info("[BuiltinTool] transfer_to_agent sessionId={}", ctx.sessionId());
        try {
            // 步骤一：入队，session 状态变为 WAITING
            // 若已是 WAITING/ACTIVE（重复触发），SessionEnqueueException 单独捕获并继续发 SSE
            sessionQueueService.enqueue(
                    ctx.sessionId(), "访客", "AI 工具触发转接", "咨询");
        } catch (com.aria.conversation.application.exception.SessionEnqueueException e) {
            // 已入队场景（幂等兜底）：忽略入队失败，继续发 SSE 让前端同步 UI
            log.warn("[BuiltinTool] 会话已入队或入队失败，继续发 SSE sessionId={}", ctx.sessionId(), e);
        } catch (Exception e) {
            log.error("[BuiltinTool] transfer_to_agent 入队失败 sessionId={}", ctx.sessionId(), e);
            return "转接失败，请点击「转人工」按钮重试。";
        }

        // 步骤二：通知前端同步 UI 状态（不论入队是否幂等，均发 SSE）
        try {
            String payload = objectMapper.writeValueAsString(
                    new TransferPayload(INTENT_CODE_AGENT_TRANSFER, "已为您转接人工客服，请稍候。"));
            ctx.eventSink().tryEmitNext(ChatEvent.transfer(payload));
        } catch (Exception e) {
            log.error("[BuiltinTool] transfer SSE 序列化失败 sessionId={}", ctx.sessionId(), e);
        }
        return "已为您转接人工客服，请稍候。";
    }
}
