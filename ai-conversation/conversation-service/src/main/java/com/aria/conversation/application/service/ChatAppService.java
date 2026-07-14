package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 对话路由分发器。
 *
 * <p>统一流式对话入口，根据会话状态和请求参数将请求分发到三条处理路径：
 * <ol>
 *   <li>已接入人工 → 直接返回提示，不走 AI</li>
 *   <li>有 domainCode → 域会话路径（DomainSessionAppService + DomainAgentService）</li>
 *   <li>无 domainCode → 通用 FAQ 路径（FaqChatAppService）</li>
 * </ol>
 *
 * <p>本类只做路由决策，不含任何业务编排逻辑，所有具体实现委托给对应 Service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    /** 会话队列服务，用于判断是否已接入人工及入队操作 */
    private final SessionQueueService     sessionQueueService;
    /** 域会话生命周期管理器，封装激活域读写和小模型路由决策 */
    private final DomainSessionAppService domainSessionService;
    /** FAQ 对话编排器，封装 RAG + 意图路由 + LLM 流程及转人工公共方法 */
    private final FaqChatAppService       faqChatService;
    /** 域 Agent 流式对话执行器，处理携带工具的域内对话 */
    private final DomainAgentService      domainAgentService;
    /** 意图分类服务，Tier1 关键词/正则 → Tier2 LLM 兜底 */
    private final IntentService           intentService;
    /** JSON 序列化工具，用于构造 SSE 事件载荷 */
    private final ObjectMapper            objectMapper;

    // -------------------------------------------------------
    // 统一对话入口
    // -------------------------------------------------------

    /**
     * 统一流式对话入口，返回 {@link ChatEvent} 流供 Controller 转换为 SSE。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @param domainCode 领域标识（可选，null 走通用 FAQ 流程）
     * @return ChatEvent 流
     */
    public Flux<ChatEvent> stream(String sessionId, String message, String domainCode) {
        // 1. 已接入人工 → 存历史，返回提示
        if (sessionQueueService.isActive(sessionId)) {
            return faqChatService.appendAndHint(sessionId, message);
        }
        // 2. 有 domainCode → 域路径
        if (StringUtils.isNotBlank(domainCode)) {
            return streamDomain(sessionId, message, domainCode);
        }
        // 3. 通用 FAQ 路径
        return faqChatService.stream(sessionId, message);
    }

    /**
     * 域路径处理：在 boundedElastic 线程完成阻塞操作（域会话管理 + 意图分类），
     * 再根据意图决策走转人工或 DomainAgentService。
     */
    private Flux<ChatEvent> streamDomain(String sessionId, String message, String domainCode) {
        return Mono.fromCallable(() -> {
                    // 阻塞操作：Redis 读写 + 小模型推理 + 意图分类
                    String activeDomain = domainSessionService.resolveActiveDomain(
                            sessionId, message, domainCode);
                    IntentResult intent = intentService.classify(message);
                    return new DomainRouteContext(activeDomain, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {
                    if (ctx.intent().requiresTransfer()) {
                        log.info("[Chat] domain 路径意图拦截 sessionId={} intent={}",
                                sessionId, ctx.intent().intent());
                        return faqChatService.handleTransfer(sessionId, ctx.intent());
                    }
                    return domainAgentService.streamChat(sessionId, ctx.activeDomain(), message);
                });
    }

    // -------------------------------------------------------
    // 非流式对话（Controller POST /api/v1/chat）
    // -------------------------------------------------------

    /**
     * 非流式对话，返回完整回复文本。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return AI 回复文本
     */
    public String chat(String sessionId, String userMessage) {
        return faqChatService.chat(sessionId, userMessage);
    }

    // -------------------------------------------------------
    // 历史查询（Controller /api/v1/chat/history）
    // -------------------------------------------------------

    /**
     * 获取全量历史消息（最近 N 轮），用于前端加载上下文。
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表
     */
    public List<ConversationMessage> getHistory(String sessionId) {
        return faqChatService.getHistory(sessionId);
    }

    /**
     * 增量获取历史消息（seq &gt; sinceSeq），用于断线重连后补齐空窗。
     *
     * @param sessionId 会话 ID
     * @param sinceSeq  客户端已知的最后一条消息 seq
     * @return seq 大于 sinceSeq 的消息列表
     */
    public List<ConversationMessage> getHistorySince(String sessionId, long sinceSeq) {
        return faqChatService.getHistorySince(sessionId, sinceSeq);
    }

    /**
     * 清除会话历史。
     *
     * @param sessionId 会话 ID
     */
    public void clearHistory(String sessionId) {
        faqChatService.clearHistory(sessionId);
    }

    // -------------------------------------------------------
    // 队列操作（Controller /api/v1/chat/transfer & /state）
    // -------------------------------------------------------

    /**
     * 用户主动请求转人工。
     *
     * @param sessionId      会话 ID
     * @param userName       用户名称
     * @param transferReason 转人工原因
     * @param tag            标签
     * @return 队列项
     */
    public SessionQueueItem requestTransfer(String sessionId, String userName,
                                            String transferReason, String tag) {
        return sessionQueueService.enqueue(sessionId, userName, transferReason, tag);
    }

    /**
     * 查询会话当前状态（供 ChatController GET /api/v1/chat/state 使用）。
     *
     * @param sessionId 会话 ID
     * @return 当前会话状态
     */
    public SessionStatus getSessionStatus(String sessionId) {
        return sessionQueueService.getSessionStatus(sessionId);
    }

    // -------------------------------------------------------
    // 向后兼容
    // -------------------------------------------------------

    /**
     * @deprecated 新代码应直接调用 {@link #stream}。
     *             本方法仅保留用于非 SSE 场景的向后兼容，后续版本将被移除。
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public Flux<String> streamChat(String sessionId, String userMessage) {
        return faqChatService.stream(sessionId, userMessage)
                .flatMap(e -> {
                    if (e.eventType() == null) {
                        try {
                            TokenPayload token = objectMapper.readValue(e.data(), TokenPayload.class);
                            return Flux.just(token.content() != null ? token.content() : "");
                        } catch (Exception ex) {
                            log.warn("[Chat] token payload 解析失败，降级返回原始 data", ex);
                            return Flux.just(e.data());
                        }
                    }
                    if (ChatEvent.EventType.TRANSFER.equals(e.eventType())) {
                        try {
                            TransferPayload payload = objectMapper.readValue(e.data(), TransferPayload.class);
                            return Flux.just(payload.message() != null ? payload.message() : e.data());
                        } catch (Exception ex) {
                            log.warn("[Chat] transfer payload 解析失败，降级返回原始 data", ex);
                            return Flux.just(e.data());
                        }
                    }
                    return Flux.empty();
                });
    }

    // -------------------------------------------------------
    // 内部记录
    // -------------------------------------------------------

    /**
     * 域路径路由阶段中间结果，携带最终确定的活跃域编码和意图分类结果。
     *
     * @param activeDomain 经域路由决策后的最终活跃域编码
     * @param intent       意图分类结果，决定后续走转人工还是 DomainAgentService
     */
    private record DomainRouteContext(String activeDomain, IntentResult intent) {}
}
