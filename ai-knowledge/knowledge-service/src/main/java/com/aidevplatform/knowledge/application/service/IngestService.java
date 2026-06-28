package com.aidevplatform.knowledge.application.service;

import com.aidevplatform.common.web.redis.RedisStreamHelper;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.repository.KnowledgeDocRepository;
import com.aidevplatform.knowledge.infrastructure.mq.DocIngestEvent;
import com.aidevplatform.knowledge.infrastructure.mq.DocumentIngestPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取应用服务。
 * 负责 Redis Streams 消息消费、失败重试、DLQ 转移的完整生命周期。
 * 与 IngestWorker 解耦，便于单测。
 *
 * <p>重试机制（S-1 修复）：
 * <ul>
 *   <li>Step 1：先处理 PEL 中空闲超时的 pending 消息（XCLAIM 认领组级别 PEL）</li>
 *   <li>Step 2：再拉取新消息（ReadOffset.lastConsumed() 游标）</li>
 *   <li>利用 Redis 内置 delivery-count 判断重试次数，不依赖 payload 字段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final DocumentIngestPipeline   pipeline;
    private final RedisStreamHelper        streamHelper;
    /** 通过领域层 Repository 接口更新文档状态，不直接依赖基础设施 Mapper */
    private final KnowledgeDocRepository   docRepository;

    /** PEL 消息空闲超过此时长才认领重试 */
    private static final Duration CLAIM_IDLE_THRESHOLD = Duration.ofSeconds(60);
    /** 单次最多认领 pending 消息条数 */
    private static final int      CLAIM_BATCH_SIZE     = 10;

    /**
     * 批量消费 Redis Streams 消息，优先处理 PEL pending 消息，再拉取新消息。
     *
     * @param streamKey  Stream key
     * @param group      消费者组名称
     * @param batchSize  单次拉取新消息最大条数
     * @param maxRetries 最大重试次数（超出则转 DLQ）
     * @param dlqKey     死信队列 Redis key
     */
    public void consumeBatch(String streamKey, String group,
                             int batchSize, int maxRetries, String dlqKey) {
        String workerId = resolveWorkerId();

        // Step 1：优先处理 PEL 中空闲超时的消息（失败重试核心逻辑）
        processPendingMessages(streamKey, group, workerId, maxRetries, dlqKey);

        // Step 2：拉取新消息（> 游标，仅返回首次投递的消息）
        List<MapRecord<String, Object, Object>> records =
                streamHelper.readNew(streamKey, group, workerId, batchSize, 200L);
        records.forEach(r -> processSingle(r, streamKey, group, dlqKey, maxRetries));
    }

    // -------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------

    /**
     * 处理 PEL 中空闲超时的消息：使用 XCLAIM 认领，
     * 根据 Redis 内置 delivery-count 决定重试还是转 DLQ。
     */
    private void processPendingMessages(String streamKey, String group,
                                        String workerId, int maxRetries, String dlqKey) {
        Map<MapRecord<String, Object, Object>, PendingMessage> claimedMap =
                streamHelper.claimIdleMessages(
                        streamKey, group, workerId, CLAIM_IDLE_THRESHOLD, CLAIM_BATCH_SIZE);

        if (claimedMap.isEmpty()) {
            return;
        }

        claimedMap.forEach((record, pending) -> {
            long deliveryCount = pending.getTotalDeliveryCount();
            if (deliveryCount >= maxRetries) {
                // 超过最大重试次数，转 DLQ
                streamHelper.pushToDlq(dlqKey, record.getValue().toString());
                streamHelper.acknowledge(streamKey, group, record.getId());
                markDocFailed(record, "超过最大重试次数 " + maxRetries
                        + "，投递次数=" + deliveryCount);
                log.error("文档摄取转入 DLQ，recordId={}，投递次数={}", record.getId(), deliveryCount);
            } else {
                // 未超限，重新处理
                processSingle(record, streamKey, group, dlqKey, maxRetries);
            }
        });
    }

    private void processSingle(MapRecord<String, Object, Object> record,
                               String streamKey, String group,
                               String dlqKey, int maxRetries) {
        try {
            DocIngestEvent event = DocIngestEvent.fromRecord(record);
            pipeline.process(event);
            // 处理成功，ACK 确认，消息从 PEL 移除
            streamHelper.acknowledge(streamKey, group, record.getId());
        } catch (Exception e) {
            // 不 ACK，消息留在 PEL，由下次 processPendingMessages 根据 delivery-count 决策
            log.warn("文档摄取失败，消息留在 PEL 等待重试，recordId={}", record.getId(), e);
        }
    }

    /**
     * 通过 Repository 接口将文档状态标记为 FAILED。
     * 使用领域层接口，不直接依赖基础设施 Mapper。
     */
    private void markDocFailed(MapRecord<String, Object, Object> record, String reason) {
        try {
            Object docIdObj = record.getValue().get("docId");
            if (docIdObj == null) {
                return;
            }
            String docId = docIdObj.toString();
            docRepository.updateStatusBatch(List.of(docId), DocStatus.FAILED);
            log.warn("文档状态标记为 FAILED，docId={}，原因={}", docId, reason);
        } catch (Exception e) {
            log.error("标记文档 FAILED 状态时异常", e);
        }
    }

    /**
     * 从环境变量或主机名解析 Worker 唯一标识，支持多实例水平扩展。
     */
    private String resolveWorkerId() {
        String envId = System.getenv("WORKER_ID");
        if (envId != null && !envId.isBlank()) {
            return envId;
        }
        try {
            return InetAddress.getLocalHost().getHostName()
                    + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "worker-" + System.currentTimeMillis();
        }
    }
}
