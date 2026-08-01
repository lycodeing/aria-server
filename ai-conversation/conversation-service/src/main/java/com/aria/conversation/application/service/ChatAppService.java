package com.aria.conversation.application.service;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.common.web.redis.RedisCounterHelper;
import com.aria.conversation.application.service.cancellation.CancellationRegistry;
import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    /** 多意图分类服务（三级级联：规则 → Embedding 原型 → LLM） */
    private final MultiIntentService      multiIntentService;
    /** JSON 序列化工具，用于构造 SSE 事件载荷 */
    private final ObjectMapper            objectMapper;
    /** 取消信号注册表，供 cancel() 委托 */
    private final CancellationRegistry    cancellationRegistry;
    /** Redis 缓存操作，供 requestId 完成后标记 done */
    private final RedisCacheHelper        cache;
    /** Redis 计数器，供 requestId 幂等判定（firstAccess = SETNX + EX 原子） */
    private final RedisCounterHelper      counter;

    /** requestId 幂等窗口（2 分钟，覆盖最坏情况下多工具链执行耗时） */
    private static final Duration REQUEST_IDEMPOTENCY_TTL = Duration.ofMinutes(2);
    private static final String REQ_KEY_PREFIX = "chat:req:";

    // -------------------------------------------------------
    // 统一对话入口
    // -------------------------------------------------------

    /**
     * 统一流式对话入口，返回 {@link ChatEvent} 流供 Controller 转换为 SSE。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @param domainCode 领域标识（可选，null 走通用 FAQ 流程）
     * @param requestId  请求幂等键（可选，null 时不做幂等检查）
     * @return ChatEvent 流
     */
    public Flux<ChatEvent> stream(String sessionId, String message,
                                   String domainCode, String requestId) {
        Flux<ChatEvent> stream = resolveStream(sessionId, message, domainCode);
        // requestId 幂等检查（可选）
        if (requestId == null || requestId.isBlank()) {
            return stream;
        }
        String key = REQ_KEY_PREFIX + sessionId + ":" + requestId;
        // I3 修复：firstAccess 是阻塞 Redis 调用，不能在 WebFlux event loop 线程上同步执行，
        // 用 Mono.fromCallable + subscribeOn(boundedElastic) 包装为响应式。
        return Mono.fromCallable(() -> {
                    try {
                        return counter.firstAccess(key, REQUEST_IDEMPOTENCY_TTL);
                    } catch (Exception e) {
                        // Redis 异常降级放行（M4 修复：幂等检查非核心功能）
                        log.warn("[Chat] firstAccess 失败，降级放行 sessionId={} requestId={}", sessionId, requestId, e);
                        return true;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(firstAccess -> {
                    if (!firstAccess) {
                        log.warn("[Chat] 重复请求被拦截 sessionId={} requestId={}", sessionId, requestId);
                        return Flux.just(ChatEvent.error("请勿重复提交，请等待上一条消息处理完成", objectMapper));
                    }
                    // 流终止时刷新 requestId key TTL，防止 TTL 内重复提交被误判为新请求
                    return stream.doFinally(signal -> {
                        try {
                            cache.set(key, "done", REQUEST_IDEMPOTENCY_TTL);
                        } catch (Exception e) {
                            log.warn("[Chat] 刷新 requestId TTL 失败 key={}", key, e);
                        }
                    });
                });
    }

    /** 路由决策：已接入人工 → 存历史返回提示；有 domainCode → 域路径；其余 → FAQ 路径 */
    private Flux<ChatEvent> resolveStream(String sessionId, String message, String domainCode) {
        if (sessionQueueService.isActive(sessionId)) {
            return faqChatService.appendAndHint(sessionId, message);
        }
        if (StringUtils.isNotBlank(domainCode)) {
            return streamDomain(sessionId, message, domainCode);
        }
        return faqChatService.stream(sessionId, message);
    }

    /**
     * 域路径处理：单一职责——确定活跃域、域感知意图分类、路由决策，三步串行。
     *
     * <p>域感知意图分类（{@link MultiIntentService#classifyMulti(String, String)}）合并了
     * {@code __system__} 路由级意图和活跃域业务意图，LLM 拿到完整上下文。
     * 两步都在同一 {@code boundedElastic} 线程上执行，避免了并行时意图分类看不到活跃域意图的问题。
     */
    private Flux<ChatEvent> streamDomain(String sessionId, String message, String domainCode) {
        return Mono.fromCallable(() -> {
                    // 1. 确定活跃域（Redis + 关键词 + 小模型域路由，~50ms）
                    String activeDomain = domainSessionService.resolveActiveDomain(
                            sessionId, message, domainCode);
                    // 2. 域感知意图分类：__system__ 意图 + activeDomain 业务意图合并后分类
                    MultiIntentResult multi = multiIntentService.classifyMulti(message, activeDomain);
                    return Map.entry(activeDomain, multi);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(entry -> {
                    String activeDomain = entry.getKey();
                    MultiIntentResult multi = entry.getValue();
                    if (multi.requiresTransfer()) {
                        log.info("[Chat] domain 路径多意图转人工拦截 sessionId={} primary={}",
                                sessionId, multi.primaryIntent().intent());
                        return faqChatService.handleTransfer(sessionId, multi.primaryIntent());
                    }
                    return domainAgentService.streamChat(
                            sessionId, activeDomain, message, multi.intentCodes());
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

    /**
     * 取消指定会话正在进行的 Agent 生成。
     * 触发取消标志（阻止后续工具执行）+ Reactor Sink 信号（截断 LLM 流）。
     *
     * @param sessionId 会话 ID
     */
    public void cancel(String sessionId) {
        cancellationRegistry.cancel(sessionId);
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

}
