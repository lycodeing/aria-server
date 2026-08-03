package com.aria.conversation.application.service.cancellation;

import com.aria.conversation.infrastructure.cancellation.CancelFlagStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取消信号注册表（应用编排层）。
 *
 * <p>编排两类取消信号：
 * <ul>
 *   <li>Reactor 信号 — {@link Sinks.One} 供 Flux {@code takeUntilOther} 截断 LLM 流</li>
 *   <li>持久标志 — 委托 {@link CancelFlagStore} 供 blocking 工具线程轮询</li>
 * </ul>
 *
 * <p><b>SRP 拆分</b>：Reactor Sink 管理（本类）与 Redis 标志位（{@link CancelFlagStore}）分离，
 * 分别服务于 Flux 链和 blocking 工具线程，变更原因不同（Reactor 生命周期 vs 多实例复制）。
 *
 * <p><b>轮次隔离（I1 修复）</b>：内部以 {@code turnId}（每次 {@link #register} 生成的 UUID）
 * 而非 {@code sessionId} 作为 Sink/标志位的 key。原因：若同一 sessionId 连续触发两次流式请求
 * （如"取消后立即重发消息"），旧实现下第二次 {@code register(sessionId)} 会用同一 key 覆盖
 * 第一轮的 Sink，导致 {@code cancel(sessionId)} 误取消到无关的新一轮请求，或反之取消信号
 * 打不到真正想取消的那一轮。引入 turnId 后，每轮请求拥有独立 key，互不覆盖；
 * {@code sessionId → turnId} 映射只记录"当前最新一轮"，供 {@link #cancel} 按 sessionId 查找。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancellationRegistry {

    private final CancelFlagStore cancelFlagStore;

    /** turnId → 取消触发器 Sink（应用运行时状态，每轮请求独立 key） */
    private final ConcurrentHashMap<String, Sinks.One<Void>> activeSinks = new ConcurrentHashMap<>();
    /** sessionId → 当前最新一轮 turnId，供 {@link #cancel(String)} 按 sessionId 定位目标轮次 */
    private final ConcurrentHashMap<String, String> sessionToTurn = new ConcurrentHashMap<>();

    /**
     * Agent 流启动时注册，生成本轮独立的 turnId 并返回取消句柄。
     *
     * <p>更新 {@code sessionToTurn} 使 {@link #cancel(String)} 后续能定位到本轮；
     * 不清理旧 turnId 的 Sink/标志（旧 key 与新 key 不同，天然不会互相覆盖，
     * 旧一轮结束时由其自身的 {@link #unregister} 清理）。
     *
     * @param sessionId 会话 ID
     * @return 取消句柄，包含本轮 turnId 和取消触发器
     */
    public CancelHandle register(String sessionId) {
        String turnId = UUID.randomUUID().toString();
        Sinks.One<Void> sink = Sinks.one();
        activeSinks.put(turnId, sink);
        sessionToTurn.put(sessionId, turnId);
        cancelFlagStore.clear(turnId);
        return new CancelHandle(turnId, sink);
    }

    /**
     * 触发取消：按 sessionId 找到当前最新一轮 turnId，取消该轮。
     * 先设标志位（工具线程立即感知），再触发 Reactor 信号（I10 修复顺序）。
     * 检查 {@code tryEmitEmpty} 返回值（I9 修复），失败时记录 WARN（流可能已自然结束）。
     *
     * @param sessionId 会话 ID
     */
    public void cancel(String sessionId) {
        String turnId = sessionToTurn.get(sessionId);
        if (turnId == null) {
            log.info("[Cancel] 无活跃轮次，忽略 sessionId={}", sessionId);
            return;
        }
        // ① 先设标志位（blocking 工具线程立即感知，缩小 TOCTOU 窗口）
        cancelFlagStore.markCancelled(turnId);
        // ② 再触发 Reactor cancel 信号
        Sinks.One<Void> sink = activeSinks.remove(turnId);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitEmpty();
            if (result.isFailure()) {
                log.warn("[Cancel] Sink 发射失败 sessionId={} turnId={} result={}（流可能已结束）",
                        sessionId, turnId, result);
            }
        }
    }

    /**
     * 流自然结束时清理注册，避免内存泄漏。
     *
     * <p>仅当 {@code sessionToTurn} 中记录的仍是本轮 turnId 时才移除该映射，
     * 避免清理掉期间已被新一轮覆盖的映射（新一轮不应被旧一轮的收尾逻辑影响）。
     *
     * @param sessionId 会话 ID
     * @param turnId    本轮 turnId（{@link #register} 返回的 {@link CancelHandle#turnId()}）
     */
    public void unregister(String sessionId, String turnId) {
        activeSinks.remove(turnId);
        cancelFlagStore.clear(turnId);
        sessionToTurn.remove(sessionId, turnId);
    }

    /**
     * 检查指定轮次是否已取消（委托 {@link CancelFlagStore}）。
     * 供 blocking 工具线程在执行前轮询。
     *
     * @param turnId 本轮 turnId
     * @return true 表示已取消
     */
    public boolean isCancelled(String turnId) {
        return cancelFlagStore.isCancelled(turnId);
    }

    /**
     * 取消句柄：封装本轮 turnId 和 Reactor 取消触发器。
     *
     * @param turnId  本轮唯一标识，用于 {@link #isCancelled} / {@link #unregister} 及工具层取消检查
     * @param trigger 取消触发器，{@code trigger.asMono()} 传入 {@code takeUntilOther}
     */
    public record CancelHandle(String turnId, Sinks.One<Void> trigger) {}
}
