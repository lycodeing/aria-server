package com.aria.conversation.infrastructure.cancellation;

import com.aria.common.web.redis.RedisCacheHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取消标志位存储（基础设施层）。
 *
 * <p>双轨设计：内存 {@link ConcurrentHashMap} 优先（无网络开销），降级到 Redis（多实例兼容）。
 * 供 blocking 工具线程在执行前轮询，弥补 Reactor cancel 信号无法中断 block 线程的缺陷。
 *
 * <p>Redis 操作统一通过 {@link RedisCacheHelper} 封装，遵循项目 Redis 工具类规范。
 *
 * <ul>
 *   <li>{@link #markCancelled} — 设置取消标志（内存 + Redis）</li>
 *   <li>{@link #isCancelled} — 检查是否已取消（先查内存，再查 Redis）</li>
 *   <li>{@link #clear} — 清理标志（流结束时调用）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelFlagStore {

    private final RedisCacheHelper cache;

    private static final String KEY_PREFIX = "chat:cancel:";
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 内存标志（当前实例有效，避免每次工具执行都查 Redis） */
    private final ConcurrentHashMap<String, Boolean> localFlags = new ConcurrentHashMap<>();

    /**
     * 设置取消标志（内存 + Redis 同步写入）。
     * Redis 写入失败时仅记录 WARN，内存标志已生效，取消意图部分可用。
     *
     * @param turnId 轮次 ID（{@code CancellationRegistry.register} 生成的 turnId，
     *               I1 修复：不再用 sessionId，避免同一 session 连续请求互相覆盖标志）
     */
    public void markCancelled(String turnId) {
        localFlags.put(turnId, Boolean.TRUE);
        try {
            cache.set(KEY_PREFIX + turnId, "1", TTL);
        } catch (Exception e) {
            log.warn("[CancelFlagStore] Redis set 失败，仅本地标志生效 turnId={}", turnId, e);
        }
    }

    /**
     * 检查是否已取消。优先查内存标志（无网络开销），降级查 Redis（多实例场景）。
     * Redis 异常时 fail-open 返回 false，避免取消特性击穿核心工具链路。
     *
     * @param turnId 轮次 ID
     * @return true 表示已取消
     */
    public boolean isCancelled(String turnId) {
        if (Boolean.TRUE.equals(localFlags.get(turnId))) {
            return true;
        }
        try {
            return cache.exists(KEY_PREFIX + turnId);
        } catch (Exception e) {
            log.warn("[CancelFlagStore] Redis exists 失败，降级仅用本地标志 turnId={}", turnId, e);
            return false;
        }
    }

    /**
     * 清理取消标志（流自然结束或被取消后调用，避免内存泄漏）。
     * Redis 删除失败时仅记录 WARN，内存标志已清除。
     *
     * @param turnId 轮次 ID
     */
    public void clear(String turnId) {
        localFlags.remove(turnId);
        try {
            cache.delete(KEY_PREFIX + turnId);
        } catch (Exception e) {
            log.warn("[CancelFlagStore] Redis delete 失败 turnId={}", turnId, e);
        }
    }
}
