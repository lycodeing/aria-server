package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.ChatAppService;
import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.SessionStatus;
import jakarta.validation.Valid;
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
 *
 * <p>DDD 分层职责：
 * <ul>
 *   <li>Interface 层（本类）：入参格式校验、sessionId 生成、ChatEvent → SSE 格式转换</li>
 *   <li>Application 层（ChatAppService）：所有业务路由（人工接入判断、DIT、FAQ）</li>
 * </ul>
 *
 * <p>CORS 说明：
 * <ul>
 *   <li>{@code /stream}、{@code POST /}（非流式）为访客公开接口，通过方法级 {@code @CrossOrigin} 放行任意源</li>
 *   <li>其余接口（历史查询/转人工）由网关层统一管控 CORS，不单独放行</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    // ---- 常量定义 ----
    private static final String GUEST_SESSION_PREFIX = "guest-";
    private static final String DEFAULT_TRANSFER_REASON = "用户主动请求转人工";
    private static final String DEFAULT_TAG = "咨询";

    /**
     * sessionId 格式校验：与 ChatWebSocketHandler.SESSION_ID_PATTERN 保持一致。
     * 只允许字母、数字、下划线、连字符，长度 1~64，防止 Redis key 注入。
     */
    private static final java.util.regex.Pattern SESSION_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final ChatAppService chatService;

    /**
     * SSE 流式对话接口（访客公开，允许任意跨域）。
     *
     * <p>Controller 只负责：格式校验 → sessionId 生成 → ChatEvent → SSE 转换。
     * 所有业务路由（人工接入/DIT Pipeline/FAQ）在 Application 层完成。
     */
    @CrossOrigin(origins = "*")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return doneStream();
        }
        String sessionId = resolveSessionId(req.getSessionId());
        if (sessionId == null) {
            return Flux.concat(
                    Flux.just(ServerSentEvent.<String>builder()
                            .event(ChatEvent.EventType.ERROR)
                            .data("非法的 sessionId 格式")
                            .build()),
                    doneStream()
            );
        }

        return chatService.stream(sessionId, req.getMessage(), req.getDomainCode())
                .map(this::toSse)
                .concatWith(doneStream());
    }

    /**
     * 非流式对话接口（访客公开，允许任意跨域）。
     */
    @CrossOrigin(origins = "*")
    @PostMapping
    public R<Map<String, String>> chat(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return R.fail(400, "消息内容不能为空");
        }
        String sessionId = resolveSessionId(req.getSessionId());
        if (sessionId == null) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        String reply = chatService.chat(sessionId, req.getMessage());
        return R.ok(Map.of("reply", reply, "sessionId", sessionId));
    }

    /**
     * 获取对话历史。
     *
     * <p>支持两种模式：
     * <ul>
     *   <li>全量模式（sinceSeq 缺省或 ≤ 0）：返回最近 20 轮历史</li>
     *   <li>增量模式（sinceSeq &gt; 0）：仅返回 seq 严格大于 sinceSeq 的消息</li>
     * </ul>
     */
    @GetMapping("/history")
    public R<List<ConversationMessage>> history(
            @RequestParam String sessionId,
            @RequestParam(required = false, defaultValue = "0") long sinceSeq) {
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
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
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        chatService.clearHistory(sessionId);
        return R.ok(Map.of("message", "会话历史已清除", "sessionId", sessionId));
    }

    /**
     * 用户请求转人工。
     */
    @PostMapping("/transfer")
    public R<SessionQueueItem> transfer(@RequestBody @Valid TransferRequest req) {
        String reason = req.getTransferReason() != null ? req.getTransferReason() : DEFAULT_TRANSFER_REASON;
        String tag = req.getTag() != null ? req.getTag() : DEFAULT_TAG;
        return R.ok(chatService.requestTransfer(req.getSessionId(), req.getUserName(), reason, tag));
    }

    /**
     * 查询会话当前状态（前端 onMounted 兜底检测转接状态）。
     *
     * <p>当用户在 AI 工具触发转接后关闭页面，重新打开时前端无法从 localStorage
     * 得知转接已发生（因为 localStorage 标志由前端自行写入，页面关闭前来不及写）。
     * 此接口允许前端主动拉取后端 session 状态，若为 WAITING/ACTIVE 则自动恢复 WS 连接。
     *
     * <p>CORS：与 /history 等接口一致，由全局 {@code app.cors.allowed-origins} 管控，
     * 禁止通配符放行，避免任意源枚举会话状态。
     *
     * @param sessionId 会话 ID
     * @return 当前会话状态字符串（AI_CHAT / WAITING / ACTIVE / CLOSED）
     */
    @GetMapping("/state")
    public R<Map<String, String>> sessionState(@RequestParam String sessionId) {
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        String status = chatService.getSessionStatus(sessionId).getValue();
        return R.ok(Map.of("sessionId", sessionId, "status", status));
    }

    // ---- 私有工具方法 ----

    /**
     * 解析 sessionId：前端传入时校验格式；未传则生成合规 ID。
     * 格式非法时返回 null。
     */
    private String resolveSessionId(String sessionId) {
        if (sessionId == null) {
            return GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        }
        return SESSION_ID_PATTERN.matcher(sessionId).matches() ? sessionId : null;
    }

    /**
     * 将 ChatEvent 转换为 ServerSentEvent。
     */
    private ServerSentEvent<String> toSse(ChatEvent event) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(event.data());
        if (event.eventType() != null) {
            builder.event(event.eventType());
        }
        return builder.build();
    }

    /**
     * 终止 SSE 流的 done 事件。
     */
    private Flux<ServerSentEvent<String>> doneStream() {
        return Flux.just(ServerSentEvent.<String>builder()
                .event(ChatEvent.EventType.DONE)
                .data("[DONE]")
                .build());
    }

    // ---- Request / Response 类 ----

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
        @jakarta.validation.constraints.NotBlank(message = "sessionId 不能为空")
        @jakarta.validation.constraints.Pattern(
                regexp = "^[a-zA-Z0-9_\\-]{1,64}$",
                message = "sessionId 格式非法（只允许字母、数字、下划线、连字符，长度 1~64）")
        private String sessionId;

        @jakarta.validation.constraints.NotBlank(message = "userName 不能为空")
        private String userName;

        private String transferReason;
        private String tag;
    }
}
