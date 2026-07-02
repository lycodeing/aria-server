package com.aria.knowledge.domain.model;

/**
 * Chunk 内容类型枚举。
 * 用于区分不同内容类型的检索策略和展示方式：
 * <ul>
 *   <li>{@link #TEXT} — 普通正文文本，默认类型</li>
 *   <li>{@link #TABLE} — 表格内容（字段:值结构化文本）</li>
 *   <li>{@link #IMAGE_CAPTION} — 图片说明/多模态摘要（预留）</li>
 * </ul>
 */
public enum ChunkType {
    /** 普通正文文本 */
    TEXT,
    /** 表格内容（字段:值结构化文本） */
    TABLE,
    /** 图片说明/多模态摘要（预留） */
    IMAGE_CAPTION
}
