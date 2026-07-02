package com.aria.knowledge.infrastructure.parser;

import com.aria.knowledge.domain.model.ChunkType;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档解析结果聚合对象，由 DocumentParser 各实现返回。
 * 非 PDF 文档通过工厂方法 {@link #ofSinglePage} 包装为单页结构。
 */
@Data
@Builder
public class ParsedDocument {
    /** 文档标题（PDF outline 根节点或文件名），可 null */
    private String           title;
    /** PDF 类型（非 PDF 文档固定为 NATIVE_TEXT） */
    @Builder.Default
    private PdfType          pdfType = PdfType.NATIVE_TEXT;
    /** 按顺序排列的页列表 */
    private List<ParsedPage> pages;

    /**
     * 将全部页内容拼接为纯文本（供 RecursiveChunkSplitter 降级使用）。
     */
    public String toPlainText() {
        if (pages == null || pages.isEmpty()) return "";
        return pages.stream()
            .map(ParsedPage::toPlainText)
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * 非 PDF 解析器专用工厂方法：将纯文本包装为单页文档。
     *
     * @param text         解析后的纯文本
     * @param sectionTitle 章节标题（通常传 null）
     */
    public static ParsedDocument ofSinglePage(String text, String sectionTitle) {
        ParsedBlock block = ParsedBlock.builder()
            .content(text)
            .chunkType(ChunkType.TEXT)
            .sectionTitle(sectionTitle)
            .build();
        ParsedPage page = ParsedPage.builder()
            .pageNum(1)
            .blocks(List.of(block))
            .build();
        return ParsedDocument.builder()
            .pdfType(PdfType.NATIVE_TEXT)
            .pages(List.of(page))
            .build();
    }
}
