package com.aria.knowledge.infrastructure.parser;

/**
 * 文档解析器接口（破坏性升级：返回类型从 String 改为 ParsedDocument）。
 * 职责：将原始字节流解析为结构化文档，保留页码、章节、块类型等元数据。
 * 每种格式对应一个实现类，通过 MultiFormatParser 按 fileType 分发。
 */
public interface DocumentParser {

    /**
     * 将原始文件字节流解析为结构化文档。
     *
     * @param content 原始文件字节数组
     * @return 含页码、章节、块类型的结构化文档对象
     * @throws com.aria.common.core.exception.BusinessException 解析失败时抛出
     */
    ParsedDocument parse(byte[] content);

    /**
     * 本实现支持的文件类型标识，对应 knowledge_doc.file_type 字段。
     * 如：MARKDOWN / PDF / HTML / DOCX / TICKET
     */
    String supportedType();
}
