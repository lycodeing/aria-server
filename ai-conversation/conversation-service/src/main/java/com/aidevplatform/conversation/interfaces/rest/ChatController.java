package com.aidevplatform.conversation.interfaces.rest;

import com.aidevplatform.conversation.application.service.ChatAppService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 对话接口。
 * POST /api/v1/chat/stream  → SSE 流式回复（前端 EventSource 消费）
 * POST /api/v1/chat         → 非流式回复
 * GET  /api/v1/chat/history → 历史消息列表
 * DELETE /api/v1/chat/history → 清除历史
 */
@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatAppService chatService;

    public ChatController(ChatAppService chatService) {
        this.chatService = chatService;
    }

    /**
     * SSE 流式对话接口。
     * 前端 fetch with ReadableStream 或 EventSource 消费。
     * 每个 chunk 为纯文本（delta content）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest req) {
        String sessionId = req.getSessionId() != null ? req.getSessionId() : "guest-" + System.currentTimeMillis();
        return chatService.streamChat(sessionId, req.getMessage())
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("[DONE]")
                                .build()));
    }

    /**
     * 非流式对话接口（用于简单场景）。
     */
    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest req) {
        String sessionId = req.getSessionId() != null ? req.getSessionId() : "guest-" + System.currentTimeMillis();
        String reply = chatService.chat(sessionId, req.getMessage());
        return Map.of("reply", reply, "sessionId", sessionId);
    }

    /**
     * 获取对话历史。
     */
    @GetMapping("/history")
    public List<Map<String, String>> history(@RequestParam String sessionId) {
        return chatService.getHistory(sessionId);
    }

    /**
     * 清除对话历史（新建对话/退出时调用）。
     */
    @DeleteMapping("/history")
    public Map<String, String> clearHistory(@RequestParam String sessionId) {
        chatService.clearHistory(sessionId);
        return Map.of("message", "会话历史已清除", "sessionId", sessionId);
    }

    // ---- Request 内部类 ----

    public static class ChatRequest {
        /** 会话 ID，访客可传 null 由后端生成 */
        private String sessionId;
        /** 用户消息内容 */
        private String message;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { this.sessionId = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { this.message = v; }
    }
}
