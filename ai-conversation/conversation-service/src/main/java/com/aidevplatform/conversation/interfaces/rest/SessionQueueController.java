package com.aidevplatform.conversation.interfaces.rest;

import com.aidevplatform.common.web.response.R;
import com.aidevplatform.conversation.application.service.SessionQueueService;
import com.aidevplatform.conversation.application.service.SessionQueueService.SessionQueueItem;
import com.aidevplatform.conversation.infrastructure.mq.SessionEventSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 座席会话队列接口。
 * 所有 REST 接口统一用 R<> 包装，与前端 defaultResponseInterceptor 对齐。
 * <p>
 * GET  /api/v1/sessions/queue         → 获取等待队列列表
 * GET  /api/v1/sessions/active        → 获取进行中的会话列表
 * POST /api/v1/sessions/{id}/accept   → 接入会话
 * POST /api/v1/sessions/{id}/close    → 结束会话
 * GET  /api/v1/sessions/events        → SSE 实时事件流（SseEmitter，Servlet 原生）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
@CrossOrigin(origins = "${app.cors.allowed-origins}")
@RequiredArgsConstructor
public class SessionQueueController {

    private static final long HEARTBEAT_INTERVAL_SEC = 20L;
    private static final long SSE_TIMEOUT_MS         = 30 * 60 * 1000L;

    private final SessionQueueService  queueService;
    /** 订阅者持有所有活跃 SSE emitter，RabbitMQ 消息到达时广播 */
    private final SessionEventSubscriber eventSubscriber;

    /**
     * SSE 心跳线程池。
     * 线程数 = max(4, CPU核数)，避免 2 个固定线程在并发座席多时因慢客户端阻塞互相饥饿。
     * 【强制】线程池不允许使用 Executors 创建，改用 ScheduledThreadPoolExecutor。
     */
    private final ScheduledExecutorService heartbeatScheduler = new ScheduledThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "sse-heartbeat-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    /** 容器关闭时优雅停止心跳线程池，防止线程泄漏 */
    @PreDestroy
    public void shutdownScheduler() {
        heartbeatScheduler.shutdown();
        log.debug("[SSE] heartbeatScheduler 已关闭");
    }

    /**
     * 查询等待队列
     */
    @GetMapping("/queue")
    public R<List<SessionQueueItem>> getQueue() {
        return R.ok(queueService.getQueue());
    }

    /**
     * 查询进行中的会话（刷新恢复用）
     */
    @GetMapping("/active")
    public R<List<SessionQueueItem>> getActive() {
        return R.ok(queueService.getActiveSessions());
    }

    /**
     * 座席接入会话
     */
    @PostMapping("/{sessionId}/accept")
    public R<SessionQueueItem> accept(@PathVariable String sessionId) {
        return R.ok(queueService.accept(sessionId));
    }

    /**
     * 结束/转交会话
     */
    @PostMapping("/{sessionId}/close")
    public R<Void> close(@PathVariable String sessionId) {
        queueService.close(sessionId);
        return R.ok();
    }

    /**
     * SSE 事件流：座席长连接订阅会话队列变更事件。
     *
     * <p>实现变更（Redis Pub/Sub → RabbitMQ Fanout）：
     * <ul>
     *   <li>旧：每个 SSE 连接向 {@code RedisMessageListenerContainer} 动态注册
     *       {@code MessageListener}，由 Redis {@code agent:session:events} 频道推送</li>
     *   <li>新：emitter 注册到 {@link SessionEventSubscriber}，
     *       {@code @RabbitListener} 消费 {@code cs.conversation.events.sse} 队列后广播</li>
     * </ul>
     *
     * <p>断连清理：通过 onCompletion/onTimeout/onError 回调从 subscriber 移除，
     * 避免向已断开的连接发送数据。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 注册到 MQ 订阅者，后续消息到达时由 subscriber 广播
        eventSubscriber.register(emitter);

        // 发送初始连接确认，防止前端 EventSource 误判断连
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            eventSubscriber.remove(emitter);
            emitter.completeWithError(e);
            return emitter;
        }

        // 心跳：每 20s 发送一次注释帧，维持连接
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            heartbeat.cancel(true);
            eventSubscriber.remove(emitter);
            log.debug("[SSE] 座席断连，emitter 已清理");
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }
}
