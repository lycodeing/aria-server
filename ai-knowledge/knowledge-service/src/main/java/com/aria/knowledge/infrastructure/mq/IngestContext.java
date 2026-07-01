package com.aria.knowledge.infrastructure.mq;

import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.parser.ParsedDocument;
import com.aria.knowledge.infrastructure.splitter.SplitResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档摄取责任链共享上下文。
 *
 * <p>贯穿所有 {@link IngestHandler} 的数据载体，各步产出写入对应字段。
 *
 * <p>字段赋值顺序：
 * <ol>
 *   <li>event          — Pipeline 初始化，全程只读</li>
 *   <li>rawContent     — LoadContentHandler</li>
 *   <li>parsedDoc      — ParseHandler（结构化文档，含页码/章节/类型）</li>
 *   <li>splits         — SplitHandler（含元数据的切片列表）</li>
 *   <li>qualifiedSplits — QualityFilterHandler（过滤后的切片）</li>
 *   <li>chunks         — BuildChunksHandler（填充向量后由 EmbedHandler 就地更新）</li>
 * </ol>
 */
@Data
@Builder
public class IngestContext {

    /** 摄取事件（来自 RabbitMQ，全程只读） */
    private final DocIngestEvent event;

    /** MinIO 下载的原始文件字节流 */
    private byte[] rawContent;

    /** MultiFormatParser 解析后的结构化文档（含页码/章节/块类型） */
    private ParsedDocument parsedDoc;

    /** RecursiveChunkSplitter 切分后的原始切片列表（含元数据） */
    private List<SplitResult> splits;

    /** 经质量过滤后的合格切片列表 */
    private List<SplitResult> qualifiedSplits;

    /** 构建完成的 KnowledgeChunk 领域对象列表（EmbedHandler 就地填充 vector） */
    private List<KnowledgeChunk> chunks;

    /**
     * 中断标志：为 true 时 Pipeline 停止执行后续 Handler。
     */
    @Builder.Default
    private boolean aborted = false;

    /**
     * 中断责任链，后续所有 Handler 将被跳过。
     */
    public void abort() {
        this.aborted = true;
    }
}
