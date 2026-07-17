package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * FAQ 对话编排器。
 *
 * <p>编排通用 FAQ 链路：知识库检索（RAG）→ 意图分类 → 路由分支 → 流式 token 生成。
 * {@link #handleTransfer} 方法同时被 Domain 路径复用，实现转人工的统一处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqChatAppService {

    /** 超出业务范围时的统一拒答回复，所有 OUT_OF_SCOPE 意图均返回此文案 */
    private static final String OUT_OF_SCOPE_REPLY =
            "抱歉，我是专业的客服助手，只能回答业务相关的问题，无法帮您解答这个问题。";
    /** 系统自动触发转人工时写入队列的原因描述，用于座席侧展示 */
    private static final String TRANSFER_AUTO_REASON = "系统识别到用户需要人工服务";
    /** 转人工队列条目的默认标签，用于座席分组路由 */
    private static final String TRANSFER_DEFAULT_TAG = "咨询";
    /** FAQ 路径转人工时的 intentCode 占位符，区别于 DIT 域路径使用真实意图 code */
    private static final String FAQ_TRANSFER_INTENT_CODE = "faq_transfer";
    /** 会话已接入人工时的提示文案，告知用户消息已转发给座席 */
    private static final String AGENT_HINT_MSG = "（消息已发送给人工客服）";

    /** AI 模型工厂，提供流式对话 ChatModel 实例 */
    private final DynamicModelFactory            aiClient;
    /** 对话历史仓储，负责追加和读取多轮对话上下文 */
    private final ConversationHistoryRepository  historyRepository;
    /** 知识库 RAG 检索客户端，根据用户消息向量检索相关知识块 */
    private final KnowledgeServiceClient         knowledgeServiceClient;
    /** 意图分类服务（@Primary 实现为 HybridIntentService），Tier1 规则 → Tier2 LLM */
    private final IntentService                  intentService;
    /** 会话队列服务，提供入队和状态查询能力 */
    private final SessionQueueService            sessionQueueService;
    /** JSON 序列化工具，用于构造 TransferPayload、sources 等 SSE 载荷 */
    private final ObjectMapper                   objectMapper;
    /** CSAT 服务，AI 对话流结束后追加评价邀请事件 */
    private final CsatService                    csatService;
    /** 对话持久化仓储，用于消息发送前的会话存在性校验 */
    private final ConversationPersistRepository  persistRepository;

    /**
     * 已接入人工时的消息处理：仅追加历史记录并返回提示，不调用 AI。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return 仅含提示文案的单元素 token 事件流
     */
    public Flux<ChatEvent> appendAndHint(String sessionId, String message) {
        historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
        return Flux.just(ChatEvent.token(AGENT_HINT_MSG, objectMapper));
    }

    /**
     * 通用 FAQ 流程：RAG 检索 + 意图分类 + 路由分支 + 流式 token 生成。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ChatEvent 流，包含 token 事件，RAG 命中时首先发送 sources 事件
     */
    public Flux<ChatEvent> stream(String sessionId, String message) {
        // 会话必须已通过 /session/init 接口创建，此处做防御性校验
        if (!persistRepository.existsBySessionId(sessionId)) {
            log.warn("[FAQ] 会话不存在 sessionId={}，前端可能未调用 /session/init", sessionId);
            return Flux.just(ChatEvent.error("会话不存在，请刷新页面重试", objectMapper));
        }
        return Mono.fromCallable(() -> {
                    historyRepository.append(sessionId, MessageRole.USER.getValue(), message);
                    List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(message);
                    IntentResult intent = intentService.classify(message);
                    log.debug("[FAQ] sessionId={} intent={} confidence={}",
                            sessionId, intent.intent(), intent.confidence());
                    return new FaqContext(hits, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> buildEventStream(sessionId, message, ctx));
    }

    /**
     * 转人工处理，FAQ 路径和 Domain 路径共用。
     *
     * <p>入队操作失败时仅打 warn 日志，不抛出异常，确保 TRANSFER 事件始终能发送给前端。
     *
     * @param sessionId 会话 ID
     * @param intent    触发转人工的意图分类结果，用于区分 COMPLAINT 和 TRANSFER_REQUEST 回复文案
     * @return 单元素 TRANSFER 语义事件流；序列化失败时降级为 token 事件流
     */
    public Flux<ChatEvent> handleTransfer(String sessionId, IntentResult intent) {
        try {
            sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
        } catch (Exception e) {
            log.warn("[FAQ] 自动转人工入队失败 sessionId={}", sessionId, e);
        }
        String reply = intent.intent() == IntentType.COMPLAINT
                ? "非常抱歉给您带来了不好的体验，我已为您转接人工客服，请稍候。"
                : "好的，我已为您转接人工客服，请稍候。";
        historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(), reply);
        try {
            // 使用意图原始 code，Domain 路径（如 transfer_request / complaint）能正确传给前端
            String intentCode = intent.intentCode() != null && !intent.intentCode().isBlank()
                    ? intent.intentCode() : FAQ_TRANSFER_INTENT_CODE;
            String json = objectMapper.writeValueAsString(
                    new TransferPayload(intentCode, reply));
            return Flux.just(ChatEvent.transfer(json));
        } catch (JsonProcessingException e) {
            log.warn("[FAQ] transfer payload 序列化失败 sessionId={}", sessionId, e);
            return Flux.just(ChatEvent.token(reply, objectMapper));
        }
    }

    private Flux<ChatEvent> buildEventStream(String sessionId, String message, FaqContext ctx) {
        if (ctx.intent().requiresTransfer()) {
            return handleTransfer(sessionId, ctx.intent());
        }
        if (ctx.intent().intent() == IntentType.OUT_OF_SCOPE) {
            historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(), OUT_OF_SCOPE_REPLY);
            return Flux.just(ChatEvent.token(OUT_OF_SCOPE_REPLY, objectMapper));
        }
        return buildLlmStream(sessionId, message, ctx);
    }

    private Flux<ChatEvent> buildLlmStream(String sessionId, String message, FaqContext ctx) {
        List<KnowledgeSearchResult.Hit> effectiveHits =
                ctx.intent().skipRag() ? List.of() : ctx.hits();
        String systemPrompt = SystemPromptBuilder.build(effectiveHits);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder replyBuf = new StringBuilder();

        Flux<ChatEvent> tokenStream = aiClient.streamChat(aiPrompt, systemPrompt)
                .map(token -> {
                    replyBuf.append(token);
                    return ChatEvent.token(token, objectMapper);
                })
                .onErrorResume(e -> {
                    log.warn("[FAQ] LLM 调用失败 sessionId={}", sessionId, e);
                    return Flux.just(ChatEvent.error("抱歉，AI 服务暂时不可用，请稍后重试。", objectMapper));
                })
                .doFinally(s -> {
                    if (!replyBuf.isEmpty()) {
                        historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(),
                                replyBuf.toString());
                    }
                });

        if (effectiveHits.isEmpty()) {
            return appendCsatEvent(tokenStream, sessionId);
        }
        ChatEvent sourcesEvent = buildSourcesEvent(effectiveHits);
        Flux<ChatEvent> withSources = tokenStream.switchOnFirst((signal, flux) -> {
            if (signal.hasValue()) {
                String type = signal.get() != null ? signal.get().eventType() : null;
                if (ChatEvent.EventType.ERROR.equals(type)) {
                    return flux;
                }
            }
            return Flux.concat(Flux.just(sourcesEvent), flux);
        });
        return appendCsatEvent(withSources, sessionId);
    }

    /**
     * 在 AI 对话 Flux 末尾追加 CSAT 评价邀请事件。
     * 失败时静默降级（仅记录 warn），不影响主流程。
     */
    private Flux<ChatEvent> appendCsatEvent(Flux<ChatEvent> flux, String sessionId) {
        return flux.concatWith(Flux.defer(() -> {
            try {
                CsatRatingDO csat = csatService.createInvitation(sessionId, null, null, "AI");
                String payload = objectMapper.writeValueAsString(
                        com.aria.conversation.application.service.support.CsatInvites.payload(csat));
                return Flux.just(new ChatEvent(ChatEvent.EventType.CSAT_REQUEST, payload));
            } catch (Exception e) {
                log.warn("[CSAT] AI 流追加评价邀请失败 sessionId={}", sessionId, e);
                return Flux.empty();
            }
        }));
    }

    /**
     * 将 RAG 命中结果序列化为 sources SSE 事件。
     * label 优先使用文档面包屑（breadcrumb），缺失时降级为"文档片段"。
     */
    private ChatEvent buildSourcesEvent(List<KnowledgeSearchResult.Hit> hits) {
        List<Map<String, String>> sources = hits.stream()
                .map(h -> Map.of(
                        "docId", h.getDocId() != null ? h.getDocId() : "",
                        "label", StringUtils.isNotBlank(h.getBreadcrumb())
                                ? h.getBreadcrumb() : "文档片段"))
                .toList();
        try {
            return ChatEvent.sources(objectMapper.writeValueAsString(sources));
        } catch (JsonProcessingException e) {
            log.warn("[FAQ] sources 序列化失败，降级返回空数组", e);
            return ChatEvent.sources("[]");
        }
    }

    /**
     * 非流式对话，返回完整回复文本（供 ChatAppService 代理 Controller 的非流式端点使用）。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return AI 回复文本
     */
    public String chat(String sessionId, String userMessage) {
        historyRepository.append(sessionId, MessageRole.USER.getValue(), userMessage);
        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits);
        String reply = aiClient.chat(toAiPrompt(historyRepository.findAll(sessionId)), systemPrompt);
        historyRepository.append(sessionId, MessageRole.ASSISTANT.getValue(), reply);
        return reply;
    }

    /**
     * 获取全量历史消息（最近 N 轮），用于前端加载上下文。
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表
     */
    public List<com.aria.conversation.domain.ConversationMessage> getHistory(String sessionId) {
        return historyRepository.findAll(sessionId);
    }

    /**
     * 增量获取历史消息（seq &gt; sinceSeq），用于断线重连后补齐空窗。
     *
     * @param sessionId 会话 ID
     * @param sinceSeq  客户端已知的最后一条消息 seq
     * @return seq 大于 sinceSeq 的消息列表
     */
    public List<com.aria.conversation.domain.ConversationMessage> getHistorySince(
            String sessionId, long sinceSeq) {
        return historyRepository.findSince(sessionId, sinceSeq);
    }

    /**
     * 清除会话历史。
     *
     * @param sessionId 会话 ID
     */
    public void clearHistory(String sessionId) {
        historyRepository.delete(sessionId);
    }

    private List<ChatMessage> toAiPrompt(List<com.aria.conversation.domain.ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }

    /** FAQ 路由阶段的中间结果，携带 RAG 命中列表和意图分类结果。 */
    private record FaqContext(List<KnowledgeSearchResult.Hit> hits, IntentResult intent) {}
}
