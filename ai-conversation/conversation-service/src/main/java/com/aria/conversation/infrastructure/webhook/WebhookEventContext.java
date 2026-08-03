package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 通用 Webhook 事件上下文（替代 SlaBreachContext）。
 * 携带触发事件的范围、细化类型与业务 payload，供各 WebhookSender 渲染消息。
 *
 * <p>并发注意：同一实例可能被 {@link WebhookDispatcher} 并发传给多个 @Async 分发任务，
 * Sender 必须只读本对象（当前字段仅读），不要在其内部修改 ctx。
 */
@Builder
@Data
public class WebhookEventContext {

    /** 触发的事件范围（必须） */
    private WebhookScope scope;

    /** 细化事件类型：SLA 的 WAIT/FRT/HANDLE；会话的 ENQUEUE/TRANSFER/CLOSED/CREATED；评价的 RATED */
    private String eventType;

    /** 会话 ID（可为空，如系统级事件） */
    private String sessionId;

    /** 访客名称（可为空） */
    private String visitorName;

    /** 事件专属业务字段（SLA 违规明细 / 会话状态 / 评分等） */
    private Map<String, Object> payload;

    /**
     * 推送成功后的回调（可选）。由事件发布方注入（如 SLA 违规回写 webhook_notified_at），
     * WebhookDispatcher 发送成功后统一调用；失败不调用。
     * 使 dispatcher 保持通用，无需按 scope 特判。
     */
    private Runnable onSuccess;
}
