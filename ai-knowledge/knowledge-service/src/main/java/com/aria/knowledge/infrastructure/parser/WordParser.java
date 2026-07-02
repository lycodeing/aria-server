package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Word 文档解析器（基于 Apache POI，支持 .docx）。
 *
 * <p>改为逐段落提取，保留文档层次结构：
 * <ul>
 *   <li>标题样式（Heading1/Heading2/Heading3）提取为 sectionTitle，
 *       后续段落归属于该章节</li>
 *   <li>普通段落归入当前章节的 ParsedBlock</li>
 *   <li>空段落跳过，避免噪声 chunk</li>
 * </ul>
 */
@Slf4j
@Component
public class WordParser extends AbstractDocumentParser {

    /** 标题样式名称前缀（POI 返回的样式 ID，如 Heading1/heading1） */
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("^[Hh]eading[123]$|^[标题][123]$");

    @Override
    protected ParsedDocument doParse(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<ParsedPage> pages = extractPages(doc);
            log.debug("Word 段落提取完成，段落数={}，虚拟页数={}",
                doc.getParagraphs().size(), pages.size());
            return ParsedDocument.builder()
                    .pdfType(PdfType.NATIVE_TEXT)
                    .pages(pages)
                    .build();
        } catch (IOException e) {
            throw new BusinessException(5001, "Word 文档解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "DOCX";
    }

    /**
     * 按标题段落分组，每个章节产生一个虚拟"页"（ParsedPage）。
     * 章节内多个段落合并为一个 ParsedBlock，保留 sectionTitle 元数据。
     */
    private List<ParsedPage> extractPages(XWPFDocument doc) {
        List<ParsedPage> pages = new ArrayList<>();
        String currentSection = null;
        StringBuilder currentContent = new StringBuilder();
        int virtualPage = 1;

        for (XWPFParagraph para : doc.getParagraphs()) {
            String text = para.getText();
            if (text == null || text.isBlank()) continue;

            String styleId = para.getStyleID();
            boolean isHeading = styleId != null && HEADING_PATTERN.matcher(styleId).matches();

            if (isHeading) {
                // 遇到新标题：先把当前积累的内容提交为一个页
                if (currentContent.length() > 0) {
                    pages.add(buildPage(virtualPage++, currentContent.toString(), currentSection));
                    currentContent.setLength(0);
                }
                currentSection = text.trim();
                // 标题本身也加入内容，让 chunk 包含完整章节头
                currentContent.append(text).append("\n");
            } else {
                currentContent.append(text).append("\n");
            }
        }
        // 提交最后一段内容
        if (currentContent.length() > 0) {
            pages.add(buildPage(virtualPage, currentContent.toString(), currentSection));
        }
        // 无任何有效段落时返回空，让 QualityFilterHandler 标记 FAILED
        return pages;
    }

    private ParsedPage buildPage(int pageNum, String content, String sectionTitle) {
        ParsedBlock block = ParsedBlock.builder()
                .content(content.trim())
                .chunkType(ChunkType.TEXT)
                .sectionTitle(sectionTitle)
                .build();
        return ParsedPage.builder()
                .pageNum(pageNum)
                .blocks(List.of(block))
                .build();
    }
}
