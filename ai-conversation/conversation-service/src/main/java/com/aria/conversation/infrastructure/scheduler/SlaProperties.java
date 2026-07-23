package com.aria.conversation.infrastructure.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SLA 扫描调度器配置属性。
 *
 * <p>通过 {@code application.yml} 中的 {@code sla.*} 前缀进行配置：
 * <pre>
 * sla:
 *   shard-count: 4
 *   scan-interval-ms: 30000
 *   initial-delay-ms: 10000
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sla")
public class SlaProperties {

    /** 分片总数，建议 >= Pod 数，默认 4 */
    private int shardCount = 4;

    /** 两次扫描间隔（毫秒），fixedDelay 语义：上次执行完成后计时，默认 30s */
    private long scanIntervalMs = 30_000;

    /** 服务启动后首次触发延迟（毫秒），等待其他 Bean 初始化完毕，默认 10s */
    private long initialDelayMs = 10_000;
}
