package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.ChatAppService;
import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话接口。
 * POST /api/v1/chat/stream    → SSE 流式回复（ACTIVE 时不走 AI）
 * POST /api/v1/chat           → 非流式回复
 * GET  /api/v1/chat/history   → 历史消息列表（R<> 包装）
 * DELETE /api/v1/chat/history → 清除历史（R<> 包装）
 * POST /api/v1/chat/transfer  → 用户请求转人工（R<> 包装）
 *
 * <p>CORS 说明：chat 接口为访客公开接口，允许任意源访问（访客页面可内嵌至任意站点）。
 * 座席管理接口（/api/v1/sessions）则限制为配置的前端域名。
 */
@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatController {

    // ---- 常量定义 ----
    private static final String GUEST_SESSION_PREFIX = "guest-";
    private static final String DEFAULT_TRANSFER_REASON = "用户主动请求转人工";
    private static final String DEFAULT_TAG = "咨询";
    private static final String AGENT_HINT_MSG = "（消息已发送给人工客服）";

    /**
     * sessionId 格式校验：与 ChatWebSocketHandler.SESSION_ID_PATTERN 保持一致。
     * 只允许字母、数字、下划线、连字符，长度 1~64，防止 Redis key 注入。
     */
    private static final java.util.regex.Pattern SESSION_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final ChatAppService chatService;
    private final SessionQueueService sessionQueueService;
    private final ObjectMapper objectMapper;

    /**
     * SSE 流式对话接口。
     * - 会话已接入人工（ACTIVE）：存历史，提示已转人工，不调 AI
     * - 会话未接入（WAITING/无）：
     *   1. 先检索知识库 hits
     *   2. 发送 event:sources（JSON 数组，含 docId/breadcrumb），前端用于展示溯源标签
     *   3. 流式输出 AI 回复
     * 前端用 native fetch + ReadableStream 消费，不经过 axios，无需 R<> 包装。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest req) {
        // 消息内容不能为空
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        }

        // sessionId：前端传入时校验格式，未传则服务端生成合规 ID
        String sessionId;
        if (req.getSessionId() != null) {
            if (!SESSION_ID_PATTERN.matcher(req.getSessionId()).matches()) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("非法的 sessionId 格式").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            sessionId = req.getSessionId();
        } else {
            sessionId = GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        }

        // 已接入人工 → 存消息到 history + 返回提示，不走 AI
        if (sessionQueueService.isActive(sessionId)) {
            chatService.saveVisitorMessage(sessionId, req.getMessage());
            return Flux.just(
                    ServerSentEvent.<String>builder().data(AGENT_HINT_MSG).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }

        // 有 domainCode → 走 DIT Pipeline（领域感知意图识别 + 槽位解析）
        if (req.getDomainCode() != null && !req.getDomainCode().isBlank()) {
            Flux<ServerSentEvent<String>> ditStream = chatService
                    .streamChatWithDomain(sessionId, req.getMessage(),
                            req.getDomainCode(), java.util.Map.of())
                    .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                    .concatWith(Flux.just(
                            ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
            return ditStream;
        }

        // 无 domainCode → 原有通用流程
        List<KnowledgeSearchResult.Hit> hits = chatService.searchHits(req.getMessage());

        Flux<ServerSentEvent<String>> aiStream = chatService.streamChat(sessionId, req.getMessage(), hits)
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));

        // 有知识库命中时才推送 event:sources，空命中不发，避免前端拼入 "[]"
        if (hits.isEmpty()) {
            return aiStream;
        }
        Flux<ServerSentEvent<String>> sourcesEvent = Flux.just(
                ServerSentEvent.<String>builder().event("sources").data(buildSourcesJson(hits)).build()
        );
        return sourcesEvent.concatWith(aiStream);
    }

    /**
     * 非流式对话接口。
     */
    @PostMapping
    public R<Map<String, String>> chat(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return R.fail(400, "消息内容不能为空");
        }
        String sessionId;
        if (req.getSessionId() != null) {
            if (!SESSION_ID_PATTERN.matcher(req.getSessionId()).matches()) {
                return R.fail(400, "非法的 sessionId 格式");
            }
            sessionId = req.getSessionId();
        } else {
            sessionId = GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        }
        String reply = chatService.chat(sessionId, req.getMessage());
        return R.ok(Map.of("reply", reply, "sessionId", sessionId));
    }

    /**
     * 获取对话历史。
     *
     * <p>支持两种模式：
     * <ul>
     *   <li>全量模式（sinceSeq 缺省或 ≤ 0）：返回最近 20 轮历史，供座席接入时加载上下文</li>
     *   <li>增量模式（sinceSeq &gt; 0）：仅返回 seq 严格大于 sinceSeq 的消息，
     *       客户端断线重连后用此模式补齐空窗消息，避免每次全量重拉</li>
     * </ul>
     *
     * @param sessionId 会话唯一标识
     * @param sinceSeq  起始 seq（不含），缺省 0 表示全量
     */
    @GetMapping("/history")
    public R<List<ConversationMessage>> history(
            @RequestParam String sessionId,
            @RequestParam(required = false, defaultValue = "0") long sinceSeq) {
        if (sinceSeq > 0L) {
            return R.ok(chatService.getHistorySince(sessionId, sinceSeq));
        }
        return R.ok(chatService.getHistory(sessionId));
    }

    /**
     * 清除对话历史。
     */
    @DeleteMapping("/history")
    public R<Map<String, String>> clearHistory(@RequestParam String sessionId) {
        chatService.clearHistory(sessionId);
        return R.ok(Map.of("message", "会话历史已清除", "sessionId", sessionId));
    }

    /**
     * 用户请求转人工。
     * 将当前会话加入座席等待队列，并通过 Redis Pub/Sub 实时通知座席。
     * POST /api/v1/chat/transfer
     */
    @PostMapping("/transfer")
    public R<SessionQueueItem> transfer(@RequestBody TransferRequest req) {
        String reason = req.getTransferReason() != null ? req.getTransferReason() : DEFAULT_TRANSFER_REASON;
        String tag = req.getTag() != null ? req.getTag() : DEFAULT_TAG;
        return R.ok(sessionQueueService.enqueue(req.getSessionId(), req.getUserName(), reason, tag));
    }

    // ---- Request 内部类（【强制】必须有 toString，使用 @Data 统一处理） ----

    /**
     * 将 hits 序列化为 JSON 数组，格式：[{"docId":"...","label":"..."}]。
     * 序列化失败时返回空数组，不阻断 SSE 流。
     */
    private String buildSourcesJson(List<KnowledgeSearchResult.Hit> hits) {
        if (hits.isEmpty()) return "[]";
        List<Map<String, String>> sources = hits.stream()
                .map(h -> {
                    String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                            ? h.getBreadcrumb() : "文档片段";
                    return Map.of(
                            "docId",  h.getDocId() != null ? h.getDocId() : "",
                            "label",  label
                    );
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @Data
    public static class ChatRequest {
        /**
         * 会话 ID，访客可传 null 由后端生成
         */
        private String sessionId;
        /**
         * 用户消息内容
         */
        private String message;
        /**
         * 领域标识（可选）。传入时走 DIT Pipeline，支持意图识别和工具调用。
         * 如 "ecommerce"、"finance"、"travel"。
         */
        private String domainCode;
    }

    @Data
    public static class TransferRequest {
        /**
         * 会话 ID（必填，格式同 sessionId 规范）
         */
        @jakarta.validation.constraints.NotBlank(message = "sessionId 不能为空")
        @jakarta.validation.constraints.Pattern(
            regexp = "^[a-zA-Z0-9_\\-]{1,64}$",
            message = "sessionId 格式非法（只允许字母、数字、下划线、连字符，长度 1~64）")
        private String sessionId;

        /**
         * 用户名称（必填）
         */
        @jakarta.validation.constraints.NotBlank(message = "userName 不能为空")
        private String userName;

        /**
         * 转人工原因（可选）
         */
        private String transferReason;

        /**
         * 标签（可选）
         */
        private String tag;
    }
}
