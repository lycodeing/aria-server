package com.aidevplatform.knowledge.infrastructure.mq;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.Map;

/**
 * 文档摄取事件（Redis Streams 消息的业务封装）。
 * 上传 API 发布此消息到 Redis Streams，IngestWorker 消费并触发摄取管道。
 */
@Data
@Builder
public class DocIngestEvent {

    private String docId;
    private String kbId;
    /** 文件类型：MARKDOWN / PDF / HTML / DOCX / TICKET */
    private String fileType;
    /** 文件存储路径（OSS/MinIO），格式：oss://bucket/path/file.pdf */
    private String storagePath;

    /** 转换为 Redis Streams 消息 payload */
    public Map<String, String> toPayload() {
        return Map.of(
            "docId",       docId,
            "kbId",        kbId,
            "fileType",    fileType,
            "storagePath", storagePath
        );
    }

    /** 从 Redis Streams 消息 payload 反序列化 */
    @SuppressWarnings("unchecked")
    public static DocIngestEvent fromRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        return DocIngestEvent.builder()
            .docId(String.valueOf(body.get("docId")))
            .kbId(String.valueOf(body.get("kbId")))
            .fileType(String.valueOf(body.get("fileType")))
            .storagePath(String.valueOf(body.get("storagePath")))
            .build();
    }
}
