package com.aria.knowledge.infrastructure.splitter;

import com.aria.knowledge.domain.model.ChunkType;
import lombok.Builder;
import lombok.Data;

/**
 * 单个切片结果，携带文本内容和来源元数据。
 * 由 RecursiveChunkSplitter.split(ParsedDocument) 输出，
 * DocumentIngestPipeline 用于构建 KnowledgeChunk。
 */
@Data
@Builder
public class SplitResult {
    /** 切片文本内容 */
    private String    content;
    /** 来源页码（1-based），非 PDF 文档为 null */
    private Integer   pageNum;
    /** 所属章节标题，可 null */
    private String    sectionTitle;
    /** 内容类型（TEXT / TABLE / IMAGE_CAPTION） */
    private ChunkType chunkType;
}
