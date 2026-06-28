package com.aidevplatform.knowledge.infrastructure.parser;

/**
 * 文档解析器接口。
 * 职责：将原始字节流解析为纯文本，并进行基础清洗（去页眉页脚、去 HTML 标签等）。
 * 每种格式对应一个实现类，通过 MultiFormatParser 按 fileType 分发。
 */
public interface DocumentParser {

    /**
     * 将原始文件字节流解析为清洗后的纯文本。
     *
     * @param content  原始文件字节数组
     * @return 清洗后的纯文本，不含页眉页脚、广告、导航栏等噪声内容
     * @throws com.aidevplatform.common.core.exception.BusinessException 解析失败时抛出
     */
    String parse(byte[] content);

    /**
     * 本实现支持的文件类型标识，对应 knowledge_doc.file_type 字段。
     * 如：MARKDOWN / PDF / HTML / DOCX / TICKET
     */
    String supportedType();
}
