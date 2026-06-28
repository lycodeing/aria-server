package com.aidevplatform.knowledge.infrastructure.mq;

import com.aidevplatform.knowledge.application.service.IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档摄取消费者（@Scheduled 调度，遵循阿里规范：方法体不超过 5 行）。
 * 业务逻辑全部委托 IngestService，此类仅负责触发调度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestWorker {

    private final IngestService ingestService;

    @Value("${knowledge.ingest.stream-key}") private String streamKey;
    @Value("${knowledge.ingest.group-name}")  private String group;
    @Value("${knowledge.ingest.dlq-key}")     private String dlqKey;
    @Value("${knowledge.ingest.batch-size:10}") private int batchSize;
    @Value("${knowledge.ingest.max-retries:3}") private int maxRetries;

    /**
     * 每 500ms 拉取一批消息处理。
     * 先处理 PEL pending 消息（失败重试），再拉取新消息。
     */
    @Scheduled(fixedDelay = 500)
    public void consume() {
        ingestService.consumeBatch(streamKey, group, batchSize, maxRetries, dlqKey);
    }
}
