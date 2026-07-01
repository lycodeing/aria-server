package com.aria.knowledge.infrastructure.persistence.do_;

import lombok.Data;

/**
 * Chunk 检索查询结果数据对象（仅用于 Mapper 查询映射，不传递到 domain 层）。
 * Assembler 负责将 ChunkHitDO 转换为领域对象 ChunkHit。
 */
@Data
public class ChunkHitDO {
    private String chunkId;
    private String docId;
    private String kbId;
    private String content;
    private String breadcrumb;
    private String parentChunkId;
    /** 检索相关性分数（向量余弦相似度 或 TF-IDF 分） */
    private Double  score;
    /** 来源页码 */
    private Integer pageNum;
    /** 所属章节标题 */
    private String  sectionTitle;
    /** Chunk 类型（TEXT / TABLE / IMAGE_CAPTION） */
    private String  chunkType;
}
