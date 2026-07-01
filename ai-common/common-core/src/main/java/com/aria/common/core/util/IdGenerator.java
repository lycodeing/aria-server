package com.aria.common.core.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式 ID 生成器（Snowflake 简化版）。
 * <p>格式：时间戳(ms) 41位 + workerId 10位 + sequence 12位 = 63位 Long。
 * <p>部署时通过环境变量 WORKER_ID（0-1023）区分不同实例。
 */
public final class IdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private static final long WORKER_ID = initWorkerId();
    private static final AtomicLong LAST_TIMESTAMP = new AtomicLong(0);
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private IdGenerator() {}

    /**
     * 生成下一个全局唯一 ID。
     */
    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        long lastTs = LAST_TIMESTAMP.get();
        if (timestamp == lastTs) {
            long seq = (SEQUENCE.incrementAndGet()) & SEQUENCE_MASK;
            if (seq == 0) {
                // 序列溢出，等待下一毫秒
                while (timestamp <= lastTs) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else if (timestamp < lastTs) {
            // 时钟回拨，等待到追上
            while (timestamp <= lastTs) {
                timestamp = System.currentTimeMillis();
            }
        }
        LAST_TIMESTAMP.set(timestamp);
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | (SEQUENCE.get() & SEQUENCE_MASK);
    }

    private static long initWorkerId() {
        String env = System.getenv("WORKER_ID");
        if (env != null) {
            long id = Long.parseLong(env);
            if (id >= 0 && id <= MAX_WORKER_ID) return id;
        }
        // 默认用 PID 取模
        long pid = ProcessHandle.current().pid();
        return pid % (MAX_WORKER_ID + 1);
    }
}
