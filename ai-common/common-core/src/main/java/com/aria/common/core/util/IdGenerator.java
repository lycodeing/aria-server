package com.aria.common.core.util;

/**
 * 分布式 ID 生成器（Snowflake 简化版）。
 * <p>格式：时间戳(ms) 41位 + workerId 10位 + sequence 12位 = 63位 Long。
 * <p>部署时通过环境变量 WORKER_ID（0-1023）区分不同实例。
 *
 * <p>实现说明：
 * <ul>
 *   <li>方法以 {@code synchronized} 保证单线程串行，不再使用 AtomicLong（二者混用逻辑矛盾）</li>
 *   <li>新毫秒时重置 sequence 为 0，避免上一毫秒末尾值浪费本毫秒序列空间</li>
 *   <li>时钟回拨时忙等到追上，保证单调递增</li>
 * </ul>
 */
public final class IdGenerator {

    private static final long EPOCH          = 1704067200000L; // 2024-01-01 00:00:00 UTC
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS  = 12L;
    private static final long MAX_WORKER_ID  = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK  = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT   = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT   = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 可容忍的最大时钟回拨（毫秒）；超过则抛异常而非无限忙等 */
    private static final long MAX_BACKWARD_MS = 5000L;

    private static final long WORKER_ID = initWorkerId();

    /** 普通 long 字段，由 synchronized 保证线程安全，无需 AtomicLong */
    private static long lastTimestamp = 0L;
    private static long sequence      = 0L;

    private IdGenerator() {}

    /**
     * 生成下一个全局唯一 ID。
     * synchronized 保证单线程串行，每毫秒最多生成 4096 个 ID。
     */
    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp > lastTimestamp) {
            // 新毫秒：重置序列计数器，从 0 开始充分利用本毫秒的序列空间
            sequence = 0L;
        } else if (timestamp == lastTimestamp) {
            // 同一毫秒：序列递增
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 序列溢出（本毫秒已生成 4096 个 ID），忙等到下一毫秒
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
                sequence = 0L;
            }
        } else {
            // 时钟回拨：小幅回拨（≤ MAX_BACKWARD_MS）容忍，忙等到追上；
            // 超过阈值直接抛异常，避免无限忙等挂死整个 ID 生成（进而阻塞所有写入）。
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "时钟回拨过大，拒绝生成 ID：回拨 " + offset + "ms（阈值 " + MAX_BACKWARD_MS + "ms）");
            }
            while (timestamp <= lastTimestamp) {
                timestamp = System.currentTimeMillis();
            }
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | sequence;
    }

    private static long initWorkerId() {
        // 优先环境变量 WORKER_ID，其次系统属性 worker.id，显式配置才可保证多实例不冲突
        String configured = System.getenv("WORKER_ID");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("worker.id");
        }
        if (configured != null && !configured.isBlank()) {
            try {
                long id = Long.parseLong(configured.trim());
                if (id >= 0 && id <= MAX_WORKER_ID) return id;
                System.err.println("[IdGenerator] WORKER_ID 超出范围 [0," + MAX_WORKER_ID
                        + "]，回退 PID 取模，多实例存在 ID 冲突风险: " + configured);
            } catch (NumberFormatException e) {
                System.err.println("[IdGenerator] WORKER_ID 非法，回退 PID 取模，多实例存在 ID 冲突风险: " + configured);
            }
        } else {
            // 未显式配置：PID 取模仅为单机兜底，多实例/容器化部署 PID 高度趋同，
            // 存在 workerId 碰撞进而 ID 重复的风险，务必在部署时通过 WORKER_ID 显式分配。
            System.err.println("[IdGenerator] 未配置 WORKER_ID，回退 PID 取模；多实例部署请务必显式设置 WORKER_ID 以避免 ID 冲突");
        }
        long pid = ProcessHandle.current().pid();
        return pid % (MAX_WORKER_ID + 1);
    }
}
