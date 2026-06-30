package com.aidevplatform.knowledge.infrastructure.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档摄取事件 DTO（RabbitMQ 消息体）。
 *
 * <p>上传 API 发布此消息到 {@code knowledge.doc.ingest} Exchange，
 * {@code DocIngestConsumer} 消费并触发摄取管道。
 *
 * <p>Jackson 反序列化需要无参构造器 + setter，因此保留：
 * {@code @Data + @NoArgsConstructor + @AllArgsConstructor + @Builder}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocIngestEvent {

    /** 文档 ID（与 knowledge_doc.id 对应） */
    private String docId;

    /** 知识库 ID */
    private String kbId;

    /** 文件类型：MARKDOWN / PDF / HTML / DOCX / TICKET */
    private String fileType;

    /** 文件存储路径，MinIO 格式：oss://bucket/docs/{docId}/{filename} */
    private String storagePath;
}
