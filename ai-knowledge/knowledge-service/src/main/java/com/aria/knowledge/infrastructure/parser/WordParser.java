package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Word 文档解析器（基于 Apache POI，支持 .docx）。
 * 提取段落和标题文本，保留段落分隔符。
 */
@Slf4j
@Component
public class WordParser extends AbstractDocumentParser {

    @Override
    protected ParsedDocument doParse(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.debug("Word 段落提取完成，段落数={}，字符数={}",
                doc.getParagraphs().size(), text.length());
            return ParsedDocument.ofSinglePage(text, null);
        } catch (IOException e) {
            throw new BusinessException(5001, "Word 文档解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "DOCX";
    }
}
