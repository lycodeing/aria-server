package com.aria.knowledge.infrastructure.parser;

/** Chunk 内容类型枚举，用于检索策略差异化处理和引用展示。 */
public enum ChunkType {
    /** 普通正文文本 */
    TEXT,
    /** 表格内容（已转为「字段: 值」结构化文本） */
    TABLE,
    /** 图片说明文字（caption）或未来多模态摘要（预留） */
    IMAGE_CAPTION
}
