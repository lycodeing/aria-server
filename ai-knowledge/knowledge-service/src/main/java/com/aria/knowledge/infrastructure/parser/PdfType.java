package com.aria.knowledge.infrastructure.parser;

/** PDF 文件类型枚举，用于 PdfParser 类型检测后的分支处理。 */
public enum PdfType {
    /** 原生文本型 PDF：含可提取文字层，直接用 PDFTextStripper */
    NATIVE_TEXT,
    /** 扫描件型 PDF：无文字层，当前版本拒绝入库，需接入 OCR 后支持 */
    SCANNED,
    /** 图文混排型 PDF：部分页含图表，文字层覆盖率偏低 */
    MIXED
}
