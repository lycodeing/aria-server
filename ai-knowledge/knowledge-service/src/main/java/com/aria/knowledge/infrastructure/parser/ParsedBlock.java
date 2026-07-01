package com.aria.knowledge.infrastructure.parser;

import lombok.Builder;
import lombok.Data;

/**
 * 最小解析单元：一段文本块，含类型与所属章节信息。
 * 对应 PDF 中的一个段落、一张表格或一段图注。
 */
@Data
@Builder
public class ParsedBlock {
    /** 块文本内容 */
    private String    content;
    /** 块类型（TEXT / TABLE / IMAGE_CAPTION） */
    private ChunkType chunkType;
    /** 所属章节标题，null 表示无法检测到章节边界 */
    private String    sectionTitle;
}
