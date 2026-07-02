package com.aria.conversation.infrastructure.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话事件 RabbitMQ 订阅者 — SSE 广播中枢。
 *
 * <p>职责：监听 {@code cs.conversation.events} Fanout Exchange，
 * 将收到的事件广播给所有活跃的座席 SSE 连接。
 *
 * <p>架构说明（替换原 Redis Pub/Sub）：
 * <pre>
 *   SessionQueueService.publishEvent()
 *     ↓  eventsRabbitTemplate.convertAndSend(eventsExchange, "", event)
 *   cs.conversation.events（fanout Exchange）
 *     ↓  每个 Pod 绑定一个 exclusive + autoDelete 的匿名队列
 *   @RabbitListener（每个 Pod 独占一个副本）
 *     ↓  遍历 emitters
 *   SseEmitter × N（每个座席 SSE 连接各一个）
 * </pre>
 *
 * <p>多 Pod 说明：每个 Pod 启动时 Spring AMQP 自动创建一个 server-named、
 * exclusive、autoDelete 的队列并绑定到 Fanout Exchange。Fanout Exchange
 * 向每个绑定队列投递一份副本，保证所有 Pod 上的 SSE 连接都能收到事件。
 * Pod 停止时队列自动删除，不会积压离线期间的历史事件。
 *
 * <p>并发安全：emitters 使用 {@link CopyOnWriteArrayList}，
 * 读多写少（广播 vs 注册/注销），无需显式加锁。
 *
 * <p>僵尸 emitter 清理：发送失败时移出列表，防止无限增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventSubscriber {

    /** 当前活跃的 SSE 连接列表，线程安全 */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 注册新的 SSE emitter（座席建立 SSE 连接时调用）。
     *
     * @param emitter 待注册的 SseEmitter
     */
    public void register(SseEmitter emitter) {
        emitters.add(emitter);
        log.debug("[SSE] emitter 注册，当前连接数={}", emitters.size());
    }

    /**
     * 移除已断开的 SSE emitter（连接超时/完成/错误时调用）。
     *
     * @param emitter 待移除的 SseEmitter
     */
    public void remove(SseEmitter emitter) {
        emitters.remove(emitter);
        log.debug("[SSE] emitter 移除，当前连接数={}", emitters.size());
    }

    /**
     * 全局心跳广播：由 {@link com.aria.conversation.interfaces.rest.SessionQueueController}
     * 的单个全局定时任务调用，向所有活跃 SSE 连接发送 comment 心跳，
     * 替代原来每个连接独立 ScheduledFuture 的方案，降低线程池压力。
     */
    public void broadcastHeartbeat() {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                dead.add(emitter);
                try { emitter.completeWithError(e); } catch (Exception ignore) {}
            } catch (RuntimeException e) {
                dead.add(emitter);
                try { emitter.completeWithError(e); } catch (Exception ignore) {}
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            log.debug("[SSE] 心跳清理僵尸 emitter {}个，剩余={}", dead.size(), emitters.size());
        }
    }

    /**
     * 消费 Fanout 队列中的会话事件并广播给所有 SSE 连接。
     *
     * <p>使用 {@code exclusive="true", autoDelete="true"} 匿名队列，确保：
     * <ul>
     *   <li>每个 Pod 独立接收全量事件（真正的 fanout，而非 round-robin）</li>
     *   <li>Pod 停止时队列自动删除，不积压离线期间的历史事件</li>
     * </ul>
     *
     * <p>直接使用 {@link Message} 原始参数，避免 Jackson 反序列化 + 重新序列化的往返开销，
     * 保持 SSE payload 与 MQ 消息体完全一致。
     *
     * @param message AMQP 原始消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value    = @Queue(exclusive = "true", autoDelete = "true"),   // server-named，每 Pod 独占一个副本
            exchange = @Exchange(value = "${conversation.events.exchange}",
                                 type  = "fanout", durable = "true")
    ))
    public void onSessionEvent(Message message) {
        if (emitters.isEmpty()) {
            return;
        }

        // 直接读取原始 JSON 字节，无需二次序列化
        String json = new String(message.getBody(), StandardCharsets.UTF_8);

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException | IllegalStateException e) {
                // IOException：底层 socket 已关闭；IllegalStateException：emitter 已 complete/error
                dead.add(emitter);
                try { emitter.completeWithError(e); } catch (Exception ignore) { /* 已 complete */ }
            } catch (RuntimeException e) {
                // Spring 6 在 servlet 输出失败时会抛 AsyncRequestNotUsableException（RuntimeException），
                // 该异常非业务错误，仅表示客户端断开，必须吞掉避免冒泡到 GlobalExceptionHandler 污染日志
                dead.add(emitter);
                try { emitter.completeWithError(e); } catch (Exception ignore) { /* 已 complete */ }
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            log.debug("[SSE] 清理僵尸 emitter {}个，剩余={}", dead.size(), emitters.size());
        }
        log.debug("[SSE] 事件广播完成，活跃连接={}", emitters.size());
    }
}
