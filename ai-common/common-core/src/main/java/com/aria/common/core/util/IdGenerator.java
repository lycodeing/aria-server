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
            // 时钟回拨：忙等到追上，保证 ID 单调递增
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
        String env = System.getenv("WORKER_ID");
        if (env != null) {
            try {
                long id = Long.parseLong(env);
                if (id >= 0 && id <= MAX_WORKER_ID) return id;
            } catch (NumberFormatException ignored) {}
        }
        // 默认用 PID 取模
        long pid = ProcessHandle.current().pid();
        return pid % (MAX_WORKER_ID + 1);
    }
}
