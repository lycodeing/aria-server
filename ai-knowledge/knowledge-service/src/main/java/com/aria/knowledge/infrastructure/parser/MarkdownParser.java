package com.aria.knowledge.infrastructure.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档解析器。
 * 按 # / ## 标题边界切为虚拟"页"，每个 section 携带 sectionTitle。
 * 标题层级由 RecursiveChunkSplitter 在切片阶段进一步利用。
 */
@Slf4j
@Component
public class MarkdownParser extends AbstractDocumentParser {

    @Override
    protected ParsedDocument doParse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<ParsedPage> pages = splitByHeading(text);
        log.debug("Markdown 解析完成，虚拟页数={}", pages.size());
        return ParsedDocument.builder()
            .pdfType(PdfType.NATIVE_TEXT)
            .pages(pages)
            .build();
    }

    @Override
    public String supportedType() {
        return "MARKDOWN";
    }

    /** 按 # / ## 标题边界切分为虚拟页列表 */
    private List<ParsedPage> splitByHeading(String text) {
        List<ParsedPage> pages = new ArrayList<>();
        String[] sections = text.split("(?=\n#{1,2} )");
        int virtualPage = 1;
        for (String section : sections) {
            if (section.isBlank()) continue;
            String title = extractTitle(section);
            ParsedBlock block = ParsedBlock.builder()
                .content(section.trim())
                .chunkType(ChunkType.TEXT)
                .sectionTitle(title)
                .build();
            pages.add(ParsedPage.builder()
                .pageNum(virtualPage++)
                .blocks(List.of(block))
                .build());
        }
        if (pages.isEmpty()) {
            pages.add(ParsedPage.builder()
                .pageNum(1)
                .blocks(List.of(ParsedBlock.builder()
                    .content(text.trim())
                    .chunkType(ChunkType.TEXT)
                    .build()))
                .build());
        }
        return pages;
    }

    private String extractTitle(String section) {
        String firstLine = section.trim().split("\n")[0];
        return firstLine.replaceFirst("^#{1,3}\\s*", "").trim();
    }
}
