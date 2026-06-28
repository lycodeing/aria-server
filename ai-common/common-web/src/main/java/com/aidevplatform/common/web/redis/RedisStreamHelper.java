package com.aidevplatform.common.web.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Redis Streams 流操作封装。
 * 统一封装消息发布、消费、XCLAIM 重试、DLQ 转移等操作。
 *
 * <p>Redis Streams 核心概念：
 * <ul>
 *   <li>Stream：有序消息日志，消息写入后不可修改</li>
 *   <li>Consumer Group：消费者组，多个消费者协作消费</li>
 *   <li>PEL（Pending Entry List）：已投递但未 ACK 的消息列表</li>
 *   <li>XCLAIM：将 PEL 中空闲超时的消息重新分配给指定消费者（故障转移和重试）</li>
 *   <li>delivery-count：Redis 内置的投递次数计数，通过 XPENDING 可读</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamHelper {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 向 Stream 发布消息。
     *
     * @param streamKey Stream key
     * @param payload   消息体（key-value 均为字符串）
     * @return 消息 ID（RecordId）
     */
    public RecordId publish(String streamKey, Map<String, String> payload) {
        // Spring Data Redis 3.x：直接传 streamKey + payload Map
        RecordId id = redisTemplate.opsForStream().add(streamKey, payload);
        log.debug("消息已发布，streamKey={}，recordId={}", streamKey, id);
        return id;
    }

    /**
     * 拉取新消息（仅返回首次投递的消息，对应 > 游标）。
     * 失败消息不会在此方法中返回，需通过 {@link #claimIdleMessages} 处理。
     *
     * @param streamKey  Stream key
     * @param group      消费者组名
     * @param consumerId 消费者 ID（建议格式：hostname-pid）
     * @param count      单次最多拉取条数
     * @param blockMs    无新消息时阻塞等待毫秒数，0=不阻塞
     * @return 新消息列表，无新消息返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<MapRecord<String, Object, Object>> readNew(
            String streamKey, String group, String consumerId, int count, long blockMs) {

        // Spring Data Redis 3.x：StreamReadOptions 在 connection.stream 包
        StreamReadOptions options = blockMs > 0
            ? StreamReadOptions.empty().count(count).block(Duration.ofMillis(blockMs))
            : StreamReadOptions.empty().count(count);

        List<?> records = redisTemplate.opsForStream().read(
            Consumer.from(group, consumerId),
            options,
            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );
        if (records == null) return Collections.emptyList();
        return (List<MapRecord<String, Object, Object>>) records;
    }

    /**
     * ACK 确认消息已处理，将其从 PEL 中移除。
     *
     * @param streamKey Stream key
     * @param group     消费者组名
     * @param recordId  消息 ID
     */
    public void acknowledge(String streamKey, String group, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
    }

    /**
     * 查询组级别 PEL 中空闲超过指定时间的消息并重新认领（XCLAIM）。
     * 查询的是消费者【组】全局 PEL，而非当前消费者自己的 PEL，
     * 这样才能接管其他宕机 Worker 遗留的消息。
     *
     * @param streamKey  Stream key
     * @param group      消费者组名
     * @param consumerId 认领者消费者 ID
     * @param idleTime   空闲时间阈值，超过此时间才认领
     * @param maxFetch   单次最多认领条数
     * @return 认领到的消息与其 pending 元信息的映射
     */
    @SuppressWarnings("unchecked")
    public Map<MapRecord<String, Object, Object>, PendingMessage> claimIdleMessages(
            String streamKey, String group, String consumerId,
            Duration idleTime, int maxFetch) {

        // 查询消费者组全局 PEL（Spring Data Redis 3.x API）
        PendingMessages pendingMessages = redisTemplate.opsForStream()
            .pending(streamKey, group, Range.unbounded(), maxFetch);

        if (pendingMessages == null || !pendingMessages.iterator().hasNext()) {
            return Collections.emptyMap();
        }

        Map<MapRecord<String, Object, Object>, PendingMessage> result = new LinkedHashMap<>();

        for (PendingMessage pending : pendingMessages) {
            // 未到空闲阈值，跳过
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(idleTime) < 0) {
                continue;
            }
            // Spring Data Redis 3.x：claim(key, group, consumer, minIdleTime, recordIds)
            // 注意：3.x 的 claim 签名是 (String key, String group, String newOwner, Duration, RecordId...)
            List<?> claimed = redisTemplate.opsForStream()
                .claim(streamKey, group, consumerId, idleTime, pending.getId());

            if (claimed != null && !claimed.isEmpty()) {
                result.put((MapRecord<String, Object, Object>) claimed.get(0), pending);
            }
        }
        return result;
    }

    /**
     * 将消息内容推入死信队列（Redis List，右侧追加）。
     *
     * @param dlqKey  死信队列 key（建议格式：{streamKey}:dlq）
     * @param payload 消息内容（JSON 字符串）
     */
    public void pushToDlq(String dlqKey, String payload) {
        redisTemplate.opsForList().rightPush(dlqKey, payload);
        log.warn("消息已转入死信队列，dlqKey={}，payload 长度={}", dlqKey, payload.length());
    }

    /**
     * 确保消费者组存在，不存在时自动创建（从流的最新位置开始消费）。
     *
     * @param streamKey Stream key
     * @param group     消费者组名
     */
    public void ensureGroupExists(String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, group);
            log.info("消费者组已创建，streamKey={}，group={}", streamKey, group);
        } catch (Exception e) {
            // 组已存在时忽略异常
            log.debug("消费者组已存在，streamKey={}，group={}", streamKey, group);
        }
    }
}
