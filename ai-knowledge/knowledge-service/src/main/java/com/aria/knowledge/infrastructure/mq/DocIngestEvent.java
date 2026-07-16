package com.aria.knowledge.infrastructure.mq;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文档摄取事件 DTO（RabbitMQ 消息体）。
 *
 * <p>上传 API 发布此消息到 {@code knowledge.doc.ingest} Exchange，
 * {@code DocIngestConsumer} 消费并触发摄取管道。
 *
 * <p>Jackson 反序列化需要无参构造器 + setter，因此保留：
 * {@code @Data + @NoArgsConstructor + @AllArgsConstructor + @Builder}。
 *
 * <p>消费端通过 {@code @Valid} 触发字段非空校验，反序列化得到的脏数据直接拒绝。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocIngestEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 ID（与 knowledge_doc.id 对应） */
    @NotBlank(message = "docId 不能为空")
    private String docId;

    /** 知识库 ID */
    @NotBlank(message = "kbId 不能为空")
    private String kbId;

    /** 文件类型：MARKDOWN / PDF / HTML / DOCX / TICKET */
    @NotBlank(message = "fileType 不能为空")
    private String fileType;

    /** 文件存储路径，MinIO 格式：oss://bucket/docs/{docId}/{filename} */
    @NotBlank(message = "storagePath 不能为空")
    private String storagePath;

    /**
     * 强制重新摄取标记。
     *
     * <p>为 false（默认）时：{@link com.aria.knowledge.infrastructure.mq.handler.IdempotencyCheckHandler}
     * 会对 PUBLISHED/FAILED/DEPRECATED 终态文档调用 abort，跳过摄取。
     *
     * <p>为 true 时：跳过终态校验，允许对已发布文档重新生成 chunk（解析逻辑升级场景）。
     * 仅由 {@link com.aria.knowledge.application.service.DocIngestAppService#reingest} 发布时设置。
     */
    private boolean forceReingest;

    /**
     * 全链路追踪 ID。
     *
     * <p>发布端从 MDC {@code traceId} 注入，消费端恢复到 MDC，
     * 确保摄取管道的异步处理日志与触发请求保持相同 traceId。
     * 可为 null（兼容旧消息）。
     */
    private String traceId;
}
