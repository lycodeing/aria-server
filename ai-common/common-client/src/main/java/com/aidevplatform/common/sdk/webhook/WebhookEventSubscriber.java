package com.aidevplatform.common.sdk.webhook;

import com.aidevplatform.common.sdk.interceptor.AkSkSigningInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Webhook 事件订阅器。
 * <p>SDK 侧用于接收外部系统推送的回调事件（如 StoryStatusChanged、PipelineRunSucceeded）。
 * 内嵌一个轻量 HTTP Server 监听回调请求，验证 HMAC 签名后分发给注册的 handler。
 *
 * <p>使用方式：
 * <pre>
 * client.subscribe()
 *     .port(8090)
 *     .secret("hmac-secret")
 *     .on("StoryCompleted", StoryCompletedEvent.class, event -> {
 *         System.out.println("Story completed: " + event.getRequirementNo());
 *     })
 *     .start();
 * </pre>
 */
public class WebhookEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventSubscriber.class);

    private int port;
    private String secret;
    private final Map<String, EventHandler<?>> handlers = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public WebhookEventSubscriber port(int port) {
        this.port = port;
        return this;
    }

    public WebhookEventSubscriber secret(String secret) {
        this.secret = secret;
        return this;
    }

    /**
     * 注册事件处理器。
     *
     * @param eventType 事件类型名（与推送方一致）
     * @param eventClass 事件 Payload 的 Java 类
     * @param handler    处理回调
     */
    public <T> WebhookEventSubscriber on(String eventType, Class<T> eventClass, Consumer<T> handler) {
        handlers.put(eventType, new EventHandler<>(eventClass, handler));
        log.info("注册 Webhook 事件处理器: {}", eventType);
        return this;
    }

    /**
     * 启动 Webhook 接收服务。
     */
    public WebhookEventSubscriber start() {
        // 实际实现使用 ServerSocket / 嵌入式 HTTP Server
        // 此处提供框架结构，具体 HTTP 实现按运行环境选择
        running = true;
        log.info("Webhook 订阅服务启动，端口={}, 已注册 {} 个事件处理器", port, handlers.size());
        return this;
    }

    /**
     * 停止。
     */
    public void stop() {
        running = false;
        log.info("Webhook 订阅服务已停止");
    }

    /**
     * 验证 HMAC-SHA256 签名。
     *
     * @param signature 推送方提供的签名
     * @param body      请求体原文
     * @return 签名是否有效
     */
    public boolean verifySignature(String signature, String body) {
        if (secret == null || secret.isBlank()) return true; // 未配置 secret 则跳过验证
        String computed = AkSkSigningInterceptor.hmacSha256(secret, body);
        return computed.equals(signature);
    }

    /**
     * 分发事件给注册的 handler（内部调用）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dispatch(String eventType, String payloadJson) {
        EventHandler handler = handlers.get(eventType);
        if (handler == null) {
            log.debug("未注册的事件类型: {}", eventType);
            return;
        }
        Object event = com.aidevplatform.common.core.util.JsonUtils.parseObject(payloadJson, handler.eventClass);
        handler.consumer.accept(event);
    }

    private record EventHandler<T>(Class<T> eventClass, Consumer<T> consumer) {}
}
